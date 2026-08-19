// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.utlib

import scala.language.dynamics

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.utlib.magic.{portDriveSelectDynamic, portProbeSelectDynamic}

import org.llvm.mlir.scalalib.capi.ir.Context

import java.lang.foreign.Arena

/** How a boundary signal crosses the testbench↔DUT interface, from the testbench's point of view.
  *
  *   - [[Clock]]/[[Reset]] are supplied by the driver top, not carried per cycle.
  *   - [[Drive]] is a DUT input the testbench feeds each cycle (from the DUT's IO).
  *   - [[Probe]] is a DV observation point the testbench samples (from the DUT's Probe).
  */
enum PortRole:
  case Clock, Reset, Drive, Probe

object PortRole:
  given upickle.default.ReadWriter[PortRole] =
    upickle.default.readwriter[String].bimap[PortRole](_.toString, PortRole.valueOf)

/** One signal on the ABI contract: its stable name (the DPI/JSON key), how it crosses the boundary, and its bit width
  * and signedness (which map to a DPI-C type on the software backend and to the solver's per-port type on the Zaozi
  * side).
  */
final case class Port(
  name:   String,
  role:   PortRole,
  width:  Int,
  signed: Boolean)

object Port:
  import PortRole.given
  given upickle.default.ReadWriter[Port] = upickle.default.macroRW

/** The ABI contract specification for one DUT: the typed transaction interface the testbench drives and observes,
  * serialized as the single source shared by the sim-dialect frontend, the JSON interchange, and (later) an external
  * Rust/Python DPI frontend.
  *
  * It is derived from the DUT's own types — its IO (the [[PortRole.Drive]] side) and its Probe (the [[PortRole.Probe]]
  * side) — so the spec is a function of `(I, P)`, not written by hand. [[Ports]] is the type-level view on top of it.
  */
final case class PortSpec(
  dut:   String,
  ports: Seq[Port],
  version: String):

  def drive: Seq[Port]    = ports.filter(_.role == PortRole.Drive)
  def probe: Seq[Port]    = ports.filter(_.role == PortRole.Probe)
  def clock: Option[Port] = ports.find(_.role == PortRole.Clock)
  def reset: Option[Port] = ports.find(_.role == PortRole.Reset)

  def toJson: String = upickle.default.write(this, indent = 2)

object PortSpec:
  import Port.given
  given upickle.default.ReadWriter[PortSpec] = upickle.default.macroRW

  /** The DPI ABI version this contract targets. See `doc/dpi-abi.md`. Consumers must reject a contract whose `version`
    * they do not understand.
    */
  val Version: String = "1.0"

  def fromJson(text: String): PortSpec = upickle.default.read[PortSpec](text)

  /** Derive the spec from a DUT's IO and Probe interfaces.
    *
    * Drive/clock/reset come from the IO's flipped (input) fields; the probe points come from the Probe. Aligned IO
    * outputs are intentionally not part of the contract — a DUT that wants its outputs observed exposes them through
    * its Probe, which is the designed DV surface.
    */
  def derive[I <: HWInterface[?], P <: Aggregate](
    dut:   String,
    io:    I,
    probe: P
  )(
    using Arena,
    Context,
    TypeImpl
  ): PortSpec =
    io.toMlirType
    probe.toMlirType
    val driven = io.elements.collect {
      case field if field.isFlipped =>
        Port(field.name, roleOfInput(field.dataType), field.dataType.width, isSigned(field.dataType))
    }
    val probed = probe.elements.map { field =>
      // A probe field's type wraps the observed data; unwrap to the base type for width/sign.
      val base = field.dataType match
        case p: RProbe[?]  => p.baseType
        case p: RWProbe[?] => p.baseType
        case other => other
      Port(field.name, PortRole.Probe, base.width, isSigned(base))
    }
    PortSpec(dut, (driven ++ probed).toSeq, PortSpec.Version)

  private def roleOfInput(data: Data): PortRole = data match
    case _: Clock => PortRole.Clock
    case _: Reset => PortRole.Reset
    case _ => PortRole.Drive

  private def isSigned(data: Data): Boolean = data match
    case _: SInt => true
    case _ => false

/** Drive ports of a [[Ports]], addressed by name and checked at compile time against the DUT's IO type `I`:
  * `abi.drive.A` resolves only if `A` is a field of the DUT's IO.
  */
final class DriveAccess[I <: HWInterface[?]] private[utlib] (spec: PortSpec) extends Dynamic:
  def field(name: String):                            Port =
    spec.ports
      .find(p => p.name == name && p.role != PortRole.Probe)
      .getOrElse(
        throw new NoSuchElementException(s"${spec.dut}: no drive port '$name'")
      )
  transparent inline def selectDynamic(name: String): Port = ${ portDriveSelectDynamic[I]('this, 'name) }

/** Probe ports of a [[Ports]], addressed by name and checked at compile time against the DUT's Probe type `P`.
  */
final class ProbeAccess[P <: DVInterface[?, ?]] private[utlib] (spec: PortSpec) extends Dynamic:
  def field(name: String):                            Port =
    spec.probe
      .find(_.name == name)
      .getOrElse(
        throw new NoSuchElementException(s"${spec.dut}: no probe port '$name'")
      )
  transparent inline def selectDynamic(name: String): Port = ${ portProbeSelectDynamic[P]('this, 'name) }

/** The ABI contract as a dependent type on the DUT's interfaces `(I, P)`.
  *
  * The underlying [[spec]] is the serializable contract; `drive`/`probe` give typed, compile-time-checked access so
  * that referring to a port the DUT does not have is a compile error rather than a runtime lookup miss.
  */
final class Ports[I <: HWInterface[?], P <: DVInterface[?, ?]] private[utlib] (val spec: PortSpec):
  val drive: DriveAccess[I] = new DriveAccess[I](spec)
  val probe: ProbeAccess[P] = new ProbeAccess[P](spec)
