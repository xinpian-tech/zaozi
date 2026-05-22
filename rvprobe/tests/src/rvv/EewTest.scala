// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.{OperandRole, Schema}
import me.jiuyang.rvprobe.rvv.eew.*
import me.jiuyang.rvprobe.rvv.vtype.{Lmul, Sew, VType, VTypeEnvelope, Vma, Vta}

import utest.*

object EewTest extends TestSuite:

  private def envSew32M1: VTypeEnvelope =
    VTypeEnvelope.unsafe(VType(Sew.Sew32, Lmul.M1, Vta.Agnostic, Vma.Agnostic), vl = 4, vlen = 128, xlen = 64)

  private def envSew32M4: VTypeEnvelope =
    VTypeEnvelope.unsafe(VType(Sew.Sew32, Lmul.M4, Vta.Agnostic, Vma.Agnostic), vl = 16, vlen = 128, xlen = 64)

  private def envSew8M1: VTypeEnvelope =
    VTypeEnvelope.unsafe(VType(Sew.Sew8, Lmul.M1, Vta.Agnostic, Vma.Agnostic), vl = 16, vlen = 128, xlen = 64)

  val tests = Tests:

    test("OperandClass.of vector roles in non-indexed schema are VectorElementSew"):
      val s = Schema.VdVs2Vs1Vm
      assert(OperandClass.of(OperandRole.Vd, s) == OperandClass.VectorElementSew)
      assert(OperandClass.of(OperandRole.Vs1, s) == OperandClass.VectorElementSew)
      assert(OperandClass.of(OperandRole.Vs2, s) == OperandClass.VectorElementSew)
      assert(OperandClass.of(OperandRole.Vm, s) == OperandClass.Mask)

    test("OperandClass.of vs2 in indexed schema is VectorElementIndex"):
      assert(OperandClass.of(OperandRole.Vs2, Schema.VdRs1mVs2Vm) == OperandClass.VectorElementIndex)
      assert(OperandClass.of(OperandRole.Vs2, Schema.Vs3Rs1mVs2Vm) == OperandClass.VectorElementIndex)

    test("OperandClass.of scalar roles"):
      val s = Schema.VdVs2Rs1Vm
      assert(OperandClass.of(OperandRole.Rs1, s) == OperandClass.ScalarInteger)
      assert(OperandClass.of(OperandRole.Rs1Mem, Schema.VdRs1mVm) == OperandClass.ScalarMemory)

    test("OperandClass.of FP roles"):
      val s = Schema.VdVs2Fs1Vm
      assert(OperandClass.of(OperandRole.Fs1, s) == OperandClass.ScalarFp)
      assert(OperandClass.of(OperandRole.Fd, Schema.FdVs2) == OperandClass.ScalarFp)

    test("OperandClass.of immediate roles"):
      assert(OperandClass.of(OperandRole.Imm, Schema.VdImm) == OperandClass.Immediate)
      assert(OperandClass.of(OperandRole.Uimm, Schema.VdVs2Uimm) == OperandClass.Immediate)

    test("OperandClass.of grouped-mask variants"):
      assert(OperandClass.of(OperandRole.VmGroup2, Schema.VdVs2VmP2) == OperandClass.GroupedMask)
      assert(OperandClass.of(OperandRole.VmGroup3, Schema.VdVs2VmP3) == OperandClass.GroupedMask)

    test("Eew.compute Mask returns 1"):
      val s = Schema.VdVs2Vs1Vm
      assert(Eew.compute(OperandRole.Vm, s, envSew32M1) == 1)

    test("Eew.compute ScalarInteger returns xlen"):
      val s = Schema.VdVs2Rs1Vm
      assert(Eew.compute(OperandRole.Rs1, s, envSew32M1) == 64)

    test("Eew.compute ScalarMemory returns xlen"):
      val s = Schema.VdRs1mVm
      assert(Eew.compute(OperandRole.Rs1Mem, s, envSew32M1) == 64)

    test("Eew.compute VectorElementSew with no widening returns SEW"):
      val s = Schema.VdVs2Vs1Vm
      assert(Eew.compute(OperandRole.Vd, s, envSew32M1) == 32)

    test("Eew.compute VectorElementSew with By2 widening returns 2*SEW"):
      val s = Schema.VdVs2Vs1Vm
      assert(Eew.compute(OperandRole.Vd, s, envSew32M1, widening = Widening.By2) == 64)

    test("Eew.compute VectorElementSew with By4 widening returns 4*SEW"):
      val s = Schema.VdVs2Vs1Vm
      assert(Eew.compute(OperandRole.Vd, s, envSew8M1, widening = Widening.By4) == 32)

    test("Eew.compute VectorElementSew with Narrow2 returns SEW/2"):
      val s = Schema.VdVs2Vs1Vm
      assert(Eew.compute(OperandRole.Vd, s, envSew32M1, widening = Widening.Narrow2) == 16)

    test("Eew.compute VectorElementIndex returns the provided indexedEew"):
      val s = Schema.VdRs1mVs2Vm
      assert(Eew.compute(OperandRole.Vs2, s, envSew32M1, indexedEew = Some(8)) == 8)
      assert(Eew.compute(OperandRole.Vs2, s, envSew32M1, indexedEew = Some(64)) == 64)

    test("Eew.compute VectorElementIndex without indexedEew throws"):
      val s = Schema.VdRs1mVs2Vm
      val ex = intercept[IllegalArgumentException]:
        Eew.compute(OperandRole.Vs2, s, envSew32M1, indexedEew = None)
      assert(ex.getMessage.contains("indexedEew"))

    test("Emul.compute VectorElementSew at LMUL=M1 with no widening = 1/1"):
      val s   = Schema.VdVs2Vs1Vm
      val emul = Emul.compute(OperandRole.Vd, s, envSew32M1)
      assert(emul.numerator == 1)
      assert(emul.denominator == 1)
      assert(emul.asWholeRegisters == 1)

    test("Emul.compute VectorElementSew at LMUL=M4 with no widening = 4/1"):
      val s   = Schema.VdVs2Vs1Vm
      val emul = Emul.compute(OperandRole.Vd, s, envSew32M4)
      assert(emul.numerator == 4)
      assert(emul.denominator == 1)
      assert(emul.asWholeRegisters == 4)

    test("Emul.compute VectorElementSew widening By2 at LMUL=M4 = 8 registers"):
      val s    = Schema.VdVs2Vs1Vm
      val emul = Emul.compute(OperandRole.Vd, s, envSew32M4, widening = Widening.By2)
      assert(emul.numerator == 8)
      assert(emul.denominator == 1)
      assert(emul.asWholeRegisters == 8)

    test("Emul.compute fractional LMUL"):
      val env = VTypeEnvelope.unsafe(
        VType(Sew.Sew8, Lmul.Mf2, Vta.Agnostic, Vma.Agnostic),
        vl = 1,
        vlen = 128,
        xlen = 64)
      val s   = Schema.VdVs2Vs1Vm
      val emul = Emul.compute(OperandRole.Vd, s, env)
      assert(emul.numerator == 1)
      assert(emul.denominator == 2)
      assert(emul.isFractional)
      assert(emul.asWholeRegisters == 1)

    test("RegisterFootprint of vector at LMUL=M4 = 4"):
      val s = Schema.VdVs2Vs1Vm
      assert(RegisterFootprint.of(OperandRole.Vd, s, envSew32M4) == 4)

    test("RegisterFootprint of vector at fractional LMUL = 1"):
      val env = VTypeEnvelope.unsafe(
        VType(Sew.Sew8, Lmul.Mf2, Vta.Agnostic, Vma.Agnostic),
        vl = 1,
        vlen = 128,
        xlen = 64)
      val s = Schema.VdVs2Vs1Vm
      assert(RegisterFootprint.of(OperandRole.Vd, s, env) == 1)

    test("RegisterFootprint of mask = 1"):
      val s = Schema.VdVs2Vs1Vm
      assert(RegisterFootprint.of(OperandRole.Vm, s, envSew32M4) == 1)

    test("RegisterFootprint of segmented store at NFIELDS=2 LMUL=M2 = 4"):
      val env = VTypeEnvelope.unsafe(
        VType(Sew.Sew32, Lmul.M2, Vta.Agnostic, Vma.Agnostic),
        vl = 8,
        vlen = 128,
        xlen = 64)
      val s = Schema.Vs3Rs1mVm
      assert(RegisterFootprint.of(OperandRole.Vs3, s, env, nfields = 2) == 4)

    test("NfieldsValidator rejects out-of-range nfields"):
      val env = envSew32M1
      assert(NfieldsValidator.check(env, 0).isLeft)
      assert(NfieldsValidator.check(env, 9).isLeft)
      assert(NfieldsValidator.check(env, -1).isLeft)

    test("NfieldsValidator rejects NFIELDS x EMUL > 8"):
      val env = envSew32M4
      assert(NfieldsValidator.check(env, 4).isLeft) // 4*4=16
      assert(NfieldsValidator.check(env, 3).isLeft) // 3*4=12
      assert(NfieldsValidator.check(env, 2).isRight) // 2*4=8

    test("NfieldsValidator accepts NFIELDS=8 at LMUL=M1"):
      val env = envSew32M1
      assert(NfieldsValidator.check(env, 8).isRight)
