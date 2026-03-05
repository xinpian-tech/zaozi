// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*

import utest.*

object TemplateTest extends TestSuite:
  val tests = Tests:
    test("toRecipeAsm - R-type uses GAS comma format"):
      object RTypeRecipe extends RVGenerator with HasRVProbeTest:
        val sets          = Seq(isRV64I())
        def constraints() =
          instruction(0, isAddw()) {
            rdRange(1, 5) & rs1Range(1, 5) & rs2Range(1, 5)
          }

      // GAS format: "addw x<n>, x<m>, x<k>" — commas, no index prefix
      RTypeRecipe.rvprobeTestRecipeAsm("addw x", ",")

    test("toRecipeAsm - NOP padding present for non-contiguous indices"):
      object GapRecipe extends RVGenerator with HasRVProbeTest:
        val sets          = Seq(isRV64I())
        def constraints() =
          instruction(0, isAddw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
          instruction(2, isSubw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }

      GapRecipe.rvprobeTestRecipeAsm("addw x", "nop", "subw x")

    test("toAssemblyFile - {{recipe}} placeholder is substituted"):
      object templatedGen extends RVGenerator with HasRVProbeTest:
        val sets = Seq(isRV64I(), isRVI())

        def constraints() =
          instruction(0, isAddw()) {
            rdRange(1, 5)
          }

        override def template() =
          """|    .section .text
             |    .globl  _start
             |_start:
             |    la      t0, trap_handler
             |    csrw    mtvec, t0
             |
             |recipe_code:
             |  {{recipe}}
             |
             |trap_handler:
             |    j       trap_handler
             |""".stripMargin

      templatedGen.rvprobeTestAssemblyFile(
        ".section .text",
        ".globl  _start",
        "recipe_code:",
        "trap_handler:",
        "addw x"
      )

    test("toAssemblyFile - empty template returns bare recipe lines"):
      object bareGen extends RVGenerator with HasRVProbeTest:
        val sets          = Seq(isRV64I())
        def constraints() =
          instruction(0, isAddw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
        // template() not overridden → default ""

      bareGen.rvprobeTestAssemblyFile("addw x")

    test("rvprobeTestAssemblyFile helper works"):
      object helperGen extends RVGenerator with HasRVProbeTest:
        val sets          = Seq(isRV64I(), isRVI())
        def constraints() =
          instruction(0, isAddw()) { rdRange(1, 5) }

        override def template() =
          """|    .section .text
             |recipe_code:
             |{{recipe}}
             |""".stripMargin

      helperGen.rvprobeTestAssemblyFile(".section .text", "recipe_code:", "addw x")
