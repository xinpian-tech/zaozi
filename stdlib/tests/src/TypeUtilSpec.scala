// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Yuhang Zeng <unlsycn@unlsycn.com>
package me.jiuyang.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.stdlib.default.{*, given}
import me.jiuyang.testlib.*

import java.lang.foreign.Arena
import utest.*

case class TypeUtilParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[TypeUtilParameter] = upickle.default.macroRW

class TypeUtilLayers(parameter: TypeUtilParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class TypeUtilSyncDomain extends Bundle:
  val clock = Aligned(Clock())
  val reset = Aligned(Reset())

class TypeUtilIO(parameter: TypeUtilParameter) extends HWBundle(parameter):
  val syncDomain = Flipped(new TypeUtilSyncDomain)
  val inUInt     = Flipped(UInt(parameter.width))
  val inSInt     = Flipped(SInt(parameter.width))
  val inBits     = Flipped(Bits(parameter.width))
  val inBool     = Flipped(Bool())
  val out        = Aligned(UInt(parameter.width))
  val outSInt    = Aligned(SInt(parameter.width))
  val outBits    = Aligned(Bits(parameter.width))
  val outBool    = Aligned(Bool())

class TypeUtilProbe(parameter: TypeUtilParameter) extends DVBundle[TypeUtilParameter, TypeUtilLayers](parameter)

class SimpleBundle4x2 extends Bundle:
  val x = Aligned(Bits(4))
  val y = Aligned(Bits(4))

object TypeUtilSpec extends TestSuite:
  val tests = Tests:
    test("UInt.asBits"):
      @generator
      object UIntAsBits
          extends Generator[TypeUtilParameter, TypeUtilLayers, TypeUtilIO, TypeUtilProbe]
          with HasVerilogTest:
        def architecture(parameter: TypeUtilParameter) =
          val io = summon[Interface[TypeUtilIO]]
          io.outBits := io.inUInt.asBits
          io.out.dontCare()
          io.outSInt.dontCare()
          io.outBool.dontCare()
      UIntAsBits.verilogTest(TypeUtilParameter(8))("assign outBits")

    test("SInt.asBits"):
      @generator
      object SIntAsBits
          extends Generator[TypeUtilParameter, TypeUtilLayers, TypeUtilIO, TypeUtilProbe]
          with HasVerilogTest:
        def architecture(parameter: TypeUtilParameter) =
          val io = summon[Interface[TypeUtilIO]]
          io.outBits := io.inSInt.asBits
          io.out.dontCare()
          io.outSInt.dontCare()
          io.outBool.dontCare()
      SIntAsBits.verilogTest(TypeUtilParameter(8))("assign outBits")

    test("Bool.asBits"):
      @generator
      object BoolAsBits
          extends Generator[TypeUtilParameter, TypeUtilLayers, TypeUtilIO, TypeUtilProbe]
          with HasVerilogTest:
        def architecture(parameter: TypeUtilParameter) =
          val io = summon[Interface[TypeUtilIO]]
          io.outBits := io.inBool.asBits.pad(parameter.width)
          io.out.dontCare()
          io.outSInt.dontCare()
          io.outBool.dontCare()
      BoolAsBits.verilogTest(TypeUtilParameter(8))("assign outBits")

    test("Bundle.asBits"):
      @generator
      object BundleAsBits
          extends Generator[TypeUtilParameter, TypeUtilLayers, TypeUtilIO, TypeUtilProbe]
          with HasVerilogTest:
        def architecture(parameter: TypeUtilParameter) =
          val io     = summon[Interface[TypeUtilIO]]
          val bundle = Wire(new SimpleBundle4x2)
          bundle.x   := io.inBits.bits(3, 0)
          bundle.y   := io.inBits.bits(7, 4)
          io.outBits := bundle.asBits
          io.out.dontCare()
          io.outSInt.dontCare()
          io.outBool.dontCare()
      BundleAsBits.verilogTest(TypeUtilParameter(8))("assign outBits")

    test("Bits.asType(UInt)"):
      @generator
      object BitsAsTypeOfUInt
          extends Generator[TypeUtilParameter, TypeUtilLayers, TypeUtilIO, TypeUtilProbe]
          with HasVerilogTest:
        def architecture(parameter: TypeUtilParameter) =
          val io = summon[Interface[TypeUtilIO]]
          io.out := io.inBits.asType(UInt(parameter.width))
          io.outSInt.dontCare()
          io.outBits.dontCare()
          io.outBool.dontCare()
      BitsAsTypeOfUInt.verilogTest(TypeUtilParameter(8))("assign out")

    test("Bits.asType(SInt)"):
      @generator
      object BitsAsTypeOfSInt
          extends Generator[TypeUtilParameter, TypeUtilLayers, TypeUtilIO, TypeUtilProbe]
          with HasVerilogTest:
        def architecture(parameter: TypeUtilParameter) =
          val io = summon[Interface[TypeUtilIO]]
          io.outSInt := io.inBits.asType(SInt(parameter.width))
          io.out.dontCare()
          io.outBits.dontCare()
          io.outBool.dontCare()
      BitsAsTypeOfSInt.verilogTest(TypeUtilParameter(8))("assign outSInt")

    test("Bits.asType(Bool)"):
      @generator
      object BitsAsTypeOfBool
          extends Generator[TypeUtilParameter, TypeUtilLayers, TypeUtilIO, TypeUtilProbe]
          with HasVerilogTest:
        def architecture(parameter: TypeUtilParameter) =
          val io     = summon[Interface[TypeUtilIO]]
          val result = io.inBits.bits(0, 0).asType(Bool())
          io.outBool := result
          io.out.dontCare()
          io.outSInt.dontCare()
          io.outBits.dontCare()
      BitsAsTypeOfBool.verilogTest(TypeUtilParameter(8))("assign outBool")

    test("Bits.asType(Bundle)"):
      @generator
      object BitsAsTypeOfBundle
          extends Generator[TypeUtilParameter, TypeUtilLayers, TypeUtilIO, TypeUtilProbe]
          with HasVerilogTest:
        def architecture(parameter: TypeUtilParameter) =
          val io     = summon[Interface[TypeUtilIO]]
          val bundle = io.inBits.asType(new SimpleBundle4x2)
          val wire   = Wire(new SimpleBundle4x2)
          wire.x     := bundle.x
          wire.y     := bundle.y
          io.outBits := wire.asBits
          io.out.dontCare()
          io.outSInt.dontCare()
          io.outBool.dontCare()
      BitsAsTypeOfBundle.verilogTest(TypeUtilParameter(8))("assign outBits")

    test("Bits.asType(ref.getType)"):
      @generator
      object BitsAsTypeFromRef
          extends Generator[TypeUtilParameter, TypeUtilLayers, TypeUtilIO, TypeUtilProbe]
          with HasVerilogTest:
        def architecture(parameter: TypeUtilParameter) =
          val io = summon[Interface[TypeUtilIO]]
          io.out := io.inBits.asType(io.inUInt.getType)
          io.outSInt.dontCare()
          io.outBits.dontCare()
          io.outBool.dontCare()
      BitsAsTypeFromRef.verilogTest(TypeUtilParameter(8))("assign out")

    test("Const[UInt].asBits returns Const[Bits]"):
      @generator
      object ConstAsBits
          extends Generator[TypeUtilParameter, TypeUtilLayers, TypeUtilIO, TypeUtilProbe]
          with HasVerilogTest:
        def architecture(parameter: TypeUtilParameter) =
          val io           = summon[Interface[TypeUtilIO]]
          given Ref[Clock] = io.syncDomain.clock
          given Ref[Reset] = io.syncDomain.reset
          val constBits: Const[Bits] = 0.U(parameter.width).asBits
          val reg = RegInit(constBits)
          io.outBits := reg
          reg        := io.inBits
          io.out.dontCare()
          io.outSInt.dontCare()
          io.outBool.dontCare()
      ConstAsBits.verilogTest(TypeUtilParameter(8))(
        "always @(posedge syncDomain_clock) begin",
        "if (syncDomain_reset)"
      )

    test("Const[Bits].asType(UInt) returns Const[UInt]"):
      @generator
      object ConstAsTypeOfUInt
          extends Generator[TypeUtilParameter, TypeUtilLayers, TypeUtilIO, TypeUtilProbe]
          with HasVerilogTest:
        def architecture(parameter: TypeUtilParameter) =
          val io           = summon[Interface[TypeUtilIO]]
          given Ref[Clock] = io.syncDomain.clock
          given Ref[Reset] = io.syncDomain.reset
          val constUInt: Const[UInt] = 0.B(parameter.width).asType(UInt(parameter.width))
          val reg = RegInit(constUInt)
          io.out := reg
          reg    := io.inUInt
          io.outSInt.dontCare()
          io.outBits.dontCare()
          io.outBool.dontCare()
      ConstAsTypeOfUInt.verilogTest(TypeUtilParameter(8))(
        "always @(posedge syncDomain_clock) begin",
        "if (syncDomain_reset)"
      )

    test("Const[UInt].asBits.asType(SInt) chain"):
      @generator
      object ConstChain
          extends Generator[TypeUtilParameter, TypeUtilLayers, TypeUtilIO, TypeUtilProbe]
          with HasVerilogTest:
        def architecture(parameter: TypeUtilParameter) =
          val io           = summon[Interface[TypeUtilIO]]
          given Ref[Clock] = io.syncDomain.clock
          given Ref[Reset] = io.syncDomain.reset
          val reg          = RegInit(0.U(parameter.width).asBits.asType(SInt(parameter.width)))
          io.outSInt := reg
          reg        := io.inSInt
          io.out.dontCare()
          io.outBits.dontCare()
          io.outBool.dontCare()
      ConstChain.verilogTest(TypeUtilParameter(8))(
        "always @(posedge syncDomain_clock) begin",
        "if (syncDomain_reset)"
      )

    test("Const[Bits].asType(Bundle) returns Const[Bundle]"):
      @generator
      object ConstAsTypeOfBundle
          extends Generator[TypeUtilParameter, TypeUtilLayers, TypeUtilIO, TypeUtilProbe]
          with HasVerilogTest:
        def architecture(parameter: TypeUtilParameter) =
          val io = summon[Interface[TypeUtilIO]]
          val bundleConst: Const[SimpleBundle4x2] = 0.B(8).asType(new SimpleBundle4x2)
          val wire = Wire(new SimpleBundle4x2)
          wire.x     := bundleConst.x
          wire.y     := bundleConst.y
          io.outBits := wire.asBits
          io.out.dontCare()
          io.outSInt.dontCare()
          io.outBool.dontCare()
      ConstAsTypeOfBundle.verilogTest(TypeUtilParameter(8))(
        "assign outBits"
      )
