// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.default

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.circt.scalalib.dialect.firrtl.operation.RegResetPolarity

case class SynchronizedResetParameter(stages: Int, polarity: RegResetPolarity) extends Parameter:
  require(stages >= 2, "SynchronizedReset stages must be at least 2")

given upickle.default.ReadWriter[SynchronizedResetParameter] = upickle.default.macroRW

class SynchronizedResetLayers(parameter: SynchronizedResetParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class SynchronizedResetIO(parameter: SynchronizedResetParameter) extends HWBundle(parameter):
  val clock             = Flipped(Clock())
  val reset             = Flipped(Reset())
  val synchronizedReset = Aligned(Reset())

class SynchronizedResetProbe(parameter: SynchronizedResetParameter)
    extends DVBundle[SynchronizedResetParameter, SynchronizedResetLayers](parameter)

@generator
object SynchronizedReset
    extends Generator[
      SynchronizedResetParameter,
      SynchronizedResetLayers,
      SynchronizedResetIO,
      SynchronizedResetProbe
    ]:
  override def moduleName(parameter: SynchronizedResetParameter): String =
    val polaritySuffix = parameter.polarity match
      case RegResetPolarity.PosReset => "activeHigh"
      case RegResetPolarity.NegReset => "activeLow"
    s"SynchronizedReset_stages${parameter.stages}_$polaritySuffix"

  def architecture(parameter: SynchronizedResetParameter) =
    val io           = summon[Interface[SynchronizedResetIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = parameter.polarity match
      case RegResetPolarity.PosReset => ResetScope.asyncActiveHigh(io.reset)
      case RegResetPolarity.NegReset => ResetScope.asyncActiveLow(io.reset)

    val (assertedValue, deassertedValue) = parameter.polarity match
      case RegResetPolarity.PosReset => (true.B, false.B)
      case RegResetPolarity.NegReset => (false.B, true.B)

    val synchronizationStages = Seq.fill(parameter.stages)(RegInit(assertedValue))

    synchronizationStages.head := deassertedValue
    synchronizationStages.tail
      .zip(synchronizationStages)
      .foreach: (sink, source) =>
        sink := source

    io.synchronizedReset := synchronizationStages.last.asReset

    layer("Verification"):
      val inputAsserted  = parameter.polarity match
        case RegResetPolarity.PosReset => io.reset.asBool
        case RegResetPolarity.NegReset => !io.reset.asBool
      val outputAsserted = parameter.polarity match
        case RegResetPolarity.PosReset => io.synchronizedReset.asBool
        case RegResetPolarity.NegReset => !io.synchronizedReset.asBool

      given ClockEvent = posedge(io.clock)

      // 1. output reset must be asserted when reset is asserted
      Assert(inputAsserted.I implies outputAsserted.I, "reset_asserts_output")

      // 2. output reset must not be released early
      (0 until parameter.stages).foreach: cycle =>
        val heldDeasserted = (!inputAsserted) throughout true.B.S.##(cycle)(true.B.S)
        Assert(
          (inputAsserted.S ### heldDeasserted) |-> outputAsserted.S,
          s"reset_not_released_early_$cycle"
        )

      // 3. output reset must be released after stages cycles
      val heldDeassertedForStages = (!inputAsserted) throughout true.B.S.##(parameter.stages)(true.B.S)
      val outputDeasserted        = !outputAsserted

      Assert(
        (inputAsserted.S ### heldDeassertedForStages) |-> outputDeasserted.S,
        "reset_released_after_stages"
      )

      // 4. once released, output reset must remain released until input reset is asserted
      Assert(
        always(outputDeasserted.I implies (outputDeasserted.I until inputAsserted.I)),
        "reset_stays_released_until_reasserted"
      )
