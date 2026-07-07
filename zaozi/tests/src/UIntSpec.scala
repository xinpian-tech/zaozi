// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.testlib.*
import org.llvm.mlir.scalalib.capi.ir.{given_ContextApi, Block, Context, ContextApi}
import org.llvm.mlir.scalalib.capi.pass.{given_PassManagerApi, PassManager, PassManagerApi}
import utest.*

import java.lang.foreign.Arena

case class UIntSpecParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[UIntSpecParameter] = upickle.default.macroRW

class UIntSpecLayers(parameter: UIntSpecParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class UIntSpecIO(parameter: UIntSpecParameter) extends HWBundle(parameter):
  val a     = Flipped(UInt(parameter.width))
  val b     = Flipped(UInt(parameter.width))
  val c     = Flipped(UInt(parameter.width))
  val d     = Flipped(Bool())
  val uint  = Aligned(UInt(parameter.width + 1))
  val bits  = Aligned(Bits(parameter.width))
  val bool  = Aligned(Bool())
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())

class UIntSpecProbe(parameter: UIntSpecParameter) extends DVBundle[UIntSpecParameter, UIntSpecLayers](parameter)

object UIntSpec extends TestSuite:
  val tests = Tests:
    test("asBits"):
      @generator
      object AsBits extends Generator[UIntSpecParameter, UIntSpecLayers, UIntSpecIO, UIntSpecProbe] with HasVerilogTest:
        def architecture(parameter: UIntSpecParameter) =
          val io = summon[Interface[UIntSpecIO]]
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits := io.a.asBits
      AsBits.verilogTest(UIntSpecParameter(8))(
        "assign bits = a;"
      )

    test("+"):
      @generator
      object Plus extends Generator[UIntSpecParameter, UIntSpecLayers, UIntSpecIO, UIntSpecProbe] with HasVerilogTest:
        def architecture(parameter: UIntSpecParameter) =
          val io = summon[Interface[UIntSpecIO]]
          io.uint := io.a + io.b
          io.bool.dontCare()
          io.bits.dontCare()
      Plus.verilogTest(UIntSpecParameter(8))(
        "assign uint = {1'h0, a} + {1'h0, b};"
      )

    test("Mixed Referable UInt operators"):
      @generator
      object MixedReferableUInt
          extends Generator[UIntSpecParameter, UIntSpecLayers, UIntSpecIO, UIntSpecProbe]
          with HasVerilogTest:
        def addGeneric[R <: Referable[UInt]](
          ref:  R,
          node: Node[UInt]
        )(
          using Add[UInt, UInt],
          Arena,
          Context,
          Block,
          sourcecode.File,
          sourcecode.Line,
          sourcecode.Name.Machine,
          InstanceContext
        ): Node[UInt] =
          ref + node

        def eqGeneric[R <: Referable[UInt]](
          ref:   R,
          const: Const[UInt]
        )(
          using Eq[UInt, Bool],
          Arena,
          Context,
          Block,
          sourcecode.File,
          sourcecode.Line,
          sourcecode.Name.Machine,
          InstanceContext
        ): Node[Bool] =
          ref === const

        def architecture(parameter: UIntSpecParameter) =
          val io = summon[Interface[UIntSpecIO]]
          io.uint := addGeneric(io.a, Node(io.b))
          io.bool := eqGeneric(Node(io.a), 0.U(parameter.width))
          io.bits.dontCare()
      MixedReferableUInt.verilogTest(UIntSpecParameter(8))(
        "assign uint = {1'h0, a} + {1'h0, b};",
        "assign bool = a == 8'h0;"
      )

    test("-"):
      @generator
      object Minus extends Generator[UIntSpecParameter, UIntSpecLayers, UIntSpecIO, UIntSpecProbe] with HasVerilogTest:
        def architecture(parameter: UIntSpecParameter) =
          val io = summon[Interface[UIntSpecIO]]
          io.uint := io.a - io.b
          io.bool.dontCare()
          io.bits.dontCare()
      Minus.verilogTest(UIntSpecParameter(8))(
        "assign uint = {1'h0, a} - {1'h0, b};"
      )

    test("*"):
      @generator
      object Multiplication
          extends Generator[UIntSpecParameter, UIntSpecLayers, UIntSpecIO, UIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: UIntSpecParameter) =
          val io = summon[Interface[UIntSpecIO]]
          io.uint := (io.a * io.b).asBits.bits(parameter.width, 0).asUInt
          io.bool.dontCare()
          io.bits.dontCare()
      Multiplication.verilogTest(UIntSpecParameter(8))(
        "assign uint = {1'h0, a} * {1'h0, b};"
      )

    test("/"):
      @generator
      object Divide extends Generator[UIntSpecParameter, UIntSpecLayers, UIntSpecIO, UIntSpecProbe] with HasVerilogTest:
        def architecture(parameter: UIntSpecParameter) =
          val io = summon[Interface[UIntSpecIO]]
          io.uint := io.a / io.b
          io.bool.dontCare()
          io.bits.dontCare()
      Divide.verilogTest(UIntSpecParameter(8))(
        "assign uint = {1'h0, a / b};"
      )

    test("%"):
      @generator
      object Modulo extends Generator[UIntSpecParameter, UIntSpecLayers, UIntSpecIO, UIntSpecProbe] with HasVerilogTest:
        def architecture(parameter: UIntSpecParameter) =
          val io = summon[Interface[UIntSpecIO]]
          io.uint := io.a % io.b
          io.bool.dontCare()
          io.bits.dontCare()
      Modulo.verilogTest(UIntSpecParameter(8))(
        "assign uint = {1'h0, a % b};"
      )

    test("<"):
      @generator
      object LessThan
          extends Generator[UIntSpecParameter, UIntSpecLayers, UIntSpecIO, UIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: UIntSpecParameter) =
          val io = summon[Interface[UIntSpecIO]]
          io.uint.dontCare()
          io.bool := io.a < io.b
          io.bits.dontCare()
      LessThan.verilogTest(UIntSpecParameter(8))(
        "assign bool = a < b;"
      )

    test("<="):
      @generator
      object LessEqual
          extends Generator[UIntSpecParameter, UIntSpecLayers, UIntSpecIO, UIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: UIntSpecParameter) =
          val io = summon[Interface[UIntSpecIO]]
          io.uint.dontCare()
          io.bool := io.a <= io.b
          io.bits.dontCare()
      LessEqual.verilogTest(UIntSpecParameter(8))(
        "assign bool = a <= b;"
      )

    test(">"):
      @generator
      object GreaterThan
          extends Generator[UIntSpecParameter, UIntSpecLayers, UIntSpecIO, UIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: UIntSpecParameter) =
          val io = summon[Interface[UIntSpecIO]]
          io.uint.dontCare()
          io.bool := io.a > io.b
          io.bits.dontCare()
      GreaterThan.verilogTest(UIntSpecParameter(8))(
        "assign bool = a > b;"
      )

    test(">="):
      @generator
      object GreaterEqual
          extends Generator[UIntSpecParameter, UIntSpecLayers, UIntSpecIO, UIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: UIntSpecParameter) =
          val io = summon[Interface[UIntSpecIO]]
          io.uint.dontCare()
          io.bool := io.a >= io.b
          io.bits.dontCare()
      GreaterEqual.verilogTest(UIntSpecParameter(8))(
        "assign bool = a >= b;"
      )

    test("==="):
      @generator
      object EqEqEq extends Generator[UIntSpecParameter, UIntSpecLayers, UIntSpecIO, UIntSpecProbe] with HasVerilogTest:
        def architecture(parameter: UIntSpecParameter) =
          val io = summon[Interface[UIntSpecIO]]
          io.uint.dontCare()
          io.bool := io.a === io.b
          io.bits.dontCare()
      EqEqEq.verilogTest(UIntSpecParameter(8))(
        "assign bool = a == b;"
      )

    test("=/="):
      @generator
      object NotEqual
          extends Generator[UIntSpecParameter, UIntSpecLayers, UIntSpecIO, UIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: UIntSpecParameter) =
          val io = summon[Interface[UIntSpecIO]]
          io.uint.dontCare()
          io.bool := io.a =/= io.b
          io.bits.dontCare()
      NotEqual.verilogTest(UIntSpecParameter(8))(
        "assign bool = a != b;"
      )

    test("<< int"):
      @generator
      object LeftShiftInt
          extends Generator[UIntSpecParameter, UIntSpecLayers, UIntSpecIO, UIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: UIntSpecParameter) =
          val io = summon[Interface[UIntSpecIO]]
          io.uint := (io.a << 2).asBits.bits(parameter.width, 0).asUInt
          io.bool.dontCare()
          io.bits.dontCare()
      LeftShiftInt.verilogTest(UIntSpecParameter(8))(
        "assign uint = {a[6:0], 2'h0};"
      )

    test("<< uint"):
      @generator
      object LeftShiftUInt
          extends Generator[UIntSpecParameter, UIntSpecLayers, UIntSpecIO, UIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: UIntSpecParameter) =
          val io = summon[Interface[UIntSpecIO]]
          io.uint := (io.a << io.b).asBits.bits(parameter.width, 0).asUInt
          io.bool.dontCare()
          io.bits.dontCare()
      LeftShiftUInt.verilogTest(UIntSpecParameter(8))(
        "wire [262:0] _GEN_1 = {255'h0, a} << b;",
        "assign uint = _GEN_1[8:0];"
      )

    test(">> int"):
      @generator
      object RightShiftInt
          extends Generator[UIntSpecParameter, UIntSpecLayers, UIntSpecIO, UIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: UIntSpecParameter) =
          val io      = summon[Interface[UIntSpecIO]]
          val shifted = io.a >> 4
          assert(shifted.width == parameter.width)
          io.uint := shifted
          io.bool.dontCare()
          io.bits.dontCare()
      RightShiftInt.verilogTest(UIntSpecParameter(8))(
        "assign uint = {5'h0, a[7:4]};"
      )

    test(">> uint"):
      @generator
      object RightShiftUInt
          extends Generator[UIntSpecParameter, UIntSpecLayers, UIntSpecIO, UIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: UIntSpecParameter) =
          val io      = summon[Interface[UIntSpecIO]]
          val shifted = io.a >> io.b
          assert(shifted.width == parameter.width)
          io.uint := shifted
          io.bool.dontCare()
          io.bits.dontCare()
      RightShiftUInt.verilogTest(UIntSpecParameter(8))(
        "assign uint = {1'h0, a >> b};"
      )
