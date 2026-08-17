// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.utlib

import scala.language.dynamics

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.utlib.magic.{dpiDriveSelectDynamic, dpiProbeSelectDynamic}

import org.llvm.mlir.scalalib.capi.ir.Context

import java.lang.foreign.Arena

/** How a boundary signal crosses the testbench↔DUT interface, from the testbench's point of
  * view.
  *
  *   - [[Clock]]/[[Reset]] are supplied by the driver top, not carried per cycle.
  *   - [[Drive]] is a DUT input the testbench feeds each cycle (from the DUT's IO).
  *   - [[Probe]] is a DV observation point the testbench samples (from the DUT's Probe).
  */
enum DPIRole:
  case Clock, Reset, Drive, Probe

object DPIRole:
  given upickle.default.ReadWriter[DPIRole] =
    upickle.default.readwriter[String].bimap[DPIRole](_.toString, DPIRole.valueOf)

/** One signal on the DPI contract: its stable name (the DPI/JSON key), how it crosses the
  * boundary, and its bit width and signedness (which map to a DPI-C type on the software
  * backend and to the solver's per-port type on the Zaozi side).
  */
final case class DPIPort(
  name:   String,
  role:   DPIRole,
  width:  Int,
  signed: Boolean)

object DPIPort:
  import DPIRole.given
  given upickle.default.ReadWriter[DPIPort] = upickle.default.macroRW

/** The DPI contract for one DUT: the typed transaction interface the testbench drives and
  * observes, serialized as the single source shared by the sim-dialect frontend, the JSON
  * interchange, and (later) an external Rust/Python DPI frontend.
  *
  * It is derived from the DUT's own types — its IO (the [[DPIRole.Drive]] side) and its Probe
  * (the [[DPIRole.Probe]] side) — so the contract is a function of `(I, P)`, not written by
  * hand.
  */
final case class DPI(
  dut:   String,
  ports: Seq[DPIPort]):

  def drive: Seq[DPIPort] = ports.filter(_.role == DPIRole.Drive)
  def probe: Seq[DPIPort] = ports.filter(_.role == DPIRole.Probe)
  def clock: Option[DPIPort] = ports.find(_.role == DPIRole.Clock)
  def reset: Option[DPIPort] = ports.find(_.role == DPIRole.Reset)

  def toJson: String = upickle.default.write(this, indent = 2)

object DPI:
  import DPIPort.given
  given upickle.default.ReadWriter[DPI] = upickle.default.macroRW

  def fromJson(text: String): DPI = upickle.default.read[DPI](text)

  /** Derive the contract from a DUT's IO and Probe interfaces.
    *
    * Drive/clock/reset come from the IO's flipped (input) fields; the probe points come from
    * the Probe. Aligned IO outputs are intentionally not part of the contract — a DUT that
    * wants its outputs observed exposes them through its Probe, which is the designed DV
    * surface.
    */
  def derive[I <: HWInterface[?], P <: Aggregate](
    dut:   String,
    io:    I,
    probe: P
  )(
    using Arena,
    Context,
    TypeImpl
  ): DPI =
    io.toMlirType
    probe.toMlirType
    val driven = io.elements.collect {
      case field if field.isFlipped =>
        DPIPort(field.name, roleOfInput(field.dataType), field.dataType.width, isSigned(field.dataType))
    }
    val probed = probe.elements.map { field =>
      // A probe field's type wraps the observed data; unwrap to the base type for width/sign.
      val base = field.dataType match
        case p: RProbe[?]  => p.baseType
        case p: RWProbe[?] => p.baseType
        case other         => other
      DPIPort(field.name, DPIRole.Probe, base.width, isSigned(base))
    }
    DPI(dut, (driven ++ probed).toSeq)

  private def roleOfInput(data: Data): DPIRole = data match
    case _: Clock => DPIRole.Clock
    case _: Reset => DPIRole.Reset
    case _        => DPIRole.Drive

  private def isSigned(data: Data): Boolean = data match
    case _: SInt => true
    case _       => false

/** Drive ports of a [[TypedDPI]], addressed by name and checked at compile time against the
  * DUT's IO type `I`: `contract.drive.A` resolves only if `A` is a field of the DUT's IO.
  */
final class DriveAccess[I <: HWInterface[?]] private[utlib] (value: DPI) extends Dynamic:
  def field(name: String): DPIPort =
    value.ports.find(p => p.name == name && p.role != DPIRole.Probe).getOrElse(
      throw new NoSuchElementException(s"${value.dut}: no drive port '$name'")
    )
  transparent inline def selectDynamic(name: String): DPIPort = ${ dpiDriveSelectDynamic[I]('this, 'name) }

/** Probe ports of a [[TypedDPI]], addressed by name and checked at compile time against the
  * DUT's Probe type `P`.
  */
final class ProbeAccess[P <: DVInterface[?, ?]] private[utlib] (value: DPI) extends Dynamic:
  def field(name: String): DPIPort =
    value.probe.find(_.name == name).getOrElse(
      throw new NoSuchElementException(s"${value.dut}: no probe port '$name'")
    )
  transparent inline def selectDynamic(name: String): DPIPort = ${ dpiProbeSelectDynamic[P]('this, 'name) }

/** The DPI contract as a dependent type on the DUT's interfaces `(I, P)`.
  *
  * The underlying [[value]] is the serializable contract; `drive`/`probe` give typed,
  * compile-time-checked access so that referring to a port the DUT does not have is a
  * compile error rather than a runtime lookup miss.
  */
final class TypedDPI[I <: HWInterface[?], P <: DVInterface[?, ?]] private[utlib] (val value: DPI):
  val drive: DriveAccess[I] = new DriveAccess[I](value)
  val probe: ProbeAccess[P] = new ProbeAccess[P](value)
