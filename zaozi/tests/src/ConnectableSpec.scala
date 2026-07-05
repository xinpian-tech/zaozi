// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.Interface
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.testlib.*

import java.lang.foreign.Arena
import utest.*

case class ConnParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[ConnParameter] = upickle.default.macroRW

class ConnLayers(parameter: ConnParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ConnProbe(parameter: ConnParameter) extends DVBundle[ConnParameter, ConnLayers](parameter)

class EmptyIO(parameter: ConnParameter) extends HWBundle(parameter)

class Dec(
  width: Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Bundle:
  val ready = Flipped(summon[ConstructorApi].Bool())
  val valid = Aligned(summon[ConstructorApi].Bool())
  val bits  = Aligned(summon[ConstructorApi].UInt(width))

class DecIO(parameter: ConnParameter) extends HWBundle(parameter):
  val a = Flipped(new Dec(parameter.width))
  val b = Aligned(new Dec(parameter.width))

class Scalars(
  width: Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Bundle:
  val u  = Aligned(summon[ConstructorApi].UInt(width))
  val s  = Aligned(summon[ConstructorApi].SInt(width))
  val bl = Aligned(summon[ConstructorApi].Bool())
  val ck = Aligned(summon[ConstructorApi].Clock())
  val rs = Aligned(summon[ConstructorApi].Reset())
  val bt = Aligned(summon[ConstructorApi].Bits(width))
  val fl = Flipped(summon[ConstructorApi].Bool())

class HandshakeRec(
  width: Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Record:
  val valid = Aligned("valid", summon[ConstructorApi].Bool())
  val ready = Flipped("ready", summon[ConstructorApi].Bool())
  val bits  = Aligned("bits", summon[ConstructorApi].UInt(width))

class TwoU(
  width: Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Bundle:
  val a = Aligned(summon[ConstructorApi].UInt(width))
  val b = Aligned(summon[ConstructorApi].UInt(width))

class TwoURec(
  width: Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Record:
  val a = Aligned("a", summon[ConstructorApi].UInt(width))
  val b = Aligned("b", summon[ConstructorApi].UInt(width))

class OneFlip(
  width: Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Bundle:
  val x = Flipped(summon[ConstructorApi].UInt(width))

class OneFlipRec(
  width: Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Record:
  val x = Flipped("x", summon[ConstructorApi].UInt(width))

class EmptyRec extends Record

class FlipDecBundle(
  width: Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Bundle:
  val d = Flipped(new Dec(width))

class Leaf(
  width: Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Bundle:
  val x = Aligned(summon[ConstructorApi].UInt(width))
  val y = Flipped(summon[ConstructorApi].UInt(width))

class LeafRec(
  width: Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Record:
  val p = Aligned("p", summon[ConstructorApi].UInt(width))
  val q = Flipped("q", summon[ConstructorApi].UInt(width))

class MixedNest(
  width: Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Bundle:
  val nb = Aligned(new Leaf(width))
  val nr = Flipped(new LeafRec(width))
  val vs = Aligned(Vec(2, summon[ConstructorApi].UInt(width)))
  val vb = Aligned(Vec(2, new Leaf(width)))
  val vr = Flipped(Vec(2, new LeafRec(width)))
  val vv = Aligned(Vec(2, Vec(2, summon[ConstructorApi].UInt(width))))
  val d  = Flipped(new Dec(width))

class PartialRec(
  wB: Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Record:
  val a = Aligned("a", summon[ConstructorApi].UInt(8))
  val b = Aligned("b", summon[ConstructorApi].UInt(wB))

class OrderRec(
  swapped: Boolean,
  width:   Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Record:
  if swapped then
    Aligned("b", summon[ConstructorApi].UInt(width))
    Aligned("a", summon[ConstructorApi].UInt(width))
  else
    Aligned("a", summon[ConstructorApi].UInt(width))
    Aligned("b", summon[ConstructorApi].UInt(width))

class MultiBadRec(
  producer: Boolean,
  width:    Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Record:
  Aligned("w", summon[ConstructorApi].UInt(if producer then 16 else 8))
  if producer then Aligned("k", summon[ConstructorApi].SInt(width))
  else Aligned("k", summon[ConstructorApi].UInt(width))
  if producer then Flipped("o", summon[ConstructorApi].Bool())
  else Aligned("o", summon[ConstructorApi].Bool())
  Aligned("vl", Vec(if producer then 3 else 2, summon[ConstructorApi].UInt(width)))
  Aligned("ve", Vec(2, new Leaf(if producer then 16 else 8)))
  if producer then Aligned("sh", Vec(2, summon[ConstructorApi].UInt(width)))
  else Aligned("sh", summon[ConstructorApi].UInt(width))
  if producer then Aligned("rst", summon[ConstructorApi].AsyncReset())
  else Aligned("rst", summon[ConstructorApi].Reset())
  if producer then Aligned("b1", summon[ConstructorApi].UInt(1))
  else Aligned("b1", summon[ConstructorApi].Bool())
  Aligned("ord", new OrderRec(producer, width))
  if producer then Aligned("onlyB", summon[ConstructorApi].Bool())
  else Aligned("onlyA", summon[ConstructorApi].Bool())

object ConnectableSpec extends TestSuite:
  // Preserve multiplicity and strip source locations before comparing connect lines.
  private def connects(firrtl: String): Seq[String] =
    firrtl.linesIterator
      .map(_.trim)
      .filter(_.startsWith("connect "))
      .map(l =>
        l.indexOf(" @[") match
          case -1 => l
          case i  => l.take(i)
      )
      .toSeq
      .sorted

  val tests = Tests:

    // Guards the analyze/toMlirType invariant that keeps :<>= on the one-op fast path.
    test("bidirectional always rides the fast path"):
      @generator
      object G extends Generator[ConnParameter, ConnLayers, EmptyIO, ConnProbe] with HasFirrtlTest:
        def architecture(parameter: ConnParameter) =
          val w  = parameter.width
          val a1 = Wire(new Dec(w))
          val b1 = Wire(new Dec(w))
          a1 :<>= b1
          val a2 = Wire(new Scalars(w))
          val b2 = Wire(new Scalars(w))
          a2 :<>= b2
          val a3 = Wire(new HandshakeRec(w))
          val b3 = Wire(new HandshakeRec(w))
          a3 :<>= b3
          val a4 = Wire(new MixedNest(w))
          val b4 = Wire(new MixedNest(w))
          a4 :<>= b4
          val a5 = Wire(new FlipDecBundle(w))
          val b5 = Wire(new FlipDecBundle(w))
          a5 :<>= b5
          val a6 = Wire(Vec(2, new Dec(w)))
          val b6 = Wire(Vec(2, new Dec(w)))
          a6 :<>= b6
          val a7 = Wire(new EmptyRec)
          val b7 = Wire(new EmptyRec)
          a7 :<>= b7
          val a8 = Wire(Vec(2, UInt(w)))
          val b8 = Wire(Vec(2, UInt(w)))
          a8 :<>= b8
      assert(
        connects(G.firrtlString(ConnParameter(8))) ==
          (1 to 8).map(i => s"connect a$i, b$i").sorted
      )

    test("bidirectional on ports lowers identically to hand written"):
      @generator
      object Bi     extends Generator[ConnParameter, ConnLayers, DecIO, ConnProbe] with HasFirrtlTest with HasVerilogTest:
        override def moduleName(parameter: ConnParameter): String = s"ConnEq_${parameter.width}"
        def architecture(parameter: ConnParameter) =
          val io = summon[Interface[DecIO]]
          io.b :<>= io.a
      @generator
      object Manual extends Generator[ConnParameter, ConnLayers, DecIO, ConnProbe] with HasVerilogTest:
        override def moduleName(parameter: ConnParameter): String = s"ConnEq_${parameter.width}"
        def architecture(parameter: ConnParameter) =
          val io = summon[Interface[DecIO]]
          io.a.ready := io.b.ready
          io.b.valid := io.a.valid
          io.b.bits  := io.a.bits
      val p = ConnParameter(8)
      val cs = connects(Bi.firrtlString(p))
      assert(cs.contains("connect io.b, io.a"))
      assert(!cs.exists(_.startsWith("connect io.b.")))
      assert(Bi.verilogString(p) == Manual.verilogString(p))

    test("halves emit exactly the selected passive subtrees"):
      @generator
      object Al extends Generator[ConnParameter, ConnLayers, EmptyIO, ConnProbe] with HasFirrtlTest:
        def architecture(parameter: ConnParameter) =
          val m1 = Wire(new MixedNest(parameter.width))
          val m2 = Wire(new MixedNest(parameter.width))
          m1 :<= m2
          val s1 = Wire(new Scalars(parameter.width))
          val s2 = Wire(new Scalars(parameter.width))
          s1 :<= s2
          val e1 = Wire(new EmptyRec)
          val e2 = Wire(new EmptyRec)
          e1 :<= e2
          val z1 = Wire(Vec(0, UInt(parameter.width)))
          val z2 = Wire(Vec(0, UInt(parameter.width)))
          z1 :<= z2
      @generator
      object Fl extends Generator[ConnParameter, ConnLayers, EmptyIO, ConnProbe] with HasFirrtlTest:
        def architecture(parameter: ConnParameter) =
          val m1 = Wire(new MixedNest(parameter.width))
          val m2 = Wire(new MixedNest(parameter.width))
          m1 :>= m2
          val s1 = Wire(new Scalars(parameter.width))
          val s2 = Wire(new Scalars(parameter.width))
          s1 :>= s2
          val e1 = Wire(new EmptyRec)
          val e2 = Wire(new EmptyRec)
          e1 :>= e2
          val z1 = Wire(Vec(0, UInt(parameter.width)))
          val z2 = Wire(Vec(0, UInt(parameter.width)))
          z1 :>= z2
      val p = ConnParameter(8)
      assert(
        connects(Al.firrtlString(p)) == Seq(
          "connect m1.nb.x, m2.nb.x",
          "connect m1.nr.q, m2.nr.q",
          "connect m1.vs, m2.vs",
          "connect m1.vv, m2.vv",
          "connect m1.vb[0].x, m2.vb[0].x",
          "connect m1.vb[1].x, m2.vb[1].x",
          "connect m1.vr[0].q, m2.vr[0].q",
          "connect m1.vr[1].q, m2.vr[1].q",
          "connect m1.d.ready, m2.d.ready",
          "connect s1.u, s2.u",
          "connect s1.s, s2.s",
          "connect s1.bl, s2.bl",
          "connect s1.ck, s2.ck",
          "connect s1.rs, s2.rs",
          "connect s1.bt, s2.bt"
        ).sorted
      )
      assert(
        connects(Fl.firrtlString(p)) == Seq(
          "connect m2.nb.y, m1.nb.y",
          "connect m2.nr.p, m1.nr.p",
          "connect m2.vb[0].y, m1.vb[0].y",
          "connect m2.vb[1].y, m1.vb[1].y",
          "connect m2.vr[0].p, m1.vr[0].p",
          "connect m2.vr[1].p, m1.vr[1].p",
          "connect m2.d.valid, m1.d.valid",
          "connect m2.d.bits, m1.d.bits",
          "connect s2.fl, s1.fl"
        ).sorted
      )

    test("half connects accept a read-only passive-side operand"):
      @generator
      object G extends Generator[ConnParameter, ConnLayers, EmptyIO, ConnProbe] with HasFirrtlTest:
        def architecture(parameter: ConnParameter) =
          val sink  = Wire[Record](new TwoURec(parameter.width))
          val srcB  = Wire(new TwoU(parameter.width))
          sink :<= srcB.asRecord
          val sinkB = Wire(new OneFlip(parameter.width))
          val src   = Wire[Record](new OneFlipRec(parameter.width))
          sinkB.asRecord :>= src
      G.firrtlTest(ConnParameter(8))("connect sink, srcB", "connect src.x, sinkB.x")

    test("composite mono connect is rejected at compile time"):
      @generator
      object G extends Generator[ConnParameter, ConnLayers, EmptyIO, ConnProbe] with HasFirrtlTest:
        def architecture(parameter: ConnParameter) =
          val w1 = Wire(new Dec(parameter.width))
          val w2 = Wire(new Dec(parameter.width))
          compileError("w1 := w2")
          w1 :<>= w2
          val v1 = Wire(Vec(2, UInt(parameter.width)))
          val v2 = Wire(Vec(2, UInt(parameter.width)))
          compileError("v1 := v2")
          v1 :<>= v2
      G.firrtlTest(ConnParameter(8))("connect w1, w2", "connect v1, v2")

    test("every error axis accumulates into one exception"):
      @generator
      object Bad extends Generator[ConnParameter, ConnLayers, EmptyIO, ConnProbe] with HasFirrtlTest:
        def architecture(parameter: ConnParameter) =
          val a = Wire(new MultiBadRec(false, parameter.width))
          val b = Wire(new MultiBadRec(true, parameter.width))
          a :<>= b
      val msg = intercept[ConnectException](Bad.firrtlString(ConnParameter(8))).getMessage
      assert(msg.contains("12 error(s)"))
      assert(msg.contains("missing in producer: onlyA"))
      assert(msg.contains("missing in sink: onlyB"))
      assert(msg.contains("w: width mismatch 8 vs 16"))
      assert(msg.contains("k: kind mismatch UInt vs SInt"))
      assert(msg.contains("o: orientation mismatch"))
      assert(msg.contains("vl: vec length mismatch 2 vs 3"))
      assert(msg.contains("ve[*].x: width mismatch"))
      assert(msg.contains("ve[*].y: width mismatch"))
      assert(msg.contains("sh: incompatible or unsupported types (UInt vs Vec)"))
      assert(msg.contains("rst: kind mismatch Reset vs AsyncReset"))
      assert(msg.contains("b1: kind mismatch Bool vs UInt"))
      assert(msg.contains("ord: field order mismatch"))

    test("probes are rejected with a pointer to <=="):
      @generator
      object Bad extends Generator[ConnParameter, ConnLayers, EmptyIO, ConnProbe] with HasFirrtlTest:
        def architecture(parameter: ConnParameter) =
          val probe     = summon[Interface[ConnProbe]]
          compileError("probe :<>= probe")
          val laundered = probe.asInstanceOf[me.jiuyang.zaozi.reftpe.Writable[Record]]
          laundered :<>= laundered
      val e = intercept[ConnectException](Bad.firrtlString(ConnParameter(8)))
      assert(e.getMessage.contains("probe types do not participate in bulk connects; use <== instead"))

    test("no partial connect on deep mismatch"):
      @generator
      object G extends Generator[ConnParameter, ConnLayers, EmptyIO, ConnProbe] with HasFirrtlTest:
        def architecture(parameter: ConnParameter) =
          val s  = Wire(new PartialRec(8))
          val pr = Wire(new PartialRec(16))
          try s :<>= pr
          catch case _: ConnectException => ()
      val out = G.firrtlString(ConnParameter(8))
      assert(!out.contains("connect s.a, pr.a"))
