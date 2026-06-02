// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.Interface
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.testlib.*
import org.llvm.mlir.scalalib.capi.ir.{given_ContextApi, Context, ContextApi}
import utest.*

import java.lang.foreign.Arena
import org.llvm.mlir.scalalib.capi.ir.Block
import me.jiuyang.zaozi.reftpe.Referable

class UnfixedFieldsNumRecord(n: Int, width: Int) extends Record:
  val inputs  = Seq.tabulate(n)(i => Flipped(s"input_$i", UInt(width)))
  val outputs = Seq.tabulate(n)(i => Aligned(s"output_$i", UInt(width)))

class SimpleRecord(width: Int) extends Record:
  val i = Flipped("a", UInt(width))
  val o = Aligned("b", UInt(width))

class AlignedRecord(width: Int) extends Record:
  val i = Aligned("a", UInt(width))
  val o = Aligned("b", UInt(width))

class NestedRecord(width: Int) extends Record:
  val inner = Aligned("inner", new SimpleRecord(width))

case class RecordSpecParameter(fieldNum: Int, width: Int) extends Parameter
given upickle.default.ReadWriter[RecordSpecParameter] = upickle.default.macroRW

class RecordSpecLayers(parameter: RecordSpecParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("verification"))

class DynamicFieldsNumIO(parameter: RecordSpecParameter) extends HWBundle(parameter):
  val a = Aligned(new UnfixedFieldsNumRecord(parameter.fieldNum, parameter.width))

class NestedRecordIO(parameter: RecordSpecParameter) extends HWBundle(parameter):
  val b = Aligned(new NestedRecord(parameter.width))

class SimpleRecordIO(parameter: RecordSpecParameter) extends HWBundle(parameter):
  val c = Aligned(new SimpleRecord(parameter.width))

class RecordSpecProbe(parameter: RecordSpecParameter) extends DVBundle[RecordSpecParameter, RecordSpecLayers](parameter)

class RecordAsIO(parameter: RecordSpecParameter) extends HWRecord(parameter):
  val inputs  = Seq.tabulate(parameter.fieldNum)(i => Flipped(s"input_$i", UInt(parameter.width)))
  val outputs = Seq.tabulate(parameter.fieldNum)(i => Aligned(s"output_$i", UInt(parameter.width)))

class RecordAsProbe(parameter: RecordSpecParameter) extends DVRecord[RecordSpecParameter, RecordSpecLayers](parameter):
  val probes =
    Seq.tabulate(parameter.fieldNum)(i => ProbeRead(s"probe_$i", UInt(parameter.width), layers("verification")))

object RecordSpec extends TestSuite:
  val tests = Tests:
    test("Dynamic fields num"):
      @generator
      object DynamicFieldsNum
          extends Generator[RecordSpecParameter, RecordSpecLayers, DynamicFieldsNumIO, RecordSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: RecordSpecParameter) =
          val io = summon[Interface[DynamicFieldsNumIO]]
          Seq.tabulate(parameter.fieldNum): i =>
            io.a.field(s"output_$i") := io.a.field(s"input_$i")
      DynamicFieldsNum.verilogTest(RecordSpecParameter(2, 32))(
        "assign a_output_0 = a_input_0;",
        "assign a_output_1 = a_input_1;"
      )

    test("Nested Record"):
      @generator
      object NestedRecordTest
          extends Generator[RecordSpecParameter, RecordSpecLayers, NestedRecordIO, RecordSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: RecordSpecParameter) =
          val io = summon[Interface[NestedRecordIO]]
          io.b.field[Record]("inner").field("b") := io.b.field[Record]("inner").field("a")
      NestedRecordTest.verilogTest(RecordSpecParameter(2, 32))(
        "assign b_inner_b = b_inner_a;"
      )
    test("asBits should work"):
      @generator
      object AsBitsShouldWork
          extends Generator[RecordSpecParameter, RecordSpecLayers, RecordAsIO, RecordSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: RecordSpecParameter) =
          val io = summon[Interface[RecordAsIO]]

          io.field("output_0") := (io.field[UInt]("input_0").asBits ## io.field[UInt]("input_1").asBits)
            .asRecord(new AlignedRecord(8))
            .field[UInt]("b")
          io.field("output_1") := (io.field[UInt]("input_0").asBits ## io.field[UInt]("input_1").asBits)
            .asRecord(new AlignedRecord(8))
            .field[UInt]("a")
      AsBitsShouldWork.verilogTest(RecordSpecParameter(2, 8))(
        "assign output_0 = input_1;",
        "assign output_1 = input_0;"
      )
    test("Fields cannot access by val name"):
      @generator
      object AccessValName
          extends Generator[RecordSpecParameter, RecordSpecLayers, SimpleRecordIO, RecordSpecProbe]
          with HasCompileErrorTest:
        def architecture(parameter: RecordSpecParameter) =
          val io = summon[Interface[SimpleRecordIO]]
          intercept[Exception]:
            io.c.field("o") := io.c.field("i")
          .getMessage() ==> "o not found in ArrayBuffer(a, b)"
      AccessValName.compileErrorTest(RecordSpecParameter(2, 32))

    test("Record as Interface"):
      @generator
      object RecordAsInterface
          extends Generator[RecordSpecParameter, RecordSpecLayers, RecordAsIO, RecordAsProbe]
          with HasVerilogTest:
        def architecture(parameter: RecordSpecParameter) =
          val io    = summon[Interface[RecordAsIO]]
          val probe = summon[Interface[RecordAsProbe]]
          Seq.tabulate(parameter.fieldNum): i =>
            io.field(s"output_$i") := io.field(s"input_$i")
            layer("verification"):
              probe.field[RProbe[UInt]](s"probe_$i") <== io.field[UInt](s"input_$i")
      RecordAsInterface.verilogTest(RecordSpecParameter(2, 32))(
        "assign output_0 = input_0;",
        "assign output_1 = input_1;",
        "`define ref_RecordAsInterface_8f428d5_probe_0 input_0",
        "`define ref_RecordAsInterface_8f428d5_probe_1 input_1"
      )

    test("Record.elements returns declared fields in order with correct name, isFlipped and dataType"):
      @generator
      object ElementsIntrospection
          extends Generator[RecordSpecParameter, RecordSpecLayers, SimpleRecordIO, RecordSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: RecordSpecParameter) =
          val io = summon[Interface[SimpleRecordIO]]
          io.c.field("b") := io.c.field("a")

          val widthVal      = parameter.width
          val baseInput     = UInt(widthVal)
          val baseOutput    = UInt(widthVal)
          val n             = parameter.fieldNum
          val freshRecord   = new Record:
            val inputs  = Seq.tabulate(n)(i => Flipped(s"input_$i", baseInput))
            val outputs = Seq.tabulate(n)(i => Aligned(s"output_$i", baseOutput))
          freshRecord.toMlirType
          val freshElements = freshRecord.elements
          assert(freshElements.size == 2 * n)
          Seq.tabulate(n): i =>
            val inField  = freshElements(i)
            val outField = freshElements(n + i)
            assert(inField.name == s"input_$i")
            assert(outField.name == s"output_$i")
            assert(inField.isFlipped == true)
            assert(outField.isFlipped == false)
            assert(inField.dataType eq baseInput)
            assert(outField.dataType eq baseOutput)

          val patternMatched = freshElements.collect:
            case BundleField(n, true, _)  => s"flipped:$n"
            case BundleField(n, false, _) => s"aligned:$n"
          assert(patternMatched.size == 2 * n)
      ElementsIntrospection.verilogTest(RecordSpecParameter(2, 32))(
        "assign c_b = c_a;"
      )

    test("Record.elements before materialization raises"):
      @generator
      object ElementsLifecycle
          extends Generator[RecordSpecParameter, RecordSpecLayers, SimpleRecordIO, RecordSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: RecordSpecParameter) =
          val io = summon[Interface[SimpleRecordIO]]
          io.c.field("b") := io.c.field("a")
          val unmaterialized = new SimpleRecord(parameter.width)
          intercept[IllegalArgumentException](unmaterialized.elements)
      ElementsLifecycle.verilogTest(RecordSpecParameter(2, 32))(
        "assign c_b = c_a;"
      )

    test("ProbeRecord.elements returns RProbe / RWProbe fields"):
      @generator
      object ProbeElementsIntrospection
          extends Generator[RecordSpecParameter, RecordSpecLayers, RecordAsIO, RecordAsProbe]
          with HasVerilogTest:
        def architecture(parameter: RecordSpecParameter) =
          val io    = summon[Interface[RecordAsIO]]
          val probe = summon[Interface[RecordAsProbe]]
          Seq.tabulate(parameter.fieldNum): i =>
            io.field(s"output_$i") := io.field(s"input_$i")
            layer("verification"):
              probe.field[RProbe[UInt]](s"probe_$i") <== io.field[UInt](s"input_$i")

          layer("verification"):
            val verificationLayer: LayerTree = summon[LayerTree]
            val freshProbe = new ProbeRecord:
              val first  = ProbeRead("first", UInt(parameter.width), verificationLayer)
              val second = ProbeReadWrite("second", UInt(parameter.width), verificationLayer)
            freshProbe.toMlirType
            val es         = freshProbe.elements
            assert(es.size == 2)
            assert(es(0).name == "first")
            assert(es(0).isFlipped == false)
            assert(es(0).dataType.isInstanceOf[RProbe[?]])
            assert(es(1).name == "second")
            assert(es(1).dataType.isInstanceOf[RWProbe[?]])
      ProbeElementsIntrospection.verilogTest(RecordSpecParameter(2, 32))(
        "assign output_0 = input_0;"
      )
