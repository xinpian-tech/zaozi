// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.testlib.*
import org.llvm.mlir.scalalib.capi.ir.{given_ContextApi, Context, ContextApi}

import java.lang.foreign.Arena
import utest.*
import me.jiuyang.zaozi.magic.macros.generator

case class ConstPropParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[ConstPropParameter] = upickle.default.macroRW

class ConstPropLayers(parameter: ConstPropParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ConstPropIO(parameter: ConstPropParameter) extends HWBundle(parameter):
  val syncDomain = Flipped(new SyncDomain)
  val inBits     = Flipped(Bits(parameter.width))
  val inSInt     = Flipped(SInt(parameter.width))
  val inUInt     = Flipped(UInt(parameter.width))
  val inBool     = Flipped(Bool())
  val out        = Aligned(UInt(parameter.width))
  val outSInt    = Aligned(SInt(parameter.width))
  val outBits    = Aligned(Bits(parameter.width))
  val outBool    = Aligned(Bool())

class ConstPropProbe(parameter: ConstPropParameter) extends DVBundle[ConstPropParameter, ConstPropLayers](parameter)

class SimpleBundle8 extends Bundle:
  val x = Aligned(Bits(4))
  val y = Aligned(Bits(4))

object ConstPropSpec extends TestSuite:
  val tests = Tests:
    test("Const[UInt].asBits returns Const[Bits]"):
      @generator
      object UIntAsBitsConst
          extends Generator[ConstPropParameter, ConstPropLayers, ConstPropIO, ConstPropProbe]
          with HasVerilogTest:
        def architecture(parameter: ConstPropParameter) =
          val io           = summon[Interface[ConstPropIO]]
          given ClockScope = ClockScope.posedge(io.syncDomain.clock)
          given ResetScope = ResetScope.syncActiveHigh(io.syncDomain.reset)
          val reg          = RegInit(0.U(parameter.width).asBits)
          io.outBits := reg
          reg        := io.inBits
          io.out.dontCare()
          io.outSInt.dontCare()
          io.outBool.dontCare()
      UIntAsBitsConst.verilogTest(ConstPropParameter(8))(
        "always @(posedge syncDomain_clock) begin",
        "if (syncDomain_reset)"
      )

    test("Const[Bits].asUInt returns Const[UInt]"):
      @generator
      object BitsAsUIntConst
          extends Generator[ConstPropParameter, ConstPropLayers, ConstPropIO, ConstPropProbe]
          with HasVerilogTest:
        def architecture(parameter: ConstPropParameter) =
          val io           = summon[Interface[ConstPropIO]]
          given ClockScope = ClockScope.posedge(io.syncDomain.clock)
          given ResetScope = ResetScope.syncActiveHigh(io.syncDomain.reset)
          val reg          = RegInit(0.B(parameter.width).asUInt)
          io.out := reg
          reg    := io.inUInt
          io.outSInt.dontCare()
          io.outBits.dontCare()
          io.outBool.dontCare()
      BitsAsUIntConst.verilogTest(ConstPropParameter(8))(
        "always @(posedge syncDomain_clock) begin",
        "if (syncDomain_reset)"
      )

    test("Const[Bits].asSInt returns Const[SInt]"):
      @generator
      object BitsAsSIntConst
          extends Generator[ConstPropParameter, ConstPropLayers, ConstPropIO, ConstPropProbe]
          with HasVerilogTest:
        def architecture(parameter: ConstPropParameter) =
          val io           = summon[Interface[ConstPropIO]]
          given ClockScope = ClockScope.posedge(io.syncDomain.clock)
          given ResetScope = ResetScope.syncActiveHigh(io.syncDomain.reset)
          val reg          = RegInit(0.B(parameter.width).asSInt)
          io.outSInt := reg
          reg        := io.inSInt
          io.out.dontCare()
          io.outBits.dontCare()
          io.outBool.dontCare()
      BitsAsSIntConst.verilogTest(ConstPropParameter(8))(
        "always @(posedge syncDomain_clock) begin",
        "if (syncDomain_reset)"
      )

    test("Const[Bits].asBool returns Const[Bool]"):
      @generator
      object BitsAsBoolConst
          extends Generator[ConstPropParameter, ConstPropLayers, ConstPropIO, ConstPropProbe]
          with HasVerilogTest:
        def architecture(parameter: ConstPropParameter) =
          val io           = summon[Interface[ConstPropIO]]
          given ClockScope = ClockScope.posedge(io.syncDomain.clock)
          given ResetScope = ResetScope.syncActiveHigh(io.syncDomain.reset)
          val reg          = RegInit(false.B.asBits.asBool)
          io.outBool := reg
          reg        := io.inBool
          io.out.dontCare()
          io.outSInt.dontCare()
          io.outBits.dontCare()
      BitsAsBoolConst.verilogTest(ConstPropParameter(8))(
        "always @(posedge syncDomain_clock) begin",
        "if (syncDomain_reset)"
      )

    test("Const[SInt].asBits returns Const[Bits]"):
      @generator
      object SIntAsBitsConst
          extends Generator[ConstPropParameter, ConstPropLayers, ConstPropIO, ConstPropProbe]
          with HasVerilogTest:
        def architecture(parameter: ConstPropParameter) =
          val io           = summon[Interface[ConstPropIO]]
          given ClockScope = ClockScope.posedge(io.syncDomain.clock)
          given ResetScope = ResetScope.syncActiveHigh(io.syncDomain.reset)
          val reg          = RegInit(0.S(parameter.width).asBits)
          io.outBits := reg
          reg        := io.inBits
          io.out.dontCare()
          io.outSInt.dontCare()
          io.outBool.dontCare()
      SIntAsBitsConst.verilogTest(ConstPropParameter(8))(
        "always @(posedge syncDomain_clock) begin",
        "if (syncDomain_reset)"
      )

    test("Const[Bool].asBits returns Const[Bits]"):
      @generator
      object BoolAsBitsConst
          extends Generator[ConstPropParameter, ConstPropLayers, ConstPropIO, ConstPropProbe]
          with HasVerilogTest:
        def architecture(parameter: ConstPropParameter) =
          val io           = summon[Interface[ConstPropIO]]
          given ClockScope = ClockScope.posedge(io.syncDomain.clock)
          given ResetScope = ResetScope.syncActiveHigh(io.syncDomain.reset)
          val reg          = RegInit(true.B.asBits.asBool)
          io.outBool := reg
          reg        := io.inBool
          io.out.dontCare()
          io.outSInt.dontCare()
          io.outBits.dontCare()
      BoolAsBitsConst.verilogTest(ConstPropParameter(8))(
        "always @(posedge syncDomain_clock) begin",
        "if (syncDomain_reset)"
      )

    test("Const[Bits].asBundle returns Const[Bundle]"):
      @generator
      object BitsAsBundleConst
          extends Generator[ConstPropParameter, ConstPropLayers, ConstPropIO, ConstPropProbe]
          with HasVerilogTest:
        def architecture(parameter: ConstPropParameter) =
          val io = summon[Interface[ConstPropIO]]
          val bundleConst: Const[SimpleBundle8] = 0.B(8).asBundle(new SimpleBundle8)
          val wire = Wire(new SimpleBundle8)
          wire.x     := bundleConst.x
          wire.y     := bundleConst.y
          io.outBits := wire.asBits
          io.out.dontCare()
          io.outSInt.dontCare()
          io.outBool.dontCare()
      BitsAsBundleConst.verilogTest(ConstPropParameter(8))(
        "assign outBits"
      )

    test("Const[Bits].asVec returns Const[Vec]"):
      @generator
      object BitsAsVecConst
          extends Generator[ConstPropParameter, ConstPropLayers, ConstPropIO, ConstPropProbe]
          with HasVerilogTest:
        def architecture(parameter: ConstPropParameter) =
          val io = summon[Interface[ConstPropIO]]
          val vecConst: Const[Vec[Bits]] = 0.B(8).asVec(Bits(2))
          val wire = Wire(Vec(4, Bits(2)))
          wire(0)    := vecConst(0)
          wire(1)    := vecConst(1)
          wire(2)    := vecConst(2)
          wire(3)    := vecConst(3)
          io.outBits := wire.asBits
          io.out.dontCare()
          io.outSInt.dontCare()
          io.outBool.dontCare()
      BitsAsVecConst.verilogTest(ConstPropParameter(8))(
        "assign outBits"
      )

    test("Chained const propagation: Const[UInt].asBits.asSInt"):
      @generator
      object ChainedConstProp
          extends Generator[ConstPropParameter, ConstPropLayers, ConstPropIO, ConstPropProbe]
          with HasVerilogTest:
        def architecture(parameter: ConstPropParameter) =
          val io           = summon[Interface[ConstPropIO]]
          given ClockScope = ClockScope.posedge(io.syncDomain.clock)
          given ResetScope = ResetScope.syncActiveHigh(io.syncDomain.reset)
          val reg          = RegInit(0.U(parameter.width).asBits.asSInt)
          io.outSInt := reg
          reg        := io.inSInt
          io.out.dontCare()
          io.outBits.dontCare()
          io.outBool.dontCare()
      ChainedConstProp.verilogTest(ConstPropParameter(8))(
        "always @(posedge syncDomain_clock) begin",
        "if (syncDomain_reset)"
      )

    test("Non-const does not propagate"):
      @generator
      object NonConstNoProp
          extends Generator[ConstPropParameter, ConstPropLayers, ConstPropIO, ConstPropProbe]
          with HasCompileErrorTest:
        def architecture(parameter: ConstPropParameter) =
          val io = summon[Interface[ConstPropIO]]
          compileError("""
            given ClockScope = ClockScope.posedge(io.syncDomain.clock)
            given ResetScope = ResetScope.syncActiveHigh(io.syncDomain.reset)
            val wire = Wire(UInt(parameter.width))
            val reg = RegInit(wire.asBits)
          """).check(
            "",
            "Found:    me.jiuyang.zaozi.reftpe.Node[me.jiuyang.zaozi.valuetpe.Bits]",
            "Required: me.jiuyang.zaozi.reftpe.Const[T]"
          )
      NonConstNoProp.compileErrorTest(ConstPropParameter(8))
