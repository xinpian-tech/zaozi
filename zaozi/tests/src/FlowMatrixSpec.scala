// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.Interface
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.testlib.*

import utest.*

case class FMParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[FMParameter] = upickle.default.macroRW

class FMLayers(parameter: FMParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class FMProbe(parameter: FMParameter) extends DVBundle[FMParameter, FMLayers](parameter)

class FMDec(
  width: Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Bundle:
  val ready = Flipped(summon[ConstructorApi].Bool())
  val valid = Aligned(summon[ConstructorApi].Bool())
  val bits  = Aligned(summon[ConstructorApi].UInt(width))

class FMNest(
  width: Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Bundle:
  val fwd = Aligned(new FMDec(width))
  val rev = Flipped(new FMDec(width))

class FMIO(parameter: FMParameter) extends HWBundle(parameter):
  val out = Aligned(new FMDec(parameter.width))
  val in  = Flipped(new FMDec(parameter.width))

class FMNestIO(parameter: FMParameter) extends HWBundle(parameter):
  val n = Aligned(new FMNest(parameter.width))

@generator
object FMChild extends Generator[FMParameter, FMLayers, FMIO, FMProbe]:
  def architecture(parameter: FMParameter) =
    val io = summon[Interface[FMIO]]
    io.out :<>= io.in

object FlowMatrixSpec extends TestSuite:
  private def connects(firrtl: String): Seq[String] =
    firrtl.linesIterator
      .map(_.trim)
      .filter(_.startsWith("connect "))
      .map { l =>
        l.indexOf(" @[") match
          case -1 => l
          case i  => l.take(i)
      }
      .toSeq
      .sorted

  val tests = Tests:

    // ---- module-io, aligned/flipped bundles, legal ----
    test("module-io: :<>= passthrough out<->in lowers"):
      @generator
      object G extends Generator[FMParameter, FMLayers, FMIO, FMProbe] with HasVerilogTest:
        def architecture(parameter: FMParameter) =
          val io = summon[Interface[FMIO]]
          io.out :<>= io.in
      G.verilogString(FMParameter(8))

    test("module-io: ground := writes an output leaf and reads an input leaf, straight on ports"):
      @generator
      object G extends Generator[FMParameter, FMLayers, FMIO, FMProbe] with HasFirrtlTest:
        def architecture(parameter: FMParameter) =
          val io = summon[Interface[FMIO]]
          io.out.valid := io.in.valid
          io.out.bits  := io.in.bits
          io.in.ready  := io.out.ready
      assert(
        connects(G.firrtlString(FMParameter(8))) == Seq(
          "connect in.ready, out.ready",
          "connect out.bits, in.bits",
          "connect out.valid, in.valid"
        ).sorted
      )

    test("module-io: :<= and :>= half connects on oriented bundles lower"):
      @generator
      object G extends Generator[FMParameter, FMLayers, FMIO, FMProbe] with HasVerilogTest:
        def architecture(parameter: FMParameter) =
          val io = summon[Interface[FMIO]]
          io.out :<= io.in
          io.out :>= io.in
      G.verilogString(FMParameter(8))

    // ---- module-io, illegal ----
    test("module-io: := into an input leaf is rejected by the CIRCT pipeline (source flow)"):
      @generator
      object G extends Generator[FMParameter, FMLayers, FMIO, FMProbe] with HasVerilogTest:
        def architecture(parameter: FMParameter) =
          val io = summon[Interface[FMIO]]
          io.in.valid := io.out.valid
      val e = intercept[RuntimeException](G.verilogString(FMParameter(8)))
      assert(e.getMessage.contains("invalid flow"))
      assert(e.getMessage.contains("source flow"))

    test("module-io: :<>= writing an input bundle is rejected by the CIRCT pipeline (source flow)"):
      @generator
      object G extends Generator[FMParameter, FMLayers, FMIO, FMProbe] with HasVerilogTest:
        def architecture(parameter: FMParameter) =
          val io = summon[Interface[FMIO]]
          io.in :<>= io.out
      val e = intercept[RuntimeException](G.verilogString(FMParameter(8)))
      assert(e.getMessage.contains("invalid flow"))

    // ---- nested bundle ----
    test("nested bundle: field-level whole passthrough lowers"):
      @generator
      object G extends Generator[FMParameter, FMLayers, FMNestIO, FMProbe] with HasVerilogTest:
        def architecture(parameter: FMParameter) =
          val io = summon[Interface[FMNestIO]]
          io.n.fwd :<>= io.n.rev
      G.verilogString(FMParameter(8))

    // ---- instance-io: whole-interface forwarding ----
    test("instance-io: io :<>= child.io forwards field-by-field straight to ports"):
      @generator
      object Parent extends Generator[FMParameter, FMLayers, FMIO, FMProbe] with HasFirrtlTest with HasVerilogTest:
        def architecture(parameter: FMParameter) =
          val io    = summon[Interface[FMIO]]
          val child = FMChild.instantiate(parameter)
          io :<>= child.io
      val cs = connects(Parent.firrtlString(FMParameter(8)))
      assert(cs == Seq("connect child.in, in", "connect out, child.out").sorted)
      Parent.verilogString(FMParameter(8))

    test("whole-io: io :<>= wire (Referable source) forwards field-by-field"):
      @generator
      object G extends Generator[FMParameter, FMLayers, FMIO, FMProbe] with HasFirrtlTest:
        def architecture(parameter: FMParameter) =
          val io = summon[Interface[FMIO]]
          val w  = Wire(new FMIO(parameter))
          io :<>= w
      val cs = connects(G.firrtlString(FMParameter(8)))
      assert(cs.contains("connect out, w.out"))
      assert(cs.contains("connect w.in, in"))

    test("instance-io: child.io.in :<>= io.in drives the child input"):
      @generator
      object Parent extends Generator[FMParameter, FMLayers, FMIO, FMProbe] with HasVerilogTest:
        def architecture(parameter: FMParameter) =
          val io    = summon[Interface[FMIO]]
          val child = FMChild.instantiate(parameter)
          io.out :<>= child.io.out
          child.io.in :<>= io.in
      Parent.verilogString(FMParameter(8))

    // ---- instance-io: illegal ----
    test("instance-io: reversed forward inner.io :<>= io is rejected by the CIRCT pipeline"):
      @generator
      object Parent extends Generator[FMParameter, FMLayers, FMIO, FMProbe] with HasVerilogTest:
        def architecture(parameter: FMParameter) =
          val io    = summon[Interface[FMIO]]
          val child = FMChild.instantiate(parameter)
          child.io :<>= io
      val e = intercept[RuntimeException](Parent.verilogString(FMParameter(8)))
      assert(e.getMessage.contains("invalid flow"))
      assert(e.getMessage.contains("source flow"))

    test("instance-io: face-to-face c1.io :<>= c2.io is rejected by the CIRCT pipeline"):
      @generator
      object Parent extends Generator[FMParameter, FMLayers, FMIO, FMProbe] with HasVerilogTest:
        def architecture(parameter: FMParameter) =
          val c1 = FMChild.instantiate(parameter)
          val c2 = FMChild.instantiate(parameter)
          c1.io :<>= c2.io
      val e = intercept[RuntimeException](Parent.verilogString(FMParameter(8)))
      assert(e.getMessage.contains("invalid flow"))
      assert(e.getMessage.contains("source flow"))

    test("instance-io: face-to-face now materialises IR and is caught only by the CIRCT pipeline"):
      @generator
      object Parent extends Generator[FMParameter, FMLayers, FMIO, FMProbe] with HasFirrtlTest with HasVerilogTest:
        def architecture(parameter: FMParameter) =
          val c1 = FMChild.instantiate(parameter)
          val c2 = FMChild.instantiate(parameter)
          c1.io :<>= c2.io
      assert(connects(Parent.firrtlString(FMParameter(8))).nonEmpty)
      val e = intercept[RuntimeException](Parent.verilogString(FMParameter(8)))
      assert(e.getMessage.contains("invalid flow"))

    // ---- whole-io sugar: Writable :<>= Interface ----
    test("whole-io sugar w :<>= io is rejected by the CIRCT pipeline"):
      @generator
      object G extends Generator[FMParameter, FMLayers, FMIO, FMProbe] with HasVerilogTest:
        def architecture(parameter: FMParameter) =
          val io = summon[Interface[FMIO]]
          val w  = Wire(new FMIO(parameter))
          w :<>= io
      val e = intercept[RuntimeException](G.verilogString(FMParameter(8)))
      assert(e.getMessage.contains("invalid flow"))
      assert(e.getMessage.contains("source flow"))

    // ---- io.asBits must not exist on an Interface ----
    test("io.asBits does not compile on an Interface"):
      @generator
      object G extends Generator[FMParameter, FMLayers, FMIO, FMProbe] with HasCompileErrorTest:
        def architecture(parameter: FMParameter) =
          val io = summon[Interface[FMIO]]
          io.out :<>= io.in
          compileError("io.asBits")
      G.compileErrorTest(FMParameter(8))

    // ---- structural error aggregation (whole-bundle bulk connect) ----
    test("bulk connect aggregates every mismatched field into one exception, emitting nothing"):
      @generator
      object G extends Generator[FMParameter, FMLayers, FMIO, FMProbe] with HasFirrtlTest:
        def architecture(parameter: FMParameter) =
          val a = Wire(new FMWide(false, parameter.width))
          val b = Wire(new FMWide(true, parameter.width))
          a :<>= b
      val msg = intercept[ConnectException](G.firrtlString(FMParameter(8))).getMessage
      assert(msg.contains("p: width mismatch"))
      assert(msg.contains("q: width mismatch"))

    test("bulk connect leaves no partial IR on a deep mismatch"):
      @generator
      object G extends Generator[FMParameter, FMLayers, FMIO, FMProbe] with HasFirrtlTest:
        def architecture(parameter: FMParameter) =
          val io = summon[Interface[FMIO]]
          io.out :<>= io.in
          val a  = Wire(new FMWide(false, parameter.width))
          val b  = Wire(new FMWide(true, parameter.width))
          try a :<>= b
          catch case _: ConnectException => ()
      val out = G.firrtlString(FMParameter(8))
      assert(!connects(out).exists(_.startsWith("connect a")))

class FMWide(
  wide:  Boolean,
  width: Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Bundle:
  val p = Aligned(summon[ConstructorApi].UInt(if wide then 16 else width))
  val q = Aligned(summon[ConstructorApi].UInt(if wide then 16 else width))
