// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.vtype.{Lmul, Sew, VType, VTypeEnvelope, Vma, Vta}

import utest.*

object VTypeEnvelopeTest extends TestSuite:

  private def vt(sew: Sew, lmul: Lmul): VType =
    VType(sew, lmul, Vta.Agnostic, Vma.Agnostic)

  val tests = Tests:

    test("VType.isLegal accepts SEW <= XLEN with integer LMUL"):
      assert(VType.isLegal(Sew.Sew32, Lmul.M1, elen = 64))
      assert(VType.isLegal(Sew.Sew64, Lmul.M1, elen = 64))
      assert(VType.isLegal(Sew.Sew8, Lmul.M8, elen = 64))

    test("VType.isLegal rejects SEW > XLEN"):
      assert(!VType.isLegal(Sew.Sew64, Lmul.M1, elen = 32))
      assert(!VType.isLegal(Sew.Sew32, Lmul.M1, elen = 16))

    test("VType.isLegal handles fractional-LMUL constraint SEW * (1/LMUL) <= ELEN"):
      assert(VType.isLegal(Sew.Sew8, Lmul.Mf8, elen = 64))
      assert(!VType.isLegal(Sew.Sew16, Lmul.Mf8, elen = 64))
      assert(!VType.isLegal(Sew.Sew64, Lmul.Mf2, elen = 64))
      assert(VType.isLegal(Sew.Sew32, Lmul.Mf2, elen = 64))

    test("VTypeEnvelope.apply rejects illegal VType (SEW > XLEN)"):
      val r = VTypeEnvelope(vt(Sew.Sew64, Lmul.M1), vl = 0, vlen = 128, xlen = 32)
      assert(r.isLeft)

    test("VTypeEnvelope.apply rejects illegal VType (fractional overflow)"):
      val r = VTypeEnvelope(vt(Sew.Sew16, Lmul.Mf8), vl = 0, vlen = 128, xlen = 64)
      assert(r.isLeft)

    test("VTypeEnvelope.apply rejects non-power-of-two VLEN"):
      val r = VTypeEnvelope(vt(Sew.Sew32, Lmul.M1), vl = 0, vlen = 100, xlen = 64)
      assert(r.isLeft)

    test("VTypeEnvelope.apply rejects vlen <= 0"):
      val r = VTypeEnvelope(vt(Sew.Sew32, Lmul.M1), vl = 0, vlen = 0, xlen = 64)
      assert(r.isLeft)

    test("VTypeEnvelope.apply rejects xlen != 32 and != 64"):
      val r = VTypeEnvelope(vt(Sew.Sew32, Lmul.M1), vl = 0, vlen = 128, xlen = 16)
      assert(r.isLeft)

    test("VTypeEnvelope.apply rejects vl out of range"):
      val r = VTypeEnvelope(vt(Sew.Sew32, Lmul.M1), vl = 9999, vlen = 128, xlen = 64)
      assert(r.isLeft)

    test("VTypeEnvelope.apply accepts valid args"):
      val r = VTypeEnvelope(vt(Sew.Sew32, Lmul.M1), vl = 4, vlen = 128, xlen = 64)
      assert(r.isRight)

    test("VTypeEnvelope#vill always returns false"):
      val env = VTypeEnvelope.unsafe(vt(Sew.Sew32, Lmul.M1), vl = 4, vlen = 128, xlen = 64)
      assert(env.vill == false)

    test("VTypeEnvelope#elementsPerRegister"):
      val env = VTypeEnvelope.unsafe(vt(Sew.Sew32, Lmul.M1), vl = 4, vlen = 128, xlen = 64)
      assert(env.elementsPerRegister == 128 / 32)

    test("VTypeEnvelope#registerGroupSize integer LMUL"):
      val env = VTypeEnvelope.unsafe(vt(Sew.Sew32, Lmul.M4), vl = 4, vlen = 128, xlen = 64)
      assert(env.registerGroupSize == 4)

    test("VTypeEnvelope#registerGroupSize fractional LMUL is 1 register"):
      val env = VTypeEnvelope.unsafe(vt(Sew.Sew8, Lmul.Mf2), vl = 1, vlen = 128, xlen = 64)
      assert(env.registerGroupSize == 1)

    test("VTypeEnvelope equals and hashCode have value semantics"):
      val a = VTypeEnvelope.unsafe(vt(Sew.Sew32, Lmul.M1), vl = 4, vlen = 128, xlen = 64)
      val b = VTypeEnvelope.unsafe(vt(Sew.Sew32, Lmul.M1), vl = 4, vlen = 128, xlen = 64)
      assert(a == b)
      assert(a.hashCode == b.hashCode)

    test("VTypeEnvelope is not a case class (no synthesized copy method)"):
      // Codex AC-5 requirement: structural copy must not be available
      // since the smart constructor is the only validated construction path.
      val methods = classOf[VTypeEnvelope].getMethods.map(_.getName).toSet
      assert(!methods.contains("copy"))
      assert(!methods.contains("productElement"))
      assert(!methods.contains("productArity"))

    test("VTypeEnvelope companion has no Mirror.Product synthesized members"):
      // case class would expose a `fromProduct` on the companion's Mirror.
      // Use reflection on the companion module class to confirm absence.
      val companion = me.jiuyang.rvprobe.rvv.vtype.VTypeEnvelope.getClass
      val methods   = companion.getMethods.map(_.getName).toSet
      assert(!methods.contains("fromProduct"))
