// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.utlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

private[utlib] final case class LibHarnessParameter(spec: DPISpec) extends Parameter

private[utlib] class LibHarnessLayers(parameter: LibHarnessParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

/** The harness ports *are* the DPI contract, flattened for poke/peek by an external frontend:
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
  val drives       = spec.drive.map(p => Flipped(p.name, Bits(p.width)))
  val probes       = spec.probe.map(p => Aligned(LibHarness.probePort(p.name), Bits(p.width)))

private[utlib] class LibHarnessProbe(parameter: LibHarnessParameter)
    extends DVBundle[LibHarnessParameter, LibHarnessLayers](parameter)

private[utlib] object LibHarness:
  /** How a probe point named `name` appears as a top-level output port. */
  def probePort(name: String): String = s"probe_$name"

/** The single harness of the UT flow: it turns the DUT's `(drive, probe)` contract into a flat SystemVerilog module
  * whose ports the external frontend pokes and peeks. It contains no loop, no clock oscillator and no done/timeout
  * logic — the frontend owns the loop. Verilator builds this module into the "library" (the Verilated model); a
  * frontend is just one caller of it, so replaying the solver's stimulus is only one frontend among many.
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

    // Forward the clock/reset/drive input ports to the DUT's same-named inputs.
    spec.clock.foreach(p => instance.io.field[Clock](p.name) := io.field[Clock](p.name))
    spec.reset.foreach(p => instance.io.field[Reset](p.name) := io.field[Reset](p.name))
    spec.drive.foreach(p => instance.io.field[Bits](p.name) := io.field[Bits](p.name))

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
