// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.utlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

private[utlib] final case class LibHarnessParameter(spec: AbiSpec) extends Parameter

private[utlib] class LibHarnessLayers(parameter: LibHarnessParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

/** The harness ports *are* the ABI contract, flattened for poke/peek by an external tool:
  *   - clock/reset (when the DUT has them) and every drive port become inputs the frontend pokes;
  *   - every probe point becomes an output port `probe_<name>` the frontend peeks.
  *
  * Drive and probe can share a name (e.g. `A` is both the driven input and an observed point), so probe ports are
  * prefixed to keep the flat port namespace unique.
  */
private[utlib] class LibHarnessIO(parameter: LibHarnessParameter) extends HWRecord(parameter):
  private val spec = parameter.spec
  val clock        = spec.clock.map(p => Flipped(p.name, Clock())).toSeq
  val reset        = spec.reset.map(p => Flipped(p.name, Reset())).toSeq
  val drives       = spec.drive.map(p => Flipped(LibHarness.drivePort(p.name), Bits(p.width)))
  val probes       = spec.probe.map(p => Aligned(LibHarness.probePort(p.name), Bits(p.width)))

private[utlib] class LibHarnessProbe(parameter: LibHarnessParameter)
    extends DVBundle[LibHarnessParameter, LibHarnessLayers](parameter)

private[utlib] object LibHarness:
  /** How a drive port named `name` appears as a top-level input port. Prefixed so it never collides with a probe of the
    * same name and is always a valid C++ identifier (a DUT input called `bool` would otherwise clash with the C++
    * keyword in the frontend).
    */
  def drivePort(name: String): String = s"drive_$name"

  /** How a probe point named `name` appears as a top-level output port. */
  def probePort(name: String): String = s"probe_$name"

/** Lowers the DUT's `(drive, probe)` contract into a flat SystemVerilog module whose ports *are* the contract: each
  * drive port and clock/reset an input, each probe point a `probe_<name>` output. It has no loop, clock oscillator, or
  * done/timeout logic — an external tool drives it, poking the inputs and peeking the probe outputs. This is the
  * `emitLib` artifact; driving it (and running any simulator) is out of the framework's scope.
  */
private[utlib] final class LibHarnessGenerator[
  PARAM <: Parameter,
  L <: LayerInterface[PARAM],
  I <: HWInterface[PARAM],
  P <: DVInterface[PARAM, L]
](dut:          Generator[PARAM, L, I, P],
  dutParameter: PARAM)
    extends Generator[LibHarnessParameter, LibHarnessLayers, LibHarnessIO, LibHarnessProbe]:

  // Deterministic so the caller (and lit tests) can name the top module without a lookup.
  override def moduleName(parameter: LibHarnessParameter): String = s"Lib_${dut.moduleName(dutParameter)}"

  def layers(parameter:    LibHarnessParameter): LibHarnessLayers = new LibHarnessLayers(parameter)
  def interface(parameter: LibHarnessParameter): LibHarnessIO     = new LibHarnessIO(parameter)
  def probe(parameter:     LibHarnessParameter): LibHarnessProbe  = new LibHarnessProbe(parameter)

  def parseParameter(args: Seq[String]): LibHarnessParameter =
    throw new UnsupportedOperationException("the lib harness is elaborated through UTGenerator")
  def main(args: Array[String]):         Unit                =
    throw new UnsupportedOperationException("the lib harness is elaborated through UTGenerator")

  def architecture(parameter: LibHarnessParameter) =
    val io       = summon[Interface[LibHarnessIO]]
    val instance = dut.instantiate(dutParameter)
    val spec     = parameter.spec

    val dutInterface = dut.interface(dutParameter)
    dutInterface.toMlirType
    val inputFields  = dutInterface.elements.filter(_.isFlipped)

    // Forward clock/reset straight through.
    spec.clock.foreach(p => instance.io.field[Clock](p.name) := io.field[Clock](p.name))
    spec.reset.foreach(p => instance.io.field[Reset](p.name) := io.field[Reset](p.name))

    // Each drive port is a flat `Bits` input the frontend pokes; cast it to the DUT input's
    // actual type (Bool / UInt / SInt / Bits) so the connect is well-typed.
    spec.drive.foreach { p =>
      val poked = io.field[Bits](LibHarness.drivePort(p.name))
      val field = inputFields
        .find(_.name == p.name)
        .getOrElse(throw new IllegalStateException(s"drive port ${p.name} is not an input of ${spec.dut}"))
      field.dataType match
        case _: Bool => instance.io.field[Bool](p.name) := poked.asBool
        case _: SInt => instance.io.field[SInt](p.name) := poked.asSInt
        case _: UInt => instance.io.field[UInt](p.name) := poked.asUInt
        case _ => instance.io.field[Bits](p.name) := poked
    }

    // Read each probe point through the Verification layer and expose it as the matching
    // output port. The probe is observation-only — it drives nothing in the DUT.
    given LayerTree = Layer("Verification").toLayerTree
    spec.probe.foreach { p =>
      val w = Wire(Bits(p.width))
      w <== instance
        .probe(
          using summon[TypeImpl]
        )
        .subfield[RProbe[Bits]](p.name)
      io.field[Bits](LibHarness.probePort(p.name)) := w
    }
