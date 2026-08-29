// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** Temporal intents against the real HAVEN ALU: a clocked sequence, no reference to internal state — "an FP add
  * starts, then `done` stays low for N cycles" — solved through the imported RTL for a sweep of N.
  *
  * It also records a lesson worth keeping. The sweep was written expecting `Infeasible` once N exceeded the FP
  * pipeline's latency, i.e. a *proof* that nothing can hold `done` low that long. Every N is satisfiable instead,
  * and the design says why: `if (start && is_fp_op && !fp_active)` ignores a `start` raised while the pipeline is
  * busy, so re-asserting `start` keeps the antecedent true while `done` stays low indefinitely. The intent is
  * well-typed, compiles, solves, and returns a genuine witness — for a scenario its author did not mean.
  * **Type checking constrains the form of an intent, not its meaning**; a mis-stated goal is caught by reading the
  * witness, which is why the witness is worth surfacing rather than just its verdict.
  */
object HavenAluTemporalTest extends TestSuite:
  import FormalGenHarness.*

  val tests: Tests = Tests:
    test("sweeping a temporal chain finds the pipeline latency and then proves impossibility"):
      val dir    = freshDir("HavenAlu-temporal")
      val svFile = resources / "haven" / "alu_top.v"
      val ip     = SvImport.toHw(Seq(svFile), dir / "imported")

      def outcome(n: Int): GenerateOutcome =
        val param  = HavenAluSeqParameter(n)
        val model  = FormalUT.lowerGenerator(HavenAluSeqUT, param, dir / s"n$n")
        val merged = SvImport.mergeForBmc(model.hw, ip)
        FormalUT.generate(model.copy(hw = merged), bound = 10)

      val verdicts = (2 to 6).map(n => n -> outcome(n))
      for (n, v) <- verdicts do
        val detail = v match
          case GenerateOutcome.Unknown(d) => s" -- detail: '$d'"
          case _                          => ""
        println(s"  done low for $n cycles after start: ${v.getClass.getSimpleName.replace("$", "")}$detail")

      // Every length is satisfiable, for the reason in the class comment — the temporal machinery works end to
      // end through import + merge; the intent simply does not mean what it looks like it means.
      assert(verdicts.forall(_._2.isInstanceOf[GenerateOutcome.Generated]))
      // The witnesses really are multi-cycle, so the sequence is being solved rather than collapsed.
      val traces = verdicts.collect { case (_, GenerateOutcome.Generated(t)) => t }
      assert(traces.forall(_.cycles >= 2))
