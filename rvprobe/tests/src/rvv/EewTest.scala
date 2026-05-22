// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.{OperandRole, Schema}
import me.jiuyang.rvprobe.rvv.eew.*
import me.jiuyang.rvprobe.rvv.vtype.{Lmul, Sew, VType, VTypeEnvelope, Vma, Vta}

import utest.*

object EewTest extends TestSuite:

  private def env(sew: Sew, lmul: Lmul, vl: Int = 0, vlen: Int = 128, xlen: Int = 64): VTypeEnvelope =
    VTypeEnvelope.unsafe(VType(sew, lmul, Vta.Agnostic, Vma.Agnostic), vl, vlen, xlen)

  val tests = Tests:

    test("OperandClass mapping in non-indexed schema"):
      val s = Schema.VdVs2Vs1Vm
      assert(OperandClass.of(OperandRole.Vd, s) == OperandClass.VectorElementSew)
      assert(OperandClass.of(OperandRole.Vs1, s) == OperandClass.VectorElementSew)
      assert(OperandClass.of(OperandRole.Vs2, s) == OperandClass.VectorElementSew)
      assert(OperandClass.of(OperandRole.Vm, s) == OperandClass.Mask)

    test("OperandClass routes vs2 of indexed schemas to VectorElementIndex"):
      assert(OperandClass.of(OperandRole.Vs2, Schema.VdRs1mVs2Vm) == OperandClass.VectorElementIndex)
      assert(OperandClass.of(OperandRole.Vs2, Schema.Vs3Rs1mVs2Vm) == OperandClass.VectorElementIndex)

    test("Schema.destRole = first operand-role"):
      assert(Schema.VdVs2Vs1Vm.destRole.contains(OperandRole.Vd))
      assert(Schema.RdVs2.destRole.contains(OperandRole.Rd))
      assert(Schema.Vs3Rs1m.destRole.contains(OperandRole.Vs3))
      assert(Schema.Vsetvl.destRole.contains(OperandRole.Rd))

    // ---- WidthScale ----

    test("WidthScale.By2 on SEW=32 yields 64"):
      assert((WidthScale.By2 * 32) == Some(64))

    test("WidthScale.Narrow2 on SEW=16 yields 8"):
      assert((WidthScale.Narrow2 * 16) == Some(8))

    test("WidthScale.Narrow2 on odd SEW is None (non-integral)"):
      assert((WidthScale.Narrow2 * 3) == None)

    // ---- Real RVV instruction shapes ----

    test("vadd.vv: all vector operands at base SEW/LMUL"):
      val e = env(Sew.Sew32, Lmul.M2)
      val s = Schema.VdVs2Vs1Vm
      val p = OperandWidthProfile.default
      assert(Eew.compute(OperandRole.Vd, s, e, p) == Right(32))
      assert(Eew.compute(OperandRole.Vs1, s, e, p) == Right(32))
      assert(Eew.compute(OperandRole.Vs2, s, e, p) == Right(32))
      assert(Emul.compute(OperandRole.Vd, s, e, p).map(_.asWholeRegisters) == Right(2))
      assert(Emul.compute(OperandRole.Vs1, s, e, p).map(_.asWholeRegisters) == Right(2))

    test("vwadd.vv: vd doubled; vs1, vs2 unchanged"):
      val e = env(Sew.Sew32, Lmul.M4)
      val s = Schema.VdVs2Vs1Vm
      val p = OperandWidthProfile(Map(OperandRole.Vd -> WidthScale.By2))
      assert(Eew.compute(OperandRole.Vd, s, e, p) == Right(64))
      assert(Eew.compute(OperandRole.Vs1, s, e, p) == Right(32))
      assert(Eew.compute(OperandRole.Vs2, s, e, p) == Right(32))
      assert(Emul.compute(OperandRole.Vd, s, e, p).map(_.asWholeRegisters) == Right(8))
      assert(Emul.compute(OperandRole.Vs1, s, e, p).map(_.asWholeRegisters) == Right(4))
      assert(Emul.compute(OperandRole.Vs2, s, e, p).map(_.asWholeRegisters) == Right(4))

    test("vwadd.wv: vd and vs2 doubled; vs1 unchanged"):
      val e = env(Sew.Sew32, Lmul.M2)
      val s = Schema.VdVs2Vs1Vm
      val p = OperandWidthProfile(Map(OperandRole.Vd -> WidthScale.By2, OperandRole.Vs2 -> WidthScale.By2))
      assert(Eew.compute(OperandRole.Vd, s, e, p) == Right(64))
      assert(Eew.compute(OperandRole.Vs2, s, e, p) == Right(64))
      assert(Eew.compute(OperandRole.Vs1, s, e, p) == Right(32))
      assert(Emul.compute(OperandRole.Vd, s, e, p).map(_.asWholeRegisters) == Right(4))
      assert(Emul.compute(OperandRole.Vs2, s, e, p).map(_.asWholeRegisters) == Right(4))
      assert(Emul.compute(OperandRole.Vs1, s, e, p).map(_.asWholeRegisters) == Right(2))

    test("vfncvt.f.f.w (narrowing): vs2 doubled, vd unchanged"):
      val e = env(Sew.Sew16, Lmul.M2)
      val s = Schema.VdVs2Vm
      val p = OperandWidthProfile(Map(OperandRole.Vs2 -> WidthScale.By2))
      assert(Eew.compute(OperandRole.Vd, s, e, p) == Right(16))
      assert(Eew.compute(OperandRole.Vs2, s, e, p) == Right(32))
      assert(Emul.compute(OperandRole.Vd, s, e, p).map(_.asWholeRegisters) == Right(2))
      assert(Emul.compute(OperandRole.Vs2, s, e, p).map(_.asWholeRegisters) == Right(4))

    test("vmseq.vv: vd is a mask destination, footprint = 1 register at any LMUL"):
      val e = env(Sew.Sew32, Lmul.M4)
      val s = Schema.VdVs2Vs1Vm
      val p = OperandWidthProfile.maskDestination()
      assert(Eew.compute(OperandRole.Vd, s, e, p) == Right(1))
      assert(Eew.compute(OperandRole.Vs1, s, e, p) == Right(32))
      assert(Eew.compute(OperandRole.Vs2, s, e, p) == Right(32))
      assert(Emul.compute(OperandRole.Vd, s, e, p) == Right(EmulRatio(1, 1)))
      assert(RegisterFootprint.of(OperandRole.Vd, s, e, p) == Right(1))
      assert(RegisterFootprint.of(OperandRole.Vs1, s, e, p) == Right(4))
      assert(RegisterFootprint.of(OperandRole.Vs2, s, e, p) == Right(4))

    test("vluxei32.v: indexed load, index EEW=32 separate from data SEW=8"):
      val e          = env(Sew.Sew8, Lmul.M1)
      val s          = Schema.VdRs1mVs2Vm
      val p          = OperandWidthProfile.default
      val indexedEew = Some(32)
      assert(Eew.compute(OperandRole.Vd, s, e, p) == Right(8))
      assert(Eew.compute(OperandRole.Vs2, s, e, p, indexedEew) == Right(32))
      assert(Emul.compute(OperandRole.Vd, s, e, p).map(_.asWholeRegisters) == Right(1))
      // vs2 EMUL = (32/8) × 1 = 4
      assert(Emul.compute(OperandRole.Vs2, s, e, p, indexedEew).map(_.asWholeRegisters) == Right(4))

    test("vluxei32.v rejects when LMUL=M4 pushes index EMUL out of spec"):
      val e          = env(Sew.Sew8, Lmul.M4, vlen = 128, xlen = 64)
      val s          = Schema.VdRs1mVs2Vm
      val p          = OperandWidthProfile.default
      val indexedEew = Some(32)
      // index EMUL = (32/8) × 4 = 16 — out of spec [1/8, 8]
      val result = Emul.compute(OperandRole.Vs2, s, e, p, indexedEew)
      assert(result.isLeft)
      assert(result.left.toOption.exists(_.contains("outside spec")))

    test("vsseg2e32.v: segmented store with NFIELDS=2 footprint = 2 registers"):
      val e = env(Sew.Sew32, Lmul.M1)
      val s = Schema.Vs3Rs1mVm
      val p = OperandWidthProfile.default
      assert(RegisterFootprint.of(OperandRole.Vs3, s, e, p, nfields = 2) == Right(2))

    test("default-profile segmented case: LMUL=M4 + nfields=3 -> Left (12 > 8)"):
      val e = env(Sew.Sew32, Lmul.M4)
      val s = Schema.Vs3Rs1mVm
      val p = OperandWidthProfile.default
      val r = RegisterFootprint.of(OperandRole.Vs3, s, e, p, nfields = 3)
      assert(r.isLeft)
      r.left.toOption.foreach { msg =>
        assert(msg.contains("NFIELDS"))
        assert(msg.contains("EMUL"))
        assert(msg.contains("8-register limit"))
      }

    test("profile-derived overflow: SEW=32, LMUL=M4, profile(Vd->By2), nfields=2 -> Left (2 x 8 = 16)"):
      val e = env(Sew.Sew32, Lmul.M4)
      val s = Schema.VdVs2Vs1Vm
      val p = OperandWidthProfile(Map(OperandRole.Vd -> WidthScale.By2))
      // Base LMUL=4 alone with nfields=2 would say 4*2=8 (legal), but
      // computed EMUL with widening is 8, footprint 8*2=16, illegal.
      val r = RegisterFootprint.of(OperandRole.Vd, s, e, p, nfields = 2)
      assert(r.isLeft)
      r.left.toOption.foreach { msg =>
        assert(msg.contains("NFIELDS"))
        assert(msg.contains("EMUL"))
        assert(msg.contains("8-register limit"))
      }

    test("vsseg case at LMUL=M2 hits exact 8-register limit at nfields=4"):
      val e = env(Sew.Sew32, Lmul.M2)
      val s = Schema.Vs3Rs1mVm
      val p = OperandWidthProfile.default
      assert(RegisterFootprint.of(OperandRole.Vs3, s, e, p, nfields = 4) == Right(8))
      // and trips over at nfields = 5
      val tooMany = RegisterFootprint.of(OperandRole.Vs3, s, e, p, nfields = 5)
      assert(tooMany.isLeft)

    // ---- Validation: invalid EEW/EMUL combinations ----

    test("EEW exceeds XLEN: SEW=64 + By2 + XLEN=64 → Left"):
      val e = env(Sew.Sew64, Lmul.M1, vlen = 256, xlen = 64)
      val s = Schema.VdVs2Vs1Vm
      val p = OperandWidthProfile(Map(OperandRole.Vd -> WidthScale.By2))
      val r = Eew.compute(OperandRole.Vd, s, e, p)
      assert(r.isLeft)
      assert(r.left.toOption.exists(m => m.contains("exceeds XLEN") || m.contains("not in legal")))

    test("EEW non-integral: WidthScale.Narrow4 on SEW=8 → Left"):
      val e = env(Sew.Sew8, Lmul.M1)
      val s = Schema.VdVs2Vs1Vm
      val p = OperandWidthProfile(Map(OperandRole.Vd -> WidthScale.Narrow4))
      val r = Eew.compute(OperandRole.Vd, s, e, p)
      assert(r.isLeft)
      assert(r.left.toOption.exists(m => m.contains("non-integral") || m.contains("not in legal")))

    test("EMUL out of spec: SEW=8 + LMUL=M8 + By2 → EMUL=16 → Left"):
      val e = env(Sew.Sew8, Lmul.M8, vlen = 128, xlen = 64)
      val s = Schema.VdVs2Vs1Vm
      val p = OperandWidthProfile(Map(OperandRole.Vd -> WidthScale.By2))
      val r = Emul.compute(OperandRole.Vd, s, e, p)
      assert(r.isLeft)
      assert(r.left.toOption.exists(_.contains("outside spec")))

    test("indexed schema without indexedEew returns Left"):
      val e = env(Sew.Sew8, Lmul.M1)
      val s = Schema.VdRs1mVs2Vm
      val p = OperandWidthProfile.default
      val r = Eew.compute(OperandRole.Vs2, s, e, p)
      assert(r.isLeft)
      assert(r.left.toOption.exists(_.contains("indexedEew")))

    test("indexed EEW not in legal {8,16,32,64} → Left"):
      val e = env(Sew.Sew8, Lmul.M1)
      val s = Schema.VdRs1mVs2Vm
      val p = OperandWidthProfile.default
      val r = Eew.compute(OperandRole.Vs2, s, e, p, indexedEew = Some(12))
      assert(r.isLeft)

    // ---- Mask and scalar operands ----

    test("Mask operand EEW = 1"):
      val s = Schema.VdVs2Vs1Vm
      val e = env(Sew.Sew32, Lmul.M1)
      assert(Eew.compute(OperandRole.Vm, s, e) == Right(1))

    test("ScalarInteger operand EEW = XLEN"):
      val s = Schema.VdVs2Rs1Vm
      val e = env(Sew.Sew32, Lmul.M1, xlen = 64)
      assert(Eew.compute(OperandRole.Rs1, s, e) == Right(64))

    test("ScalarMemory operand EEW = XLEN"):
      val s = Schema.VdRs1mVm
      val e = env(Sew.Sew32, Lmul.M1, xlen = 32)
      assert(Eew.compute(OperandRole.Rs1Mem, s, e) == Right(32))

    test("FP scalar EEW = SEW"):
      val s = Schema.FdVs2
      val e = env(Sew.Sew64, Lmul.M1)
      assert(Eew.compute(OperandRole.Fd, s, e) == Right(64))

    // ---- Fractional LMUL ----

    test("Fractional LMUL: SEW=8 + LMUL=Mf2 → EMUL = 1/2 (1 whole register)"):
      val e = env(Sew.Sew8, Lmul.Mf2)
      val s = Schema.VdVs2Vs1Vm
      val r = Emul.compute(OperandRole.Vd, s, e)
      assert(r.map(_.numerator) == Right(1))
      assert(r.map(_.denominator) == Right(2))
      assert(r.map(_.asWholeRegisters) == Right(1))
      assert(r.map(_.isFractional) == Right(true))

    test("Fractional LMUL register footprint = 1"):
      val e = env(Sew.Sew8, Lmul.Mf8)
      val s = Schema.VdVs2Vs1Vm
      assert(RegisterFootprint.of(OperandRole.Vd, s, e) == Right(1))

    // ---- NFIELDS validator (role/profile-aware) ----

    test("NfieldsValidator rejects nfields < 1 or > 8"):
      val e = env(Sew.Sew32, Lmul.M1)
      val s = Schema.Vs3Rs1mVm
      assert(NfieldsValidator.check(OperandRole.Vs3, s, e, nfields = 0).isLeft)
      assert(NfieldsValidator.check(OperandRole.Vs3, s, e, nfields = 9).isLeft)
      assert(NfieldsValidator.check(OperandRole.Vs3, s, e, nfields = -1).isLeft)

    test("NfieldsValidator rejects NFIELDS x EMUL > 8 at base LMUL"):
      val e = env(Sew.Sew32, Lmul.M4)
      val s = Schema.Vs3Rs1mVm
      assert(NfieldsValidator.check(OperandRole.Vs3, s, e, nfields = 4).isLeft)
      assert(NfieldsValidator.check(OperandRole.Vs3, s, e, nfields = 2).isRight) // 2*4=8 at the limit

    test("NfieldsValidator rejects when widening pushes computed EMUL over limit"):
      val e = env(Sew.Sew32, Lmul.M4)
      val s = Schema.VdVs2Vs1Vm
      val p = OperandWidthProfile(Map(OperandRole.Vd -> WidthScale.By2))
      assert(NfieldsValidator.check(OperandRole.Vd, s, e, p, nfields = 2).isLeft)
