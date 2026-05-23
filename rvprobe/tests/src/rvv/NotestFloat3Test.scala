// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.notestfloat3.{Vfredusum, Vfwredusum}
import me.jiuyang.rvprobe.rvv.pred.{FpTuplePred, FpValuePred}
import me.jiuyang.rvprobe.rvv.vtype.Sew

import utest.*

object NotestFloat3Test extends TestSuite:

  val tests = Tests:

    // ---- Per-SEW case count matches upstream ----

    test("Vfredusum has 9 cases per SEW (sew16/sew32/sew64), 27 total"):
      assert(Vfredusum.casesAt(Sew.Sew16).size == 9)
      assert(Vfredusum.casesAt(Sew.Sew32).size == 9)
      assert(Vfredusum.casesAt(Sew.Sew64).size == 9)
      assert(Vfredusum.cases.size == 27)

    test("Vfwredusum has 9 cases per SEW (sew16/sew32/sew64), 27 total"):
      assert(Vfwredusum.casesAt(Sew.Sew16).size == 9)
      assert(Vfwredusum.casesAt(Sew.Sew32).size == 9)
      assert(Vfwredusum.casesAt(Sew.Sew64).size == 9)
      assert(Vfwredusum.cases.size == 27)

    test("combined notestfloat3 case count is 54 (Codex r4 audit corpus)"):
      assert(Vfredusum.cases.size + Vfwredusum.cases.size == 54)

    // ---- Every FP token classifies via FpValuePred (no FpLit fallback) ----

    test("Vfredusum.allTokensNamed (every token maps to a named FpValuePred)"):
      assert(Vfredusum.allTokensNamed)

    test("Vfwredusum.allTokensNamed (every token maps to a named FpValuePred)"):
      assert(Vfwredusum.allTokensNamed)

    // ---- FpTuplePred coverage on at least one representative case ----

    test("Vfredusum contains a NaNPair case"):
      val pairs = Vfredusum.cases.map(c => FpTuplePred.classify(c.classifyTokens.flatten))
      assert(pairs.exists(_.contains(FpTuplePred.NaNPair)))

    test("Vfredusum contains a QuietVsSignalingNan case"):
      val pairs = Vfredusum.cases.map(c => FpTuplePred.classify(c.classifyTokens.flatten))
      assert(pairs.exists(_.contains(FpTuplePred.QuietVsSignalingNan)))

    test("Vfredusum contains an InfPair case"):
      val pairs = Vfredusum.cases.map(c => FpTuplePred.classify(c.classifyTokens.flatten))
      assert(pairs.exists(_.contains(FpTuplePred.InfPair)))

    test("Vfredusum contains a SubnormalBoundary case"):
      val pairs = Vfredusum.cases.map(c => FpTuplePred.classify(c.classifyTokens.flatten))
      assert(pairs.exists(_.contains(FpTuplePred.SubnormalBoundary)))

    test("Vfwredusum contains a NaNPair case"):
      val pairs = Vfwredusum.cases.map(c => FpTuplePred.classify(c.classifyTokens.flatten))
      assert(pairs.exists(_.contains(FpTuplePred.NaNPair)))

    // ---- Schema reference is the canonical 4-operand vector format ----

    test("Vfredusum schema is VdVs2Vs1Vm"):
      assert(Vfredusum.schema.formatString == "vd,vs2,vs1,vm")

    test("Vfwredusum schema is VdVs2Vs1Vm"):
      assert(Vfwredusum.schema.formatString == "vd,vs2,vs1,vm")

    // ---- Each case carries a non-empty rationale (audit hygiene) ----

    test("every Vfredusum case has a non-empty rationale"):
      assert(Vfredusum.cases.forall(_.rationale.nonEmpty))

    test("every Vfwredusum case has a non-empty rationale"):
      assert(Vfwredusum.cases.forall(_.rationale.nonEmpty))

    // ---- Tokens align with the upstream toml (2-token reduction tuples) ----

    test("every case carries exactly 2 tokens (vfredusum.vs is a 2-operand reduction)"):
      assert(Vfredusum.cases.forall(_.tokens.size == 2))
      assert(Vfwredusum.cases.forall(_.tokens.size == 2))
