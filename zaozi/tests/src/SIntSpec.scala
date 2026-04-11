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

case class SIntSpecParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[SIntSpecParameter] = upickle.default.macroRW

class SIntSpecLayers(parameter: SIntSpecParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class SIntSpecIO(parameter: SIntSpecParameter) extends HWBundle(parameter):
  val a          = Flipped(SInt(parameter.width))
  val b          = Flipped(SInt(parameter.width))
  val c          = Flipped(UInt(parameter.width))
  val d          = Flipped(Bool())
  val sint       = Aligned(SInt(parameter.width + 1))
  val bits       = Aligned(Bits(parameter.width))
  val bool       = Aligned(Bool())
  val clock      = Flipped(Clock())
  val asyncReset = Flipped(AsyncReset())

class SIntSpecProbe(parameter: SIntSpecParameter) extends DVBundle[SIntSpecParameter, SIntSpecLayers](parameter)

object SIntSpec extends TestSuite:
  val tests = Tests:
    test("asBits"):
      @generator
      object AsBits extends Generator[SIntSpecParameter, SIntSpecLayers, SIntSpecIO, SIntSpecProbe] with HasVerilogTest:
        def architecture(parameter: SIntSpecParameter) =
          val io = summon[Interface[SIntSpecIO]]
          io.sint.dontCare()
          io.bool.dontCare()
          io.bits := io.a.asBits
      AsBits.verilogTest(SIntSpecParameter(8))(
        "assign bits = a;"
      )

    test("+"):
      @generator
      object Plus extends Generator[SIntSpecParameter, SIntSpecLayers, SIntSpecIO, SIntSpecProbe] with HasVerilogTest:
        def architecture(parameter: SIntSpecParameter) =
          val io = summon[Interface[SIntSpecIO]]
          io.sint := io.a + io.b
          io.bool.dontCare()
          io.bits.dontCare()
      Plus.verilogTest(SIntSpecParameter(8))(
        "assign sint = {a[7], a} + {b[7], b};"
      )

    test("Mixed Referable SInt operators"):
      @generator
      object MixedReferableSInt
          extends Generator[SIntSpecParameter, SIntSpecLayers, SIntSpecIO, SIntSpecProbe]
          with HasVerilogTest:
        def subGeneric[R <: Referable[SInt]](
          ref:  R,
          node: Node[SInt]
        )(
          using Sub[SInt, SInt, R, Referable[SInt]],
          Arena,
          Context,
          Block,
          sourcecode.File,
          sourcecode.Line,
          sourcecode.Name.Machine,
          InstanceContext
        ): Node[SInt] =
          ref - node

        def geqGeneric[R <: Referable[SInt]](
          ref:   R,
          const: Const[SInt]
        )(
          using Geq[SInt, Bool, R, Referable[SInt]],
          Arena,
          Context,
          Block,
          sourcecode.File,
          sourcecode.Line,
          sourcecode.Name.Machine,
          InstanceContext
        ): Node[Bool] =
          ref >= const

        def architecture(parameter: SIntSpecParameter) =
          val io = summon[Interface[SIntSpecIO]]
          io.sint := subGeneric(io.a, Node(io.b))
          io.bool := geqGeneric(Node(io.a), 0.S(parameter.width))
          io.bits.dontCare()
      MixedReferableSInt.verilogTest(SIntSpecParameter(8))(
        "assign sint = {a[7], a} - {b[7], b};",
        "assign bool = $signed(a) > -8'sh1;"
      )

    test("-"):
      @generator
      object Minus extends Generator[SIntSpecParameter, SIntSpecLayers, SIntSpecIO, SIntSpecProbe] with HasVerilogTest:
        def architecture(parameter: SIntSpecParameter) =
          val io = summon[Interface[SIntSpecIO]]
          io.sint := io.a - io.b
          io.bool.dontCare()
          io.bits.dontCare()
      Minus.verilogTest(SIntSpecParameter(8))(
        "assign sint = {a[7], a} - {b[7], b};"
      )

    test("*"):
      @generator
      object Times extends Generator[SIntSpecParameter, SIntSpecLayers, SIntSpecIO, SIntSpecProbe] with HasVerilogTest:
        def architecture(parameter: SIntSpecParameter) =
          val io = summon[Interface[SIntSpecIO]]
          io.sint := ((io.a * io.b).asBits >> parameter.width).asSInt
          io.bool.dontCare()
          io.bits.dontCare()
      Times.verilogTest(SIntSpecParameter(8))(
        "wire [15:0] _GEN_0 = {{8{a[7]}}, a} * {{8{b[7]}}, b};"
      )

    test("/"):
      @generator
      object Div extends Generator[SIntSpecParameter, SIntSpecLayers, SIntSpecIO, SIntSpecProbe] with HasVerilogTest:
        def architecture(parameter: SIntSpecParameter) =
          val io = summon[Interface[SIntSpecIO]]
          io.sint := io.a / io.b
          io.bool.dontCare()
          io.bits.dontCare()
      Div.verilogTest(SIntSpecParameter(8))(
        "assign sint = $unsigned($signed($signed({a[7], a}) / $signed({b[7], b})));"
      )

    test("%"):
      @generator
      object Mod extends Generator[SIntSpecParameter, SIntSpecLayers, SIntSpecIO, SIntSpecProbe] with HasVerilogTest:
        def architecture(parameter: SIntSpecParameter) =
          val io = summon[Interface[SIntSpecIO]]
          io.sint := io.a % io.b
          io.bool.dontCare()
          io.bits.dontCare()
      Mod.verilogTest(SIntSpecParameter(8))(
        "wire [7:0] _GEN_0 = $unsigned($signed($signed(a) % $signed(b)));"
      )

    test("<"):
      @generator
      object Less extends Generator[SIntSpecParameter, SIntSpecLayers, SIntSpecIO, SIntSpecProbe] with HasVerilogTest:
        def architecture(parameter: SIntSpecParameter) =
          val io = summon[Interface[SIntSpecIO]]
          io.sint.dontCare()
          io.bool := io.a < io.b
          io.bits.dontCare()
      Less.verilogTest(SIntSpecParameter(8))(
        "assign bool = $signed(a) < $signed(b);"
      )

    test("<="):
      @generator
      object LessEqual
          extends Generator[SIntSpecParameter, SIntSpecLayers, SIntSpecIO, SIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: SIntSpecParameter) =
          val io = summon[Interface[SIntSpecIO]]
          io.sint.dontCare()
          io.bool := io.a <= io.b
          io.bits.dontCare()
      LessEqual.verilogTest(SIntSpecParameter(8))(
        "assign bool = $signed(a) <= $signed(b);"
      )

    test(">"):
      @generator
      object Greater
          extends Generator[SIntSpecParameter, SIntSpecLayers, SIntSpecIO, SIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: SIntSpecParameter) =
          val io = summon[Interface[SIntSpecIO]]
          io.sint.dontCare()
          io.bool := io.a > io.b
          io.bits.dontCare()
      Greater.verilogTest(SIntSpecParameter(8))(
        "assign bool = $signed(a) > $signed(b);"
      )

    test(">="):
      @generator
      object GreaterEqual
          extends Generator[SIntSpecParameter, SIntSpecLayers, SIntSpecIO, SIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: SIntSpecParameter) =
          val io = summon[Interface[SIntSpecIO]]
          io.sint.dontCare()
          io.bool := io.a >= io.b
          io.bits.dontCare()
      GreaterEqual.verilogTest(SIntSpecParameter(8))(
        "assign bool = $signed(a) >= $signed(b);"
      )

    test("==="):
      @generator
      object TripleEqual
          extends Generator[SIntSpecParameter, SIntSpecLayers, SIntSpecIO, SIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: SIntSpecParameter) =
          val io = summon[Interface[SIntSpecIO]]
          io.sint.dontCare()
          io.bool := io.a === io.b
          io.bits.dontCare()
      TripleEqual.verilogTest(SIntSpecParameter(8))(
        "assign bool = a == b;"
      )

    test("=/="):
      @generator
      object NotEqual
          extends Generator[SIntSpecParameter, SIntSpecLayers, SIntSpecIO, SIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: SIntSpecParameter) =
          val io = summon[Interface[SIntSpecIO]]
          io.sint.dontCare()
          io.bool := io.a =/= io.b
          io.bits.dontCare()
      NotEqual.verilogTest(SIntSpecParameter(8))(
        "assign bool = a != b;"
      )

    test("<< int"):
      @generator
      object ShiftLeftInt
          extends Generator[SIntSpecParameter, SIntSpecLayers, SIntSpecIO, SIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: SIntSpecParameter) =
          val io = summon[Interface[SIntSpecIO]]
          io.sint := (io.a << 2).asBits.bits(parameter.width, 0).asSInt
          io.bool.dontCare()
          io.bits.dontCare()
      ShiftLeftInt.verilogTest(SIntSpecParameter(8))(
        "assign sint = {a[6:0], 2'h0};"
      )

    test("<< uint"):
      @generator
      object ShiftLeftUInt
          extends Generator[SIntSpecParameter, SIntSpecLayers, SIntSpecIO, SIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: SIntSpecParameter) =
          val io = summon[Interface[SIntSpecIO]]
          io.sint := (io.a << io.c).asBits.bits(parameter.width, 0).asSInt
          io.bool.dontCare()
          io.bits.dontCare()
      ShiftLeftUInt.verilogTest(SIntSpecParameter(8))(
        "wire [262:0] _GEN_0 = {{255{a[7]}}, a} << c;",
        "assign sint = _GEN_0[8:0];"
      )

    test(">> int"):
      @generator
      object ShiftRightInt
          extends Generator[SIntSpecParameter, SIntSpecLayers, SIntSpecIO, SIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: SIntSpecParameter) =
          val io = summon[Interface[SIntSpecIO]]
          io.sint := io.a >> 4
          io.bool.dontCare()
          io.bits.dontCare()
      ShiftRightInt.verilogTest(SIntSpecParameter(8))(
        "assign sint = {{5{a[7]}}, a[7:4]};"
      )

    test(">> uint"):
      @generator
      object ShiftRightUInt
          extends Generator[SIntSpecParameter, SIntSpecLayers, SIntSpecIO, SIntSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: SIntSpecParameter) =
          val io = summon[Interface[SIntSpecIO]]
          io.sint := io.a >> io.c
          io.bool.dontCare()
          io.bits.dontCare()
      ShiftRightUInt.verilogTest(SIntSpecParameter(8))(
        "wire [7:0] _GEN_0 = $unsigned($signed($signed(a) >>> c));",
        "assign sint = {_GEN_0[7], _GEN_0};"
      )
