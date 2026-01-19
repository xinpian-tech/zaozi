// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*

import utest.*

object NopTest extends TestSuite:
  val tests = Tests:
    test("ContiguousIndices - no NOP inserted"):
      // When indices are contiguous (0, 1, 2), no NOP should be inserted
      object ContiguousInstructions extends RVGenerator with HasRVProbeTest:
        val sets          = Seq(isRV64I())
        def constraints() =
          instruction(0, isAddw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
          instruction(1, isSubw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
          instruction(2, isAddw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }

      ContiguousInstructions.rvprobeTestInstructions("0: addw x1 x1 x1", "1: subw x1 x1 x1", "2: addw x1 x1 x1")

    test("NonContiguousIndices - NOP inserted for gaps"):
      // When indices have gaps (0, 3), NOP should be inserted at positions 1 and 2
      object NonContiguousInstructions extends RVGenerator with HasRVProbeTest:
        val sets          = Seq(isRV64I())
        def constraints() =
          instruction(0, isAddw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
          instruction(3, isSubw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }

      NonContiguousInstructions.rvprobeTestInstructions(
        "0: addw x1 x1 x1",
        "1: nop",
        "2: nop",
        "3: subw x1 x1 x1"
      )

    test("NOP encoding verification"):
      // Verify NOP has correct encoding: ADDI x0, x0, 0 = 0x00000013
      object NopEncodingTest extends RVGenerator with HasRVProbeTest:
        val sets          = Seq(isRV64I())
        def constraints() =
          instruction(0, isAddw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
          instruction(2, isSubw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }

      val instructions   = NopEncodingTest.toInstructions()
      // NOP should be at position 1
      val nopInstruction = instructions(1)
      val nopBytes       = nopInstruction._1

      // NOP encoding is 0x00000013 in little-endian
      assert(nopBytes(0) == 0x13.toByte)
      assert(nopBytes(1) == 0x00.toByte)
      assert(nopBytes(2) == 0x00.toByte)
      assert(nopBytes(3) == 0x00.toByte)

    test("MultipleGaps - NOP inserted for each gap"):
      // When there are multiple gaps (0, 2, 5), NOP should be inserted appropriately
      object MultipleGapsInstructions extends RVGenerator with HasRVProbeTest:
        val sets          = Seq(isRV64I())
        def constraints() =
          instruction(0, isAddw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
          instruction(2, isSubw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
          instruction(5, isAddw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }

      MultipleGapsInstructions.rvprobeTestInstructions(
        "0: addw x1 x1 x1",
        "1: nop (addi x0, x0, 0)",
        "2: subw x1 x1 x1",
        "3: nop (addi x0, x0, 0)",
        "4: nop (addi x0, x0, 0)",
        "5: addw x1 x1 x1"
      )

    test("SingleInstruction - no NOP needed"):
      // Single instruction should not have any NOP
      object SingleInstruction extends RVGenerator with HasRVProbeTest:
        val sets          = Seq(isRV64I())
        def constraints() =
          instruction(5, isAddw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }

      SingleInstruction.rvprobeTestInstructions("0: nop", "1: nop", "2: nop", "3: nop", "4: nop", "5: addw x1 x1 x1")
