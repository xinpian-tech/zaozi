// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.{OperandRole, Schema}
import me.jiuyang.rvprobe.rvv.eew.*
import me.jiuyang.rvprobe.rvv.pred.*
import me.jiuyang.rvprobe.rvv.unittest.*
import me.jiuyang.rvprobe.rvv.vtype.*

import utest.*

object EmissionPipelineTest extends TestSuite:

  private def env32M1: VTypeEnvelope =
    VTypeEnvelope.unsafe(VType(Sew.Sew32, Lmul.M1, Vta.Agnostic, Vma.Agnostic), 4, 128, 64)

  val tests = Tests:

    // ---- ElemValueLowering (task4) ----

    test("ElemValueLowering.lowerValue Zero at any SEW"):
      assert(ElemValueLowering.lowerValue(ValuePred.Zero, Sew.Sew8) == BigInt(0))
      assert(ElemValueLowering.lowerValue(ValuePred.Zero, Sew.Sew64) == BigInt(0))

    test("ElemValueLowering.lowerValue MaxSigned at each SEW"):
      assert(ElemValueLowering.lowerValue(ValuePred.MaxSigned(Sew.Sew8), Sew.Sew8) == BigInt(0x7f))
      assert(ElemValueLowering.lowerValue(ValuePred.MaxSigned(Sew.Sew16), Sew.Sew16) == BigInt(0x7fff))

    test("ElemValueLowering.lowerValue MinSigned == SignBitOnly bit pattern"):
      val msMin = ElemValueLowering.lowerValue(ValuePred.MinSigned(Sew.Sew8), Sew.Sew8)
      val sbo   = ElemValueLowering.lowerValue(ValuePred.SignBitOnly(Sew.Sew8), Sew.Sew8)
      assert(msMin == BigInt(0x80))
      assert(sbo == BigInt(0x80))

    test("ElemValueLowering.lowerValue AllOnes/MinusOne at SEW8 = 0xff"):
      assert(ElemValueLowering.lowerValue(ValuePred.AllOnes(Sew.Sew8), Sew.Sew8) == BigInt(0xff))
      assert(ElemValueLowering.lowerValue(ValuePred.MinusOne, Sew.Sew8) == BigInt(0xff))

    test("ElemValueLowering.lowerValue Random is deterministic"):
      val a = ElemValueLowering.lowerValue(ValuePred.Random(Sew.Sew32, 42L), Sew.Sew32)
      val b = ElemValueLowering.lowerValue(ValuePred.Random(Sew.Sew32, 42L), Sew.Sew32)
      assert(a == b)

    test("ElemValueLowering.lowerTuple DivByZero produces (1, 0)"):
      val row = ElemValueLowering.lowerTuple(TuplePred.DivByZero(Sew.Sew8), Sew.Sew8, 2)
      assert(row == List(BigInt(1), BigInt(0)))

    test("ElemValueLowering.lowerTuple MinSignedDivZero produces (0x80, 0)"):
      val row = ElemValueLowering.lowerTuple(TuplePred.MinSignedDivZero(Sew.Sew8), Sew.Sew8, 2)
      assert(row == List(BigInt(0x80), BigInt(0)))

    test("ElemValueLowering.buildVector fills element count via round-robin"):
      val v = ElemValueLowering.buildVector(
        Seq(ValuePred.Zero, ValuePred.One),
        Sew.Sew8,
        elementCount = 5)
      assert(v.size == 5)
      assert(v == Vector(BigInt(0), BigInt(1), BigInt(0), BigInt(1), BigInt(0)))

    // ---- MagicInstrEmit (task5 / AC-8) ----

    test("MagicInstrEmit.encode opcode bits 6:0 are 0x0B"):
      val w = MagicInstrEmit.encode(0, Lmul.M1, false)
      assert((w & 0x7f) == 0x0B)

    test("MagicInstrEmit.encode group field bits 19:15"):
      val w = MagicInstrEmit.encode(7, Lmul.M1, false)
      assert(((w >>> 15) & 0x1f) == 7)
      val w24 = MagicInstrEmit.encode(24, Lmul.M1, false)
      assert(((w24 >>> 15) & 0x1f) == 24)

    test("MagicInstrEmit.encode vxsat bit 20"):
      val w0 = MagicInstrEmit.encode(0, Lmul.M1, false)
      val w1 = MagicInstrEmit.encode(0, Lmul.M1, true)
      assert(((w0 >>> 20) & 0x1) == 0)
      assert(((w1 >>> 20) & 0x1) == 1)

    test("MagicInstrEmit.encode rs2[4:1] = whole-register count (pspike contract)"):
      // Upstream pspike reads `int lmul1 = insn.rs2() >> 1` as the
      // whole-register count, NOT the architectural vlmul encoding.
      // M1/Mfractional -> 1, M2 -> 2, M4 -> 4, M8 -> 8.
      assert(((MagicInstrEmit.encode(0, Lmul.M1, false) >>> 21) & 0xf) == 1)
      assert(((MagicInstrEmit.encode(0, Lmul.M2, false) >>> 21) & 0xf) == 2)
      assert(((MagicInstrEmit.encode(0, Lmul.M4, false) >>> 21) & 0xf) == 4)
      assert(((MagicInstrEmit.encode(0, Lmul.M8, false) >>> 21) & 0xf) == 8)
      assert(((MagicInstrEmit.encode(0, Lmul.Mf2, false) >>> 21) & 0xf) == 1)
      assert(((MagicInstrEmit.encode(0, Lmul.Mf4, false) >>> 21) & 0xf) == 1)
      assert(((MagicInstrEmit.encode(0, Lmul.Mf8, false) >>> 21) & 0xf) == 1)

    test("MagicInstrEmit.wholeRegisterCount matches upstream gMagicInsn semantics"):
      assert(MagicInstrEmit.wholeRegisterCount(Lmul.M1) == 1)
      assert(MagicInstrEmit.wholeRegisterCount(Lmul.M2) == 2)
      assert(MagicInstrEmit.wholeRegisterCount(Lmul.M4) == 4)
      assert(MagicInstrEmit.wholeRegisterCount(Lmul.M8) == 8)
      assert(MagicInstrEmit.wholeRegisterCount(Lmul.Mf2) == 1)
      assert(MagicInstrEmit.wholeRegisterCount(Lmul.Mf4) == 1)
      assert(MagicInstrEmit.wholeRegisterCount(Lmul.Mf8) == 1)

    test("MagicInstrEmit round-trip encode/decode every LMUL"):
      val lmuls = List(Lmul.M1, Lmul.M2, Lmul.M4, Lmul.M8, Lmul.Mf2, Lmul.Mf4, Lmul.Mf8)
      for l <- lmuls
          g <- List(0, 1, 8, 15, 24, 31)
          v <- List(true, false)
      do
        val w = MagicInstrEmit.encode(g, l, v)
        val expectedRC = MagicInstrEmit.wholeRegisterCount(l)
        MagicInstrEmit.decode(w) match
          case Some((dg, drc, dv)) =>
            assert(dg == g)
            assert(drc == expectedRC)
            assert(dv == v)
          case None                =>
            assert(false)

    test("MagicInstrEmit.emitAsm produces .word 0x... directive"):
      val s = MagicInstrEmit.emitAsm(8, Lmul.M2, false)
      assert(s.startsWith(".word 0x"))

    test("MagicInstrEmit.decode rejects non-opcode-0x0B words"):
      assert(MagicInstrEmit.decode(0x00000013) == None) // addi opcode
      assert(MagicInstrEmit.decode(0x00000003) == None) // load opcode

    // ---- TestSEmit (task6) ----

    test("TestSEmit.render produces RVTEST_RV64UV envelope for xlen=64 + full march"):
      val block = TestSEmit.TestBlock(env32M1, 8, false, "vadd.vv v8,v16,v24")
      val s     = TestSEmit.render("vadd.vv", "RVTEST_RV64UV", List(block),
                                   Vector.fill(16)(0xff.toByte), 64)
      assert(s.contains("RVTEST_RV64UV"))
      assert(s.contains("RVTEST_CODE_BEGIN"))
      assert(s.contains("RVTEST_CODE_END"))
      assert(s.contains("RVTEST_DATA_BEGIN"))
      assert(s.contains("RVTEST_DATA_END"))
      assert(s.contains("vsetvli"))
      assert(s.contains("vmv.v.i v0, 0"))
      assert(s.contains("vmv.v.i v8, 0"))
      assert(s.contains("vmv.v.i v16, 0"))
      assert(s.contains("vmv.v.i v24, 0"))
      assert(s.contains(".word 0x"))
      assert(s.contains("TEST_CASE(2, x0, 0x0)"))
      assert(s.contains("TEST_PASSFAIL"))

    test("TestSEmit.envMacro selects RV{32|64}UV{|X}"):
      assert(TestSEmit.envMacro(64, hasFullV = true) == "RVTEST_RV64UV")
      assert(TestSEmit.envMacro(32, hasFullV = true) == "RVTEST_RV32UV")
      assert(TestSEmit.envMacro(64, hasFullV = false) == "RVTEST_RV64UVX")
      assert(TestSEmit.envMacro(32, hasFullV = false) == "RVTEST_RV32UVX")

    test("TestSEmit.vsetvliAsm matches expected mnemonic"):
      val v = TestSEmit.vsetvliAsm(env32M1)
      assert(v.contains("e32"))
      assert(v.contains("m1"))
      assert(v.contains("ta"))
      assert(v.contains("ma"))

    // ---- OverlapLegality (task7) ----

    test("OverlapLegality.DestNoVs1Overlap accepts non-overlapping"):
      val assigns = OverlapLegality.assignments(
        Map(OperandRole.Vd -> 1, OperandRole.Vs1 -> 1),
        Map(OperandRole.Vd -> 8, OperandRole.Vs1 -> 16))
      val r = OverlapLegality.check(assigns, List(OverlapRule.DestNoVs1Overlap))
      assert(r.isRight)

    test("OverlapLegality.DestNoVs1Overlap rejects overlapping"):
      val assigns = OverlapLegality.assignments(
        Map(OperandRole.Vd -> 1, OperandRole.Vs1 -> 1),
        Map(OperandRole.Vd -> 8, OperandRole.Vs1 -> 8))
      val r = OverlapLegality.check(assigns, List(OverlapRule.DestNoVs1Overlap))
      assert(r.isLeft)

    test("OverlapLegality.WideningDestSourceOverlap rejects naive overlap"):
      val assigns = OverlapLegality.assignments(
        Map(OperandRole.Vd -> 4, OperandRole.Vs2 -> 2),
        Map(OperandRole.Vd -> 8, OperandRole.Vs2 -> 9)) // 9 is inside 8..11
      val r = OverlapLegality.check(assigns, List(OverlapRule.WideningDestSourceOverlap))
      assert(r.isLeft)

    // ---- Driver (task8) ----

    test("Driver.parseCli rejects missing -stage1output"):
      val r = Driver.parseCli(Array("-VLEN", "256", "-XLEN", "64"))
      assert(r.isLeft)
      assert(r.left.toOption.exists(_.contains("stage1output")))

    test("Driver.parseCli accepts upstream-style flag set"):
      val r = Driver.parseCli(Array(
        "-VLEN", "256", "-XLEN", "64", "-split", "10000",
        "-pattern", "vadd.*", "-stage1output", "/tmp/stage1",
        "-testfloat3level", "2", "-repeat", "1",
        "-march", "rv64gcv"))
      assert(r.isRight)
      val cli = r.toOption.get
      assert(cli.vlen == 256)
      assert(cli.xlen == 64)
      assert(cli.stage1OutputDir == "/tmp/stage1")
      assert(cli.pattern == "vadd.*")

    test("Driver.parseCli silently accepts -configs (DEC-5)"):
      val r = Driver.parseCli(Array("-stage1output", "/tmp/x", "-configs", "configs/"))
      assert(r.isRight)
      assert(r.toOption.get.configsIgnored)

    test("Driver.parseMarchExtensions selects extensions per upstream"):
      val full = Driver.parseMarchExtensions("rv64gcv_zvbb_zfh_zvfh_zvkg")
      assert(full.contains("v"))
      assert(full.contains("zvbb"))
      assert(full.contains("zvfh"))
      assert(full.contains("zvfhmin")) // zvfh implies zvfhmin
      assert(full.contains("zvkg"))
      assert(!full.contains("zvbc"))

    test("Driver.parseMarchExtensions drops `v` for embedded ZVE march"):
      val zve = Driver.parseMarchExtensions("rv64gc_zvbb")
      // No 'v' in base → embedded ZVE mode → 'v' extension dir not included
      assert(!zve.contains("v"))
      assert(zve.contains("zvbb"))

    test("Driver.renderMakefrag is sorted with tests = \\\\ format"):
      val frag = Driver.renderMakefrag(List("c-0", "a-0", "b-0", "a-1"))
      assert(frag.startsWith("tests = \\\n"))
      // Lines must be sorted alphabetically
      val targets = frag.split("\n").drop(1).map(_.trim.stripSuffix("\\").trim).filter(_.nonEmpty).toList
      assert(targets == targets.sorted)
      assert(targets == List("a-0", "a-1", "b-0", "c-0"))

    test("RvvInsn.stageFileName follows dot-to-underscore + ext + split"):
      val insn = RvvInsn(name = "vadd.vv", extension = "v", schema = Schema.VdVs2Vs1Vm)
      assert(RvvInsn.stageFileName(insn, 0) == "vadd_vv_v-0.S")
      assert(RvvInsn.stageFileName(insn, 7) == "vadd_vv_v-7.S")
