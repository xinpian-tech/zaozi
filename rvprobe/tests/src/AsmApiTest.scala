// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.{*, given}

import utest.*

// Tests for the assembly-like API (AsmApi.scala).
// Validates that e.g. addi(1, 1, 1) generates "addi x1 x1 1".
object AsmApiTest extends TestSuite:
  val tests = Tests:

    test("addi(1, 1, 1) generates addi x1 x1 1"):
      object AddiTest extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          addi(1, 1, 1)
      AddiTest.rvprobeTestInstructions("0: addi x1 x1 1")

    test("add(2, 3, 4) generates add x2 x3 x4"):
      object AddTest extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          add(2, 3, 4)
      AddTest.rvprobeTestInstructions("0: add x2 x3 x4")

    test("lui(5, 42) generates lui x5 42"):
      object LuiTest extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          lui(5, 42)
      LuiTest.rvprobeTestInstructions("0: lui x5 42")

    test("multi-instruction auto-index"):
      object MultiTest extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          addi(1, 0, 10)  // idx 0
          addi(2, 0, 20)  // idx 1
          add(3, 1, 2)    // idx 2
      MultiTest.rvprobeTestInstructions(
        "0: addi x1 x0 10",
        "1: addi x2 x0 20",
        "2: add x3 x1 x2"
      )
