// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}

import utest.*

object StatementTest extends TestSuite:
  val tests = Tests:

    test("section and global directives appear in output"):
      object DirectiveTest extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          section(".text")
          global("_start")
          label("_start")
          addi(x1, x0, 42)
      DirectiveTest.rvprobeTestRecipeAsm(
        ".section .text",
        ".globl _start",
        "_start:",
        "addi x1, x0, 42"
      )

    test("label between instructions"):
      object LabelTest extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          addi(x1, x0, 10)
          label("loop")
          add(x2, x1, x1)
      LabelTest.rvprobeTestRecipeAsm(
        "addi x1, x0, 10",
        "loop:",
        "add x2, x1, x1"
      )

    test("align directive"):
      object AlignTest extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          align(4)
          label("data")
          addi(x1, x0, 0)
      AlignTest.rvprobeTestRecipeAsm(
        ".align 4",
        "data:",
        "addi x1, x0, 0"
      )

    test("full program structure"):
      object FullTest extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          section(".text")
          global("_start")
          label("_start")
          addi(x1, x0, 10)
          addi(x2, x0, 20)
          add(x3, x1, x2)
          label("trap_handler")
          raw("    j trap_handler")
      FullTest.rvprobeTestRecipeAsm(
        ".section .text",
        ".globl _start",
        "_start:",
        "addi x1, x0, 10",
        "addi x2, x0, 20",
        "add x3, x1, x2",
        "trap_handler:",
        "    j trap_handler"
      )
