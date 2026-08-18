// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.utlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

private[utlib] final case class DpiHarnessParameter(
  spec:          DPISpec,
  runCycles:     Int,
  timeoutCycles: Int)
    extends Parameter

private[utlib] class DpiHarnessLayers(parameter: DpiHarnessParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

private[utlib] class DpiHarnessIO(parameter: DpiHarnessParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val done  = Aligned(Bool())

private[utlib] class DpiHarnessProbe(parameter: DpiHarnessParameter)
    extends DVBundle[DpiHarnessParameter, DpiHarnessLayers](parameter)

/** A harness that closes the DPI loop: each cycle it reads the DUT's Probe points, hands them
  * to the external DPI function through [[dpiCall]], and drives the DUT's input with the value
  * the frontend returns. The probe reads and the drive connection are ordinary DSL — the
  * probe is just a wire in scope, so nothing here needs a hierarchical reference.
  *
  * Single-drive DUTs only for now: the FIRRTL DPI call yields one result, so a DUT with more
  * than one drive port needs the multi-output shape, which is left for later.
  */
private[utlib] final class DpiHarnessGenerator[
  PARAM <: Parameter,
  L <: LayerInterface[PARAM],
  I <: HWInterface[PARAM],
  P <: DVInterface[PARAM, L]
](dut:          Generator[PARAM, L, I, P],
  dutParameter: PARAM)
    extends Generator[DpiHarnessParameter, DpiHarnessLayers, DpiHarnessIO, DpiHarnessProbe]:

  override def moduleName(parameter: DpiHarnessParameter): String =
    s"DPI_${dut.moduleName(dutParameter)}_${parameter.hashCode.toHexString}"

  def layers(parameter:    DpiHarnessParameter): DpiHarnessLayers = new DpiHarnessLayers(parameter)
  def interface(parameter: DpiHarnessParameter): DpiHarnessIO     = new DpiHarnessIO(parameter)
  def probe(parameter:     DpiHarnessParameter): DpiHarnessProbe  = new DpiHarnessProbe(parameter)

  def parseParameter(args: Seq[String]): DpiHarnessParameter =
    throw new UnsupportedOperationException("the DPI harness is elaborated through UTGenerator")
  def main(args:           Array[String]): Unit                =
    throw new UnsupportedOperationException("the DPI harness is elaborated through UTGenerator")

  def architecture(parameter: DpiHarnessParameter) =
    val io       = summon[Interface[DpiHarnessIO]]
    val instance = dut.instantiate(dutParameter)
    val spec     = parameter.spec

    val dutInterface = dut.interface(dutParameter)
    dutInterface.toMlirType
    val inputFields  = dutInterface.elements.filter(_.isFlipped)
    inputFields.collect { case f if f.dataType.isInstanceOf[Clock] => f }
      .foreach(f => instance.io.field[Clock](f.name) := io.clock)
    inputFields.collect { case f if f.dataType.isInstanceOf[Reset] => f }
      .foreach(f => instance.io.field[Reset](f.name) := io.reset)

    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)

    // One-hot pulse; `done` at runCycles, `timeout` at the guard bound.
    val stages = (0 to parameter.timeoutCycles).map(index => RegInit((index == 0).B))
    (parameter.timeoutCycles to 1 by -1).foreach(index => stages(index) := stages(index - 1))
    stages(0) := false.B
    val done    = stages(parameter.runCycles)
    val timeout = stages(parameter.timeoutCycles)
    io.done := done

    val notReset = Wire(Bool())
    notReset := !(io.reset.asBool)

    // The DPI frontend observes the DUT through its Probe — its designated observation
    // surface. Each probe point is read into a wire and handed to the DPI function; the probe
    // itself drives nothing.
    given LayerTree = Layer("Verification").toLayerTree
    val observed = spec.probe.map { port =>
      val w = Wire(Bits(port.width))
      w <== instance.probe(using summon[TypeImpl]).subfield[RProbe[Bits]](port.name)
      w
    }

    // Hand the observed outputs to the external frontend and drive the DUT input with the
    // value it returns. One drive port is supported.
    require(spec.drive.size == 1, s"${spec.dut}: the DPI harness supports exactly one drive port")
    val drivePort = spec.drive.head
    val driven    = dpiCall(s"${spec.dut}_tick", Bits(drivePort.width), io.clock, notReset, observed*)
    instance.io.field[Bits](drivePort.name) := driven

    val doneNow = Wire(Bool())
    doneNow := done & notReset
    val timeoutNow = Wire(Bool())
    timeoutNow := timeout & notReset
    printf(io.clock, doneNow, "HARNESS-DONE\n")
    stop(io.clock, doneNow, 0)
    printf(io.clock, timeoutNow, "HARNESS-TIMEOUT\n")
    stop(io.clock, timeoutNow, 1)
