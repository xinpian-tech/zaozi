// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

private[utlib] final case class DefaultUTHarnessParameter(
  stimulus:      StimulusData,
  timeoutCycles: Int,
  trace:         Boolean,
  traceFile:     String)
    extends Parameter

private[utlib] class DefaultUTHarnessLayers(parameter: DefaultUTHarnessParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

private[utlib] class DefaultUTHarnessIO(parameter: DefaultUTHarnessParameter) extends HWBundle(parameter)

private[utlib] class DefaultUTHarnessProbe(parameter: DefaultUTHarnessParameter)
    extends DVBundle[DefaultUTHarnessParameter, DefaultUTHarnessLayers](parameter)

/** The only harness supplied by utlib: flat numeric inputs, plus at most one clock and reset input. */
private[utlib] final class DefaultUTHarnessGenerator[
  PARAM <: Parameter,
  L <: LayerInterface[PARAM],
  I <: HWInterface[PARAM],
  P <: DVInterface[PARAM, L]
](dut:          Generator[PARAM, L, I, P],
  dutParameter: PARAM)
    extends Generator[
      DefaultUTHarnessParameter,
      DefaultUTHarnessLayers,
      DefaultUTHarnessIO,
      DefaultUTHarnessProbe
    ]:

  override def moduleName(parameter: DefaultUTHarnessParameter): String =
    s"UT_${dut.moduleName(dutParameter)}_${parameter.hashCode.toHexString}"

  def layers(parameter: DefaultUTHarnessParameter): DefaultUTHarnessLayers = new DefaultUTHarnessLayers(parameter)

  def interface(parameter: DefaultUTHarnessParameter): DefaultUTHarnessIO = new DefaultUTHarnessIO(parameter)

  def probe(parameter: DefaultUTHarnessParameter): DefaultUTHarnessProbe = new DefaultUTHarnessProbe(parameter)

  def parseParameter(args: Seq[String]): DefaultUTHarnessParameter =
    throw new UnsupportedOperationException("the internal UT harness has no command-line parameter parser")

  def main(args: Array[String]): Unit =
    throw new UnsupportedOperationException("the internal UT harness is elaborated through UTGenerator")

  def architecture(parameter: DefaultUTHarnessParameter) =
    val controllerParameter = SimulationControllerParameter(
      parameter.timeoutCycles,
      parameter.trace,
      parameter.traceFile
    )
    val controller          = SimulationController.instantiate(controllerParameter)
    val instance            = dut.instantiate(dutParameter)

    val dutInterface = dut.interface(dutParameter)
    dutInterface.toMlirType
    val inputFields  = dutInterface.elements.filter(_.isFlipped)
    val clocks       = inputFields.collect { case field if field.dataType.isInstanceOf[Clock] => field }
    val resets       = inputFields.collect { case field if field.dataType.isInstanceOf[Reset] => field }
    require(clocks.size <= 1, s"${parameter.stimulus.dut}: the default UT harness supports at most one clock input")
    require(resets.size <= 1, s"${parameter.stimulus.dut}: the default UT harness supports at most one reset input")

    clocks.foreach(field => instance.io.field[Clock](field.name) := controller.io.clock)
    resets.foreach(field => instance.io.field[Reset](field.name) := controller.io.reset)

    given ClockScope = ClockScope.posedge(controller.io.clock)
    given ResetScope = ResetScope.syncActiveHigh(controller.io.reset)

    val stages = (0 to parameter.stimulus.cycles).map(index => RegInit((index == 0).B))
    (parameter.stimulus.cycles to 1 by -1).foreach(index => stages(index) := stages(index - 1))
    stages(0) := false.B
    controller.io.done := stages(parameter.stimulus.cycles)

    val numericInputs =
      inputFields.filterNot(field => field.dataType.isInstanceOf[Clock] || field.dataType.isInstanceOf[Reset])
    val expectedNames = numericInputs.map(_.name).toSet
    require(
      parameter.stimulus.inputs.keySet == expectedNames,
      s"stimulus inputs ${parameter.stimulus.inputs.keySet.toSeq.sorted.mkString(", ")} do not match DUT inputs " +
        expectedNames.toSeq.sorted.mkString(", ")
    )

    numericInputs.foreach { field =>
      val width  = field.dataType.width
      val values = parameter.stimulus.inputs(field.name)
      field.dataType match
        case _: Bool =>
          require(values.forall(value => value == 0 || value == 1), s"input ${field.name}: Bool values must be 0 or 1")
          instance.io.field[Bool](field.name) := false.B
          values.zipWithIndex.foreach { case (value, cycle) =>
            when(stages(cycle)) {
              instance.io.field[Bool](field.name) := (value != 0).B
            }
          }
        case _: Bits =>
          requireSignedValues(field.name, width, values)
          instance.io.field[Bits](field.name) := BigInt(0).B(width)
          values.zipWithIndex.foreach { case (value, cycle) =>
            val encoded = if value < 0 then value + (BigInt(1) << width) else value
            when(stages(cycle)) {
              instance.io.field[Bits](field.name) := encoded.B(width)
            }
          }
        case _: UInt =>
          requireUnsignedValues(field.name, width, values)
          instance.io.field[UInt](field.name) := BigInt(0).U(width)
          values.zipWithIndex.foreach { case (value, cycle) =>
            when(stages(cycle)) {
              instance.io.field[UInt](field.name) := value.U(width)
            }
          }
        case _: SInt =>
          requireSignedValues(field.name, width, values)
          instance.io.field[SInt](field.name) := BigInt(0).S(width)
          values.zipWithIndex.foreach { case (value, cycle) =>
            when(stages(cycle)) {
              instance.io.field[SInt](field.name) := value.S(width)
            }
          }
        case other =>
          throw new IllegalArgumentException(
            s"input ${field.name}: ${other.getClass.getSimpleName} is not supported by the default UT harness"
          )
    }

  private def requireUnsignedValues(name: String, width: Int, values: Seq[BigInt]): Unit =
    val limit = BigInt(1) << width
    require(
      values.forall(value => value >= 0 && value < limit),
      s"input $name: unsigned $width-bit values must be in 0 until $limit"
    )

  private def requireSignedValues(name: String, width: Int, values: Seq[BigInt]): Unit =
    val limit = BigInt(1) << (width - 1)
    require(
      values.forall(value => value >= -limit && value < limit),
      s"input $name: signed $width-bit values must be in ${-limit} until $limit"
    )
