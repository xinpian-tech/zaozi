// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.magic.macros.generator
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena
import utest.*

final case class MixedInputParameter(
  width:     Int,
  boolValue: Int = 1,
  bitsValue: Int = -1,
  uintValue: Int = 9,
  sintValue: Int = -1)
    extends Parameter

object MixedInputParameter:
  given upickle.default.ReadWriter[MixedInputParameter] = upickle.default.macroRW

class MixedInputLayers(parameter: MixedInputParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class MixedInputIO(parameter: MixedInputParameter) extends HWBundle(parameter):
  val bool = Flipped(Bool())
  val bits = Flipped(Bits(parameter.width))
  val uint = Flipped(UInt(parameter.width))
  val sint = Flipped(SInt(parameter.width))

class MixedInputProbe(parameter: MixedInputParameter) extends DVBundle[MixedInputParameter, MixedInputLayers](parameter)

@generator
object MixedInput
    extends Generator[MixedInputParameter, MixedInputLayers, MixedInputIO, MixedInputProbe]
    with HasUT[MixedInputParameter, MixedInputIO]:
  def constraints(
    parameter: MixedInputParameter
  )(
    using Arena,
    Context,
    Block,
    ConstraintInterface[MixedInputIO]
  ): Unit =
    import me.jiuyang.smtlib.default.{*, given}

    val io = summon[ConstraintInterface[MixedInputIO]]
    smtAssert(io.bool.at(0) === parameter.boolValue.S)
    smtAssert(io.bits.at(0) === parameter.bitsValue.S)
    smtAssert(io.uint.at(0) === parameter.uintValue.S)
    smtAssert(io.sint.at(0) === parameter.sintValue.S)

  def architecture(parameter: MixedInputParameter) =
    val _ = summon[Interface[MixedInputIO]]
    ()

object UTGeneratorTest extends TestSuite:
  private val outputRoot = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)

  private def generator(
    parameter: MixedInputParameter
  ): UTGenerator[MixedInputParameter, MixedInputLayers, MixedInputIO, MixedInputProbe] =
    UTGenerator(
      MixedInput,
      parameter,
      cycles = 1,
      outputDirectory = outputRoot / MixedInput.moduleName(parameter)
    )

  val tests: Tests = Tests:
    test("the default harness drives Bool, Bits, UInt, and SInt inputs"):
      val dut      = generator(MixedInputParameter(4))
      val stimulus = dut.solve()
      assert(dut.outputDirectory == outputRoot / MixedInput.moduleName(dut.parameter))
      assert(stimulus.io.bool.values == Vector(1))
      assert(stimulus.io.bits.values == Vector(-1))
      assert(stimulus.io.uint.values == Vector(9))
      assert(stimulus.io.sint.values == Vector(-1))

      val result = dut.runStimulus(stimulus)
      assert(result.exitCode == 0)
      assert(result.log.contains("HARNESS-DONE"))

    test("the SMT integer backend rejects unsupported port widths"):
      val dut     = generator(MixedInputParameter(31))
      val message = intercept[IllegalArgumentException](dut.solve()).getMessage
      assert(message.contains("supported 1..30 range"))

    test("default constraints reject values that do not fit their input width"):
      val invalidParameters = Seq(
        MixedInputParameter(4, boolValue = 2),
        MixedInputParameter(4, bitsValue = -9),
        MixedInputParameter(4, uintValue = 16),
        MixedInputParameter(4, sintValue = 8)
      )
      invalidParameters.foreach { parameter =>
        val message = intercept[RuntimeException](generator(parameter).solve()).getMessage
        assert(message.contains("Unsat"))
      }
