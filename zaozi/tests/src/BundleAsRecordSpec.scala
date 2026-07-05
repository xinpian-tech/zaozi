// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.{Const, Interface}
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.testlib.*
import org.llvm.mlir.scalalib.capi.ir.{given_ContextApi, Context, ContextApi}
import utest.*

import java.lang.foreign.Arena
import me.jiuyang.zaozi.magic.macros.generator

case class BARParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[BARParameter] = upickle.default.macroRW

class BARLayers(parameter: BARParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("verification"))

class BARProbe(parameter: BARParameter) extends DVBundle[BARParameter, BARLayers](parameter)

class SimpleIO(parameter: BARParameter) extends HWBundle(parameter):
  val in:  BundleField[UInt] = Flipped(UInt(parameter.width))
  val out: BundleField[UInt] = Aligned(UInt(parameter.width))

class Payload(parameter: BARParameter) extends Bundle:
  val m: BundleField[UInt] = Aligned(UInt(parameter.width))

class NestedIO(parameter: BARParameter) extends HWBundle(parameter):
  val nIn:  BundleField[Payload]   = Flipped(new Payload(parameter))
  val nOut: BundleField[Payload]   = Aligned(new Payload(parameter))
  val vIn:  BundleField[Vec[UInt]] = Flipped(Vec(2, UInt(parameter.width)))
  val vOut: BundleField[Vec[UInt]] = Aligned(Vec(2, UInt(parameter.width)))

class ReflPayload(parameter: BARParameter) extends Bundle:
  val s: BundleField[UInt]      = Aligned(UInt(parameter.width))
  val v: BundleField[Vec[UInt]] = Aligned(Vec(2, UInt(parameter.width)))

class ReflIO(parameter: BARParameter) extends HWBundle(parameter):
  val src: BundleField[ReflPayload] = Flipped(new ReflPayload(parameter))
  val dst: BundleField[ReflPayload] = Aligned(new ReflPayload(parameter))

class DstPayload(parameter: BARParameter) extends Bundle:
  val s: BundleField[UInt] = Aligned(UInt(parameter.width))
  val t: BundleField[UInt] = Aligned(UInt(parameter.width))

class SrcPayload(parameter: BARParameter) extends Bundle:
  val s: BundleField[UInt] = Aligned(UInt(parameter.width))

class MismatchIO(parameter: BARParameter) extends HWBundle(parameter):
  val src: BundleField[SrcPayload] = Flipped(new SrcPayload(parameter))
  val dst: BundleField[DstPayload] = Aligned(new DstPayload(parameter))

class ProbeIO(parameter: BARParameter) extends DVBundle[BARParameter, BARLayers](parameter):
  val p: BundleField[RProbe[UInt]] = ProbeRead(UInt(parameter.width), layers("verification"))

class TwoU4 extends Bundle:
  val x: BundleField[UInt] = Aligned(UInt(4))
  val y: BundleField[UInt] = Aligned(UInt(4))

class TwoU4Record extends Record:
  val x: BundleField[UInt] = Aligned("x", UInt(4))
  val y: BundleField[UInt] = Aligned("y", UInt(4))

class Out4IO(parameter: BARParameter) extends HWBundle(parameter):
  val out: BundleField[UInt] = Aligned(UInt(4))

object BundleAsRecordSpec extends TestSuite:
  val tests = Tests:

    test("asRecord field connect equals typed access, and elements enumerates"):
      @generator
      object FieldConnect extends Generator[BARParameter, BARLayers, SimpleIO, BARProbe] with HasVerilogTest:
        def architecture(parameter: BARParameter) =
          val io = summon[Interface[SimpleIO]]
          io.asRecord.field("out") := io.asRecord.field("in")

          val es = io.asRecord.getType.elements
          assert(es.size == 2)
          assert(es(0).name == "in")
          assert(es(1).name == "out")
          assert(es(0).isFlipped == true)
          assert(es(1).isFlipped == false)
          assert(es(0).dataType.isInstanceOf[UInt])

          intercept[Exception](io.asRecord.field("absent"))
      FieldConnect.verilogTest(BARParameter(8))(
        "assign out = in;"
      )

    test("Bundle type exposes elements directly (type-level introspection)"):
      @generator
      object DirectElems extends Generator[BARParameter, BARLayers, SimpleIO, BARProbe] with HasVerilogTest:
        def architecture(parameter: BARParameter) =
          val io = summon[Interface[SimpleIO]]
          val es = io.getType.elements
          assert(es.map(_.name) == Seq("in", "out"))
          assert(es(0).isFlipped == true)
          assert(es(1).isFlipped == false)
          io.out := io.in
      DirectElems.verilogTest(BARParameter(8))("assign out = in;")

    test("asRecord field connect renders identical Verilog to typed access"):
      @generator
      object EqTyped extends Generator[BARParameter, BARLayers, SimpleIO, BARProbe] with HasVerilogTest:
        override def moduleName(parameter: BARParameter): String = s"AsRecordEq_${parameter.width}"
        def architecture(parameter: BARParameter) =
          val io = summon[Interface[SimpleIO]]
          io.out := io.in
      @generator
      object EqView  extends Generator[BARParameter, BARLayers, SimpleIO, BARProbe] with HasVerilogTest:
        override def moduleName(parameter: BARParameter): String = s"AsRecordEq_${parameter.width}"
        def architecture(parameter: BARParameter) =
          val io = summon[Interface[SimpleIO]]
          io.asRecord.field("out") := io.asRecord.field("in")
      assert(EqView.verilogString(BARParameter(8)) == EqTyped.verilogString(BARParameter(8)))

    test("asRecord works on a Wire receiver"):
      @generator
      object WireView extends Generator[BARParameter, BARLayers, SimpleIO, BARProbe] with HasVerilogTest:
        def architecture(parameter: BARParameter) =
          val io = summon[Interface[SimpleIO]]
          val w  = Wire(new SimpleIO(parameter))
          w.asRecord.field("in")   := io.asRecord.field("in")
          io.asRecord.field("out") := w.asRecord.field("out")
          w.asRecord.field("out")  := w.asRecord.field("in")
      WireView.verilogTest(BARParameter(8))(out => out.contains("= in"))

    test("asRecord works on an instance.io receiver"):
      @generator
      object InstChild  extends Generator[BARParameter, BARLayers, SimpleIO, BARProbe]:
        def architecture(parameter: BARParameter) =
          val io = summon[Interface[SimpleIO]]
          io.asRecord.field("out") := io.asRecord.field("in")
      @generator
      object InstParent extends Generator[BARParameter, BARLayers, SimpleIO, BARProbe] with HasVerilogTest:
        def architecture(parameter: BARParameter) =
          val io    = summon[Interface[SimpleIO]]
          val child = InstChild.instantiate(parameter)
          child.io.asRecord.field("in") := io.asRecord.field("in")
          io.asRecord.field("out")      := child.io.asRecord.field("out")
      InstParent.verilogTest(BARParameter(8))(out => out.contains("InstChild"))

    test("nested bundle and nested vec descend via field[T]"):
      @generator
      object NestedDescent extends Generator[BARParameter, BARLayers, NestedIO, BARProbe] with HasVerilogTest:
        def architecture(parameter: BARParameter) =
          val io = summon[Interface[NestedIO]]
          io.asRecord.field[Payload]("nOut").asRecord.field("m") :=
            io.asRecord.field[Payload]("nIn").asRecord.field("m")
          val dv = io.asRecord.field[Vec[UInt]]("vOut")
          val sv = io.asRecord.field[Vec[UInt]]("vIn")
          (0 until dv.length).foreach: i =>
            dv(i) := sv(i)
      NestedDescent.verilogTest(BARParameter(8))(
        "assign nOut_m = nIn_m;",
        "assign vOut_0 = vIn_0;",
        "assign vOut_1 = vIn_1;"
      )

    test("probe view connects via <== and enumerates probe fields"):
      @generator
      object ProbeView extends Generator[BARParameter, BARLayers, SimpleIO, ProbeIO] with HasVerilogTest:
        def architecture(parameter: BARParameter) =
          val io    = summon[Interface[SimpleIO]]
          val probe = summon[Interface[ProbeIO]]
          io.asRecord.field("out") := io.asRecord.field("in")

          val es = probe.asRecord.getType.elements
          assert(es.size == 1)
          assert(es(0).name == "p")
          assert(es(0).dataType.isInstanceOf[RProbe[?]])

          layer("verification"):
            probe.asRecord.field[RProbe[UInt]]("p") <== io.asRecord.field[UInt]("in")
      ProbeView.verilogTest(BARParameter(8))(
        "assign out = in;"
      )

    test("reflective field-by-field connect mirrors a bundle"):
      @generator
      object Reflective extends Generator[BARParameter, BARLayers, ReflIO, BARProbe] with HasVerilogTest:
        def architecture(parameter: BARParameter) =
          val io   = summon[Interface[ReflIO]]
          val dstV = io.asRecord.field[ReflPayload]("dst").asRecord
          val srcV = io.asRecord.field[ReflPayload]("src").asRecord
          dstV.getType.elements.foreach: f =>
            f.dataType match
              case _: Vec[?] =>
                val d = dstV.field[Vec[Element]](f.name)
                val s = srcV.field[Vec[Element]](f.name)
                (0 until d.length).foreach: i =>
                  d(i) := s(i)
              case _ =>
                dstV.field(f.name) := srcV.field(f.name)
      Reflective.verilogTest(BARParameter(8))(
        "assign dst_s = src_s;",
        "assign dst_v_0 = src_v_0;",
        "assign dst_v_1 = src_v_1;"
      )

    test("reflective connect renders identical Verilog to a hand-written connect"):
      @generator
      object ReflHand extends Generator[BARParameter, BARLayers, ReflIO, BARProbe] with HasVerilogTest:
        override def moduleName(parameter: BARParameter): String = s"ReflEq_${parameter.width}"
        def architecture(parameter: BARParameter) =
          val io = summon[Interface[ReflIO]]
          io.dst.s    := io.src.s
          io.dst.v(0) := io.src.v(0)
          io.dst.v(1) := io.src.v(1)
      @generator
      object ReflView extends Generator[BARParameter, BARLayers, ReflIO, BARProbe] with HasVerilogTest:
        override def moduleName(parameter: BARParameter): String = s"ReflEq_${parameter.width}"
        def architecture(parameter: BARParameter) =
          val io   = summon[Interface[ReflIO]]
          val dstV = io.asRecord.field[ReflPayload]("dst").asRecord
          val srcV = io.asRecord.field[ReflPayload]("src").asRecord
          dstV.getType.elements.foreach: f =>
            f.dataType match
              case _: Vec[?] =>
                val d = dstV.field[Vec[Element]](f.name)
                val s = srcV.field[Vec[Element]](f.name)
                (0 until d.length).foreach: i =>
                  d(i) := s(i)
              case _ =>
                dstV.field(f.name) := srcV.field(f.name)
      assert(ReflView.verilogString(BARParameter(8)) == ReflHand.verilogString(BARParameter(8)))

    test("reflective connect with a missing source field fails at elaboration"):
      @generator
      object ReflMismatch extends Generator[BARParameter, BARLayers, MismatchIO, BARProbe] with HasVerilogTest:
        def architecture(parameter: BARParameter) =
          val io   = summon[Interface[MismatchIO]]
          val dstV = io.asRecord.field[DstPayload]("dst").asRecord
          val srcV = io.asRecord.field[SrcPayload]("src").asRecord
          dstV.getType.elements.foreach: f =>
            dstV.field(f.name) := srcV.field(f.name)
      // `verilogString`, not `verilogTest`: utest's `assert` macro would re-wrap the throw as a `utest.AssertionError`.
      intercept[Exception](ReflMismatch.verilogString(BARParameter(8)))

    test("existing Bits.asRecord(tpe) overload still works"):
      @generator
      object BitsAsRecordStillWorks extends Generator[BARParameter, BARLayers, Out4IO, BARProbe] with HasVerilogTest:
        def architecture(parameter: BARParameter) =
          val io = summon[Interface[Out4IO]]
          io.out := 0.B(8).asRecord(new TwoU4Record).field[UInt]("x")
      BitsAsRecordStillWorks.verilogTest(BARParameter(8))(
        "assign out ="
      )

    test("asRecord on a Const base yields Const[Record]"):
      @generator
      object ConstView extends Generator[BARParameter, BARLayers, Out4IO, BARProbe] with HasVerilogTest:
        def architecture(parameter: BARParameter) =
          val io = summon[Interface[Out4IO]]
          val c: Const[Record] = (0.B(8).asBundle(new TwoU4)).asRecord
          io.out := c.field[UInt]("x")
      ConstView.verilogTest(BARParameter(8))(
        "assign out ="
      )
