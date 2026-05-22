// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.pred.*
import me.jiuyang.rvprobe.rvv.vtype.Sew

import utest.*

object PredicateClassifierTest extends TestSuite:

  val tests = Tests:

    // ---- ValuePred.classify ----

    test("ValuePred.classify Zero at any SEW"):
      assert(ValuePred.classify(BigInt(0), Sew.Sew8).contains(ValuePred.Zero))
      assert(ValuePred.classify(BigInt(0), Sew.Sew64).contains(ValuePred.Zero))

    test("ValuePred.classify MaxSigned at each SEW"):
      assert(ValuePred.classify(BigInt(0x7f), Sew.Sew8).contains(ValuePred.MaxSigned(Sew.Sew8)))
      assert(ValuePred.classify(BigInt(0x7fff), Sew.Sew16).contains(ValuePred.MaxSigned(Sew.Sew16)))
      assert(ValuePred.classify(BigInt(0x7fffffffL), Sew.Sew32).contains(ValuePred.MaxSigned(Sew.Sew32)))

    test("ValuePred.classify MinSigned == SignBitOnly"):
      val ps = ValuePred.classify(BigInt(0x80), Sew.Sew8)
      assert(ps.contains(ValuePred.MinSigned(Sew.Sew8)))
      assert(ps.contains(ValuePred.SignBitOnly(Sew.Sew8)))

    test("ValuePred.classify AllOnes implies MinusOne and MaxUnsigned"):
      val ps = ValuePred.classify(BigInt(0xff), Sew.Sew8)
      assert(ps.contains(ValuePred.AllOnes(Sew.Sew8)))
      assert(ps.contains(ValuePred.MinusOne))
      assert(ps.contains(ValuePred.MaxUnsigned(Sew.Sew8)))

    test("ValuePred.classify SmallSigned for arithmetic edge cases"):
      // -8 (0xf8 in sew8) is SmallSigned(-8), not NearMinSigned
      val ps = ValuePred.classify(BigInt(0xf8), Sew.Sew8)
      assert(ps.contains(ValuePred.SmallSigned(-8)))

    test("ValuePred.classify NearMaxSigned for offsets <= 16"):
      // 0x7f - 7 = 0x78 in sew8 should be NearMaxSigned(Sew8, 7)
      val ps = ValuePred.classify(BigInt(0x78), Sew.Sew8)
      assert(ps.contains(ValuePred.NearMaxSigned(Sew.Sew8, 7)))

    test("ValuePred.classify BitPattern for 1/7 fixed-point"):
      val ps = ValuePred.classify(BigInt("6db6db6db6db6db7", 16), Sew.Sew64)
      assert(ps.exists(_.isInstanceOf[ValuePred.BitPattern]))

    test("ValuePred.classify unrecognized → Lit with rationale"):
      val ps = ValuePred.classify(BigInt(0xab), Sew.Sew8)
      assert(ps.size == 1)
      assert(ps.head.isInstanceOf[ValuePred.Lit])

    // ---- TuplePred.classify ----

    test("TuplePred.classify AllZero"):
      val ps = TuplePred.classify(List(BigInt(0), BigInt(0)), Sew.Sew8, ClassifyHint.Generic)
      assert(ps.contains(TuplePred.AllZero))

    test("TuplePred.classify MaxPlusOne with Add hint"):
      val ps = TuplePred.classify(List(BigInt(0x7f), BigInt(1)), Sew.Sew8, ClassifyHint.Add)
      assert(ps.contains(TuplePred.MaxPlusOne(Sew.Sew8)))

    test("TuplePred.classify AllOnesPlusAllOnes with Add hint"):
      val ps = TuplePred.classify(List(BigInt(0xff), BigInt(0xff)), Sew.Sew8, ClassifyHint.Add)
      assert(ps.contains(TuplePred.AllOnesPlusAllOnes(Sew.Sew8)))

    test("TuplePred.classify DivByZero with Divide hint"):
      val ps = TuplePred.classify(List(BigInt(0x80), BigInt(0)), Sew.Sew8, ClassifyHint.Divide)
      assert(ps.contains(TuplePred.DivByZero(Sew.Sew8)))
      assert(ps.contains(TuplePred.MinSignedDivZero(Sew.Sew8)))

    test("TuplePred.classify ShiftBySewMinus1 with Shift hint"):
      // for sew8, SEW-1 = 7
      val ps = TuplePred.classify(List(BigInt(7), BigInt(0xff)), Sew.Sew8, ClassifyHint.Shift)
      assert(ps.contains(TuplePred.ShiftBySewMinus1(Sew.Sew8)))

    test("TuplePred.classify ShiftBySewOrAbove with Shift hint"):
      val ps = TuplePred.classify(List(BigInt(0x1f), BigInt(0xff)), Sew.Sew8, ClassifyHint.Shift)
      assert(ps.contains(TuplePred.ShiftBySewOrAbove(Sew.Sew8)))

    test("TuplePred.classify hint-gated predicates do not fire under Generic"):
      // (0x7f, 1) is MaxPlusOne under Add; under Generic it's just classified by shape
      val genericPs = TuplePred.classify(List(BigInt(0x7f), BigInt(1)), Sew.Sew8, ClassifyHint.Generic)
      assert(!genericPs.contains(TuplePred.MaxPlusOne(Sew.Sew8)))

    test("TuplePred.classify unrecognized → Lit with rationale"):
      val ps = TuplePred.classify(List(BigInt(0xab), BigInt(0x7d)), Sew.Sew8, ClassifyHint.Generic)
      assert(ps.exists(_.isInstanceOf[TuplePred.Lit]))

    // ---- FpValuePred.classify ----

    test("FpValuePred.classify named FP tokens"):
      assert(FpValuePred.classify("smallest_normal_float").contains(FpValuePred.SmallestNormal))
      assert(FpValuePred.classify("-smallest_normal_float").contains(FpValuePred.NegSmallestNormal))
      assert(FpValuePred.classify("max_float").contains(FpValuePred.MaxFinite))
      assert(FpValuePred.classify("nan").contains(FpValuePred.Nan))
      assert(FpValuePred.classify("quiet_nan").contains(FpValuePred.QuietNan))
      assert(FpValuePred.classify("signaling_nan").contains(FpValuePred.SignalingNan))
      assert(FpValuePred.classify("inf").contains(FpValuePred.PosInf))
      assert(FpValuePred.classify("-inf").contains(FpValuePred.NegInf))
      assert(FpValuePred.classify("0.0").contains(FpValuePred.PosZero))
      assert(FpValuePred.classify("-0.0").contains(FpValuePred.NegZero))

    test("FpValuePred.classify decimal tokens → FpDecimal with rationale"):
      val ps = FpValuePred.classify("2.5")
      assert(ps.size == 1)
      assert(ps.head.isInstanceOf[FpValuePred.FpDecimal])

    test("FpValuePred.classify unknown token → FpLit"):
      val ps = FpValuePred.classify("not_a_real_fp_token")
      assert(ps.size == 1)
      assert(ps.head.isInstanceOf[FpValuePred.FpLit])

    // ---- FpTuplePred.classify ----

    test("FpTuplePred.classify NaNPair from named nan + neg nan"):
      val row = List(FpValuePred.Nan, FpValuePred.NegNan)
      assert(FpTuplePred.classify(row).contains(FpTuplePred.NaNPair))

    test("FpTuplePred.classify QuietVsSignalingNan"):
      val row = List(FpValuePred.QuietNan, FpValuePred.SignalingNan)
      assert(FpTuplePred.classify(row).contains(FpTuplePred.QuietVsSignalingNan))

    test("FpTuplePred.classify InfPair"):
      val row = List(FpValuePred.PosInf, FpValuePred.NegInf)
      assert(FpTuplePred.classify(row).contains(FpTuplePred.InfPair))

    test("FpTuplePred.classify SubnormalBoundary"):
      val row = List(FpValuePred.SmallestNonzero, FpValuePred.LargestSubnormal)
      assert(FpTuplePred.classify(row).contains(FpTuplePred.SubnormalBoundary))

    test("FpTuplePred.classify NormalPair for two decimals"):
      val a   = FpValuePred.FpDecimal("2.5", "rationale")
      val b   = FpValuePred.FpDecimal("1.0", "rationale")
      val row = List(a, b)
      assert(FpTuplePred.classify(row).contains(FpTuplePred.NormalPair))

    // ---- Case-name tables stay in sync with enum ----

    test("ValuePred.caseNames length matches expected"):
      assert(ValuePred.caseNames.size == 13)

    test("TuplePred.caseNames length matches expected"):
      assert(TuplePred.caseNames.size == 17)

    test("FpValuePred.caseNames length matches expected"):
      assert(FpValuePred.caseNames.size == 18)

    test("FpTuplePred.caseNames length matches expected"):
      assert(FpTuplePred.caseNames.size == 8)
