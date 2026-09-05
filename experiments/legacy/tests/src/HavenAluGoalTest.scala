// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** Goal-directed generation: the intent names a destination over the DUT's *outputs* — "the overflow flag is set" —
  * and says nothing about how to reach it, so the solver has to find the operands. This is the arm that separates the
  * two things the harness does: [[HavenAluFpUT]] still has the author choosing operands and the solver only scheduling
  * the handshake around them, whereas here the operand search is the solver's.
  *
  * The reserved-opcode case is the reason this test exists. It pins a soundness property rather than a capability:
  * opcode 15 never launches the FP datapath, so an overflow goal restricted to it must be `Infeasible`. It was not,
  * while the restriction was written as `Assume` on the port — circt-bmc does not enforce an assumption on a port that
  * feeds an `hw.instance` (`docs/date2027/circt-bmc-assume.md`), so the solver launched FP_SUB and parked `op` on 15.
  * Tying the opcode into the DUT instead makes the restriction structural. If anyone moves it back to an assumption,
  * this test fails.
  */
object HavenAluGoalTest extends TestSuite:
  import FormalGenHarness.*

  val tests: Tests = Tests:
    test("a goal over the DUT's outputs is solved into operands, and an unreachable goal is refused"):
      val dir    = freshDir("HavenAlu-goal")
      val svFile = resources / "haven" / "alu_top.v"
      val ip     = SvImport.toHw(Seq(svFile), dir / "imported")

      def solve(g: HavenAluGoalParameter): GenerateOutcome =
        val model  = FormalUT.lowerGenerator(HavenAluGoalUT, g, dir / g.label)
        val merged = SvImport.mergeForBmc(model.hw, ip)
        FormalUT.generate(model.copy(hw = merged), bound = 12)

      // FLAG_OVERFLOW is bit 3, FLAG_UNDERFLOW bit 2.
      val overflow  = solve(HavenAluGoalParameter("ovf_fpadd", flagsMask = 8, flagsValue = 8, opIs = Some(0)))
      val underflow = solve(HavenAluGoalParameter("uf_fpsub", flagsMask = 4, flagsValue = 4, opIs = Some(1)))
      val reserved  = solve(HavenAluGoalParameter("ovf_reserved", flagsMask = 8, flagsValue = 8, opIs = Some(15)))

      for (label, outcome) <- Seq("overflow/FP_ADD" -> overflow, "underflow/FP_SUB" -> underflow) do
        val operands = outcome match
          case GenerateOutcome.Generated(t) =>
            val at = (k: String) => t.values.get(k).flatMap(_.headOption).map(v => f"0x${v}%08x").getOrElse("-")
            s"a=${at("a")} b=${at("b")}"
          case other                        => other.toString
        println(s"  $label%-18s $operands")

      // The solver had to pick two 32-bit floats whose sum overflows, and two whose difference underflows.
      assert(overflow.isInstanceOf[GenerateOutcome.Generated])
      assert(underflow.isInstanceOf[GenerateOutcome.Generated])

      // The soundness half: no operand pair reaches an FP flag through the reserved opcode.
      assert(reserved == GenerateOutcome.Infeasible)
