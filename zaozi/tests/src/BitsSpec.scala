// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.testlib.*
import org.llvm.mlir.scalalib.capi.ir.{given_ContextApi, Block, Context, ContextApi}

import java.lang.foreign.Arena
import utest.*
import me.jiuyang.zaozi.magic.macros.generator

case class BitsSpecParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[BitsSpecParameter] = upickle.default.macroRW

class BitsSpecLayers(parameter: BitsSpecParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class BitsSpecIO(
  parameter: BitsSpecParameter)
    extends HWBundle(parameter):
  val a          = Flipped(Bits(parameter.width))
  val b          = Flipped(Bits(parameter.width))
  val c          = Flipped(UInt(parameter.width))
  val d          = Flipped(Bool())
  val sint       = Aligned(SInt(parameter.width))
  val uint       = Aligned(UInt(parameter.width))
  val bits       = Aligned(Bits(parameter.width))
  val widenBits  = Aligned(Bits(2 * parameter.width))
  val bool       = Aligned(Bool())
  val clock      = Flipped(Clock())
  val asyncReset = Flipped(AsyncReset())

class BitsSpecProbe(parameter: BitsSpecParameter) extends DVBundle[BitsSpecParameter, BitsSpecLayers](parameter)

class SimpleBundleC extends Bundle:
  val x = Aligned(Bits(4))
  val y = Aligned(Bits(4))

object BitsSpec extends TestSuite:
  val tests = Tests:
    test("AsSInt"):
      @generator
      object AsSInt extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.sint := io.a.asSInt
      AsSInt.verilogTest(BitsSpecParameter(8))(
        "assign sint = a;"
      )

    test("AsUInt"):
      @generator
      object AsUInt extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.uint := io.a.asUInt
      AsUInt.verilogTest(BitsSpecParameter(8))(
        "assign uint = a;"
      )

    test("AsBool"):
      @generator
      object AsBool extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bool := io.a.asBool
      AsBool.verilogTest(BitsSpecParameter(1))(
        "assign bool = a;"
      )

    test("~"):
      @generator
      object Not extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bits := ~io.a
      Not.verilogTest(BitsSpecParameter(8))(
        "assign bits = ~a;"
      )

    test("& (reduce)"):
      @generator
      object AndR extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bool := io.a.andR
      AndR.verilogTest(BitsSpecParameter(8))(
        "assign bool = &a;"
      )

    test("OrR"):
      @generator
      object OrR extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bool := io.a.orR
      OrR.verilogTest(BitsSpecParameter(8))(
        "assign bool = |a;"
      )

    test("XorR"):
      @generator
      object XorR extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bool := io.a.xorR
      XorR.verilogTest(BitsSpecParameter(8))(
        "assign bool = ^a;"
      )

    test("==="):
      @generator
      object Eq extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bool := io.a === io.b
      Eq.verilogTest(BitsSpecParameter(8))(
        "assign bool = a == b;"
      )

    test("=/="):
      @generator
      object Neq extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bool := io.a =/= io.b
      Neq.verilogTest(BitsSpecParameter(8))(
        "assign bool = a != b;"
      )

    test("&"):
      @generator
      object And extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bits := io.a & io.b
      And.verilogTest(BitsSpecParameter(8))(
        "assign bits = a & b;"
      )

    test("|"):
      @generator
      object Or extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bits := io.a | io.b
      Or.verilogTest(BitsSpecParameter(8))(
        "assign bits = a | b;"
      )

    test("^"):
      @generator
      object Xor extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bits := io.a ^ io.b
      Xor.verilogTest(BitsSpecParameter(8))(
        "assign bits = a ^ b;"
      )

    test("Cat"):
      @generator
      object Cat extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.widenBits := io.a ## io.b
      Cat.verilogTest(BitsSpecParameter(8))(
        "assign widenBits = {a, b};"
      )

    test("Mixed Ref and Node Bool operators"):
      @generator
      object MixedRefAndNodeBool
          extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe]
          with HasVerilogTest:
        def andGeneric[R <: Referable[Bool]](
          ref:  R,
          node: Node[Bool]
        )(
          using And[Bool, Bool, R, Referable[Bool]],
          Arena,
          Context,
          Block,
          sourcecode.File,
          sourcecode.Line,
          sourcecode.Name.Machine,
          InstanceContext
        ): Node[Bool] =
          ref & node

        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool := andGeneric(io.d, Node(io.d))
          io.bits.dontCare()
          io.widenBits.dontCare()
      MixedRefAndNodeBool.verilogTest(BitsSpecParameter(8))(
        "assign bool = d;"
      )

    test("Mixed Referable Bits operators"):
      @generator
      object MixedReferableBits
          extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe]
          with HasVerilogTest:
        def catGeneric[R <: Referable[Bits]](
          ref:  R,
          node: Node[Bits]
        )(
          using Cat[Bits, R, Referable[Bits]],
          Arena,
          Context,
          Block,
          sourcecode.File,
          sourcecode.Line,
          sourcecode.Name.Machine,
          InstanceContext
        ): Node[Bits] =
          ref ## node

        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits := catGeneric(io.a, Node(io.b))
      MixedReferableBits.verilogTest(BitsSpecParameter(8))(
        "assign widenBits = {a, b};"
      )

    test("<< int"):
      @generator
      object ShiftLeftInt
          extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          val p  = parameter
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bits := (io.a << 2).bits(p.width - 1, 0)
      ShiftLeftInt.verilogTest(BitsSpecParameter(8))(
        "assign bits = {a[5:0], 2'h0};"
      )

    test("<< uint"):
      @generator
      object ShiftLeftUInt
          extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          val p  = parameter
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bits := (io.a << io.c).bits(p.width - 1, 0)
      ShiftLeftUInt.verilogTest(BitsSpecParameter(8))(
        "wire [262:0] _GEN_0 = {255'h0, a} << c;",
        "assign bits = _GEN_0[7:0];"
      )

    test(">> int"):
      @generator
      object ShiftRightInt
          extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bits := io.a >> 4
      ShiftRightInt.verilogTest(BitsSpecParameter(8))(
        "assign bits = {4'h0, a[7:4]};"
      )

    test(">> uint"):
      @generator
      object ShiftRightUInt
          extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bits := io.a >> io.c
      ShiftRightUInt.verilogTest(BitsSpecParameter(8))(
        "assign bits = a >> c;"
      )

    test("Head"):
      @generator
      object Head extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bits := io.a.head(4) ## 0.B(4)
      Head.verilogTest(BitsSpecParameter(8))(
        "assign bits = {a[7:4], 4'h0};"
      )

    test("Tail"):
      @generator
      object Tail extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bits := io.a.tail(4) ## 0.B(4)
      Tail.verilogTest(BitsSpecParameter(8))(
        "assign bits = {a[3:0], 4'h0};"
      )

    test("Pad"):
      @generator
      object Pad1 extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bits := io.a.tail(4).pad(4)
      Pad1.verilogTest(BitsSpecParameter(8))(
        "assign bits = {4'h0, a[3:0]};"
      )

    test("Pad"):
      @generator
      object Pad2 extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bits := io.a.tail(4).pad(4)
      Pad2.verilogTest(BitsSpecParameter(8))(
        "assign bits = {4'h0, a[3:0]};"
      )

    test("ExtractRange"):
      @generator
      object ExtractRange
          extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bits := io.a.bits(4, 3)
      ExtractRange.verilogTest(BitsSpecParameter(8))(
        "assign bits = {6'h0, a[4:3]};"
      )

    test("ExtractRange apply"):
      @generator
      object ExtractRangeApply
          extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bits := io.a(4, 3)
      ExtractRangeApply.verilogTest(BitsSpecParameter(8))(
        "assign bits = {6'h0, a[4:3]};"
      )

    test("Named ExtractRange apply"):
      @generator
      object NamedExtractRangeApply
          extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bits := io.a(hi = 4, lo = 3)
      NamedExtractRangeApply.verilogTest(BitsSpecParameter(8))(
        "assign bits = {6'h0, a[4:3]};"
      )

    test("ExtractElement"):
      @generator
      object ExtractElement
          extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bool := io.a.bit(4)
      ExtractElement.verilogTest(BitsSpecParameter(8))(
        "assign bool = a[4];"
      )

    test("ExtractElement apply"):
      @generator
      object ExtractElementApply
          extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bool := io.a(4)
      ExtractElementApply.verilogTest(BitsSpecParameter(8))(
        "assign bool = a[4];"
      )

    test("Named ExtractElement apply"):
      @generator
      object NamedExtractElementApply
          extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          io.bool := io.a(idx = 4)
      NamedExtractElementApply.verilogTest(BitsSpecParameter(8))(
        "assign bool = a[4];"
      )
    test("AsBundle"):
      @generator
      object AsBundle
          extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io         = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()
          val bundleNode = io.a.asBundle(new SimpleBundleC)
          io.bits := (bundleNode.y ## bundleNode.x)
      AsBundle.verilogTest(BitsSpecParameter(8))("assign bits = {a[3:0], a[7:4]};")

    test("AsRecord"):
      @generator
      object AsRecord
          extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()

          // Define a simple record to cast to
          class SimpleRecord extends Record:
            val field1 = Aligned("first", Bits(4))
            val field2 = Aligned("second", Bits(4))

          // Cast an 8-bit value to the record
          val simpleRecordType = new SimpleRecord()
          val recordNode       = io.a.asRecord(simpleRecordType)

          // Use the record fields
          io.bits := recordNode.field[Bits]("first") ## recordNode.field[Bits]("second")
      AsRecord.verilogTest(BitsSpecParameter(8))(
        "assign bits = a;"
      )

    test("AsVec"):
      @generator
      object AsVec extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe] with HasVerilogTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          io.sint.dontCare()
          io.uint.dontCare()
          io.bool.dontCare()
          io.bits.dontCare()
          io.widenBits.dontCare()

          class SimpleRecord extends Record:
            val field1 = Aligned("first", Bits(2))
            val field2 = Aligned("second", Bits(2))

          val simpleRecordType = new SimpleRecord()
          val recordNode       = io.a.asVec(simpleRecordType)

          // Use the record fields
          io.bits :=
            recordNode(0).field[Bits]("second") ## recordNode(0).field[Bits]("first") ##
              recordNode(1).field[Bits]("first") ## recordNode(1).field[Bits]("second")
      AsVec.verilogTest(BitsSpecParameter(8))(
        "assign bits = {a[1:0], a[3:2], a[7:4]};"
      )

    test("Apply with wrong data type"):
      @generator
      object ApplyWithWrongDataType
          extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe]
          with HasCompileErrorTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          compileError("""io.bits := io.c(1)""").check(
            "",
            "Element type must be Bits, but got me.jiuyang.zaozi.valuetpe.UInt."
          )
      ApplyWithWrongDataType.compileErrorTest(BitsSpecParameter(8))

    test("Apply with incorrect named argument"):
      @generator
      object ApplyWithIncorrectNamedArgument
          extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe]
          with HasCompileErrorTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          compileError("""io.bits := io.a(invalid_arg = 4)""").check(
            "",
            "Unexpected named arguments invalid_arg."
          )
          compileError("""io.bits := io.a(idx = 1, invalid_arg = 4)""").check(
            "",
            "Unexpected named arguments (idx, invalid_arg)."
          )
      ApplyWithIncorrectNamedArgument.compileErrorTest(BitsSpecParameter(8))

    test("Apply with incorrect number of arguments"):
      @generator
      object ApplyWithIncorrectNumberOfArguments
          extends Generator[BitsSpecParameter, BitsSpecLayers, BitsSpecIO, BitsSpecProbe]
          with HasCompileErrorTest:
        def architecture(parameter: BitsSpecParameter) =
          val io = summon[Interface[BitsSpecIO]]
          compileError("""io.bits := io.b(1, 2, 3)""").check(
            "",
            "Expected 1 or 2 args, but got 3"
          )
      ApplyWithIncorrectNumberOfArguments.compileErrorTest(BitsSpecParameter(8))
