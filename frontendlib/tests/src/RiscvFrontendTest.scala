// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.frontendlib.tests

import me.jiuyang.frontendlib.*
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.*

import utest.*

object RiscvFrontendTest extends TestSuite:
  val tests: Tests = Tests:
    test("rendering preserves solved fields and statement layout without solving again"):
      object Program extends RVGenerator:
        val sets       = Seq(isRVI())
        var solveCount = 0

        def constraints() =
          section(".text")
          label("entry")
          addi(x1, x0, 42)
          label("next")
          addi(x2, x1, 7)

        override def solveRecipe(): SolvedRecipe =
          solveCount += 1
          super.solveRecipe()

      val frontend = RiscvFrontend(Program)
      val solved   = frontend.solve()
      assert(Program.solveCount == 1)
      assert(solved.sequence.fields("rd_0") == 1)
      assert(solved.sequence.fields("rs1_1") == 1)
      val opcode   = solved.sequence.selections(0)
      assert(frontend.alphabet.kinds.exists(kind => kind.id == opcode && kind.mnemonic == "addi"))

      val rendered = frontend.backend.render(solved)
      assert(
        rendered.linesIterator.map(_.trim).toSeq == Seq(
          ".section .text",
          "entry:",
          "addi x1, x0, 42",
          "next:",
          "addi x2, x1, 7"
        )
      )
      assert(frontend.backend.render(solved) == rendered)
      assert(Program.solveCount == 1)
