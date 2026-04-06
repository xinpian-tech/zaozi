// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.Interface
import me.jiuyang.testlib.*

import org.llvm.mlir.scalalib.capi.ir.{given_ContextApi, Block, Context, ContextApi}

import java.lang.foreign.Arena
import utest.*
import me.jiuyang.zaozi.magic.macros.generator

case class VecSpecParameter(width: Int, vecCount: Int) extends Parameter
given upickle.default.ReadWriter[VecSpecParameter] = upickle.default.macroRW

class VecSpecLayers(parameter: VecSpecParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class VecSpecIO(parameter: VecSpecParameter) extends HWBundle(parameter):
  val a        = Flipped(Vec(parameter.vecCount, Bits(parameter.width)))
  val au       = Flipped(Vec(parameter.vecCount, UInt(parameter.width)))
  val idx      = Flipped(UInt(BigInt(parameter.vecCount).bitLength))
  val b        = Aligned(Vec(parameter.vecCount, Bits(parameter.width)))
  val out      = Aligned(Bits(parameter.width))
  val flatuint = Aligned(UInt(parameter.width * parameter.vecCount))

class VecSpecProbe(parameter: VecSpecParameter) extends DVBundle[VecSpecParameter, VecSpecLayers](parameter)

object VecSpec extends TestSuite:
  val tests = Tests:
    test("Assign"):
      @generator
      object Assign extends Generator[VecSpecParameter, VecSpecLayers, VecSpecIO, VecSpecProbe] with HasVerilogTest:
        def architecture(parameter: VecSpecParameter) =
          val io = summon[Interface[VecSpecIO]]
          io.b := io.a
          io.out.dontCare()
          io.flatuint.dontCare()
      Assign.verilogTest(VecSpecParameter(8, 4))(
        "assign b_0 = a_0",
        "assign b_1 = a_1",
        "assign b_2 = a_2",
        "assign b_3 = a_3"
      )
    test("AsBits"):
      @generator
      object AsBits extends Generator[VecSpecParameter, VecSpecLayers, VecSpecIO, VecSpecProbe] with HasVerilogTest:
        def architecture(parameter: VecSpecParameter) =
          val io = summon[Interface[VecSpecIO]]
          io.b := io.a.asBits.asVec(Bits(parameter.width))
          io.out.dontCare()
          io.flatuint.dontCare()
      AsBits.verilogTest(VecSpecParameter(8, 4))(
        "assign b_0 = a_0",
        "assign b_1 = a_1",
        "assign b_2 = a_2",
        "assign b_3 = a_3"
      )
    test("AsBits asUInt"):
      @generator
      object AsBitsAsUInt
          extends Generator[VecSpecParameter, VecSpecLayers, VecSpecIO, VecSpecProbe]
          with HasMlirTest
          with HasVerilogTest:
        def architecture(parameter: VecSpecParameter) =
          val io = summon[Interface[VecSpecIO]]
          io.b.dontCare()
          io.out.dontCare()
          io.flatuint := io.au.asBits.asUInt
      AsBitsAsUInt.mlirTest(VecSpecParameter(8, 4)): out =>
        val line = out.split("\n").find(_.contains("firrtl.node"))
        line.exists(l => l.contains("firrtl.uint") && !l.contains("vector"))
        && out.contains("firrtl.bitcast")
      AsBitsAsUInt.verilogTest(VecSpecParameter(8, 4))(
        "assign flatuint ="
      )
    test("Dynamic index"):
      @generator
      object DynamicIndex
          extends Generator[VecSpecParameter, VecSpecLayers, VecSpecIO, VecSpecProbe]
          with HasFirrtlTest:
        def architecture(parameter: VecSpecParameter) =
          val io = summon[Interface[VecSpecIO]]
          io.b.dontCare()
          io.out := io.a.ref(io.idx)
          io.flatuint.dontCare()
      DynamicIndex.firrtlTest(VecSpecParameter(8, 4))(
        "connect io.out, io.a[io.idx]"
      )

    test("Dynamic index apply"):
      @generator
      object DynamicIndexApply
          extends Generator[VecSpecParameter, VecSpecLayers, VecSpecIO, VecSpecProbe]
          with HasFirrtlTest:
        def architecture(parameter: VecSpecParameter) =
          val io = summon[Interface[VecSpecIO]]
          io.b.dontCare()
          io.out := io.a(io.idx)
          io.flatuint.dontCare()
      DynamicIndexApply.firrtlTest(VecSpecParameter(8, 4))(
        "connect io.out, io.a[io.idx]"
      )

    test("Static index"):
      @generator
      object StaticIndex
          extends Generator[VecSpecParameter, VecSpecLayers, VecSpecIO, VecSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: VecSpecParameter) =
          val io = summon[Interface[VecSpecIO]]
          io.b.dontCare()
          io.out := io.a.ref(3)
          io.flatuint.dontCare()
      StaticIndex.verilogTest(VecSpecParameter(8, 4))(
        "assign out = a_3"
      )

    test("Static index apply"):
      @generator
      object StaticIndexApply
          extends Generator[VecSpecParameter, VecSpecLayers, VecSpecIO, VecSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: VecSpecParameter) =
          val io = summon[Interface[VecSpecIO]]
          io.b.dontCare()
          io.out := io.a(3)
          io.flatuint.dontCare()
      StaticIndexApply.verilogTest(VecSpecParameter(8, 4))(
        "assign out = a_3"
      )

    test("Named static index apply"):
      @generator
      object NamedStaticIndexApply
          extends Generator[VecSpecParameter, VecSpecLayers, VecSpecIO, VecSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: VecSpecParameter) =
          val io = summon[Interface[VecSpecIO]]
          io.b.dontCare()
          io.out := io.a(idx = 3)
          io.flatuint.dontCare()
      NamedStaticIndexApply.verilogTest(VecSpecParameter(8, 4))(
        "assign out = a_3"
      )

    test("Apply with incorrect named argument"):
      @generator
      object IncorrectNamedArgument
          extends Generator[VecSpecParameter, VecSpecLayers, VecSpecIO, VecSpecProbe]
          with HasCompileErrorTest:
        def architecture(parameter: VecSpecParameter) =
          val io = summon[Interface[VecSpecIO]]
          io.b.dontCare()
          io.out.dontCare()
          io.flatuint.dontCare()
          compileError("""io.out := io.a(invalid_name = 3)""").check(
            "",
            "Unexpected named arguments invalid_name"
          )
      IncorrectNamedArgument.compileErrorTest(VecSpecParameter(8, 4))

    test("Static index assign"):
      @generator
      object StaticIndexAssign
          extends Generator[VecSpecParameter, VecSpecLayers, VecSpecIO, VecSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: VecSpecParameter) =
          val io = summon[Interface[VecSpecIO]]
          io.b.dontCare()
          io.out.dontCare()
          io.b(2) := io.a(2)
          io.flatuint.dontCare()
      StaticIndexAssign.verilogTest(VecSpecParameter(8, 4))(
        "assign b_2 = a_2"
      )

    test("Dynamic index assign"):
      @generator
      object DynamicIndexAssign
          extends Generator[VecSpecParameter, VecSpecLayers, VecSpecIO, VecSpecProbe]
          with HasFirrtlTest:
        def architecture(parameter: VecSpecParameter) =
          val io = summon[Interface[VecSpecIO]]
          io.b.dontCare()
          io.out.dontCare()
          io.b(io.idx) := io.a(io.idx)
          io.flatuint.dontCare()
      DynamicIndexAssign.firrtlTest(VecSpecParameter(8, 4))(
        "connect io.b[io.idx], io.a[io.idx]"
      )

    test("Apply with incorrect number of arguments"):
      @generator
      object IncorrectNumberOfArguments
          extends Generator[VecSpecParameter, VecSpecLayers, VecSpecIO, VecSpecProbe]
          with HasCompileErrorTest:
        def architecture(parameter: VecSpecParameter) =
          val io = summon[Interface[VecSpecIO]]
          io.b.dontCare()
          io.out.dontCare()
          io.flatuint.dontCare()
          compileError("""io.out := io.a(1, 2)""").check(
            "",
            "Expected 1 args, but got 2"
          )
      IncorrectNumberOfArguments.compileErrorTest(VecSpecParameter(8, 4))
