// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*

import utest.*
import scala.collection.View.Single

object RecipeTest extends TestSuite:
  val tests = Tests:
    test("SingleInstruction"):
      object SingleInstruction extends RVGenerator with HasRVProbeTest:
        val sets          = Seq(isRV64I())
        def constraints() =
          instruction(0, isAddw()) {
            rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
          }

      // Stage 1: opcode solving is satisfiable
      SingleInstruction.rvprobeTestOpcodeZ3Output("sat")

      // Stage 2: arg solving is satisfiable
      SingleInstruction.rvprobeTestArgZ3Output("sat")

      // End-to-end: generates an addw instruction with registers in [1,31]
      val instructions = SingleInstruction.toInstructions()
      assert(instructions.length == 1)
      val instrStr = instructions.head._2
      assert(instrStr.startsWith("0: addw "))
      val parts = instrStr.split(" ")
      // parts: ["0:", "addw", "x1", "x2", "x3"]
      // Register operands are at indices 2, 3, 4
      (2 to 4).foreach { i =>
        val reg = parts(i).stripPrefix("x").toInt
        assert(reg >= 1 && reg < 32)
      }

    test("MultiInstructions"):
      object MultiInstructions extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          (0 until 50).foreach { i =>
            instruction(i, isAddw()) {
              rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
            }
          }
      MultiInstructions.rvprobeTestInstructions("""
                                                  |0: addw x1 x1 x1
                                                  |1: addw x1 x1 x1
                                                  |2: addw x1 x1 x1
                                                  |3: addw x1 x1 x1
                                                  |4: addw x1 x1 x1
                                                  |5: addw x1 x1 x1
                                                  |6: addw x1 x1 x1
                                                  |7: addw x1 x1 x1
                                                  |8: addw x1 x1 x1
                                                  |9: addw x1 x1 x1
                                                  |10: addw x1 x1 x1
                                                  |11: addw x1 x1 x1
                                                  |12: addw x1 x1 x1
                                                  |13: addw x1 x1 x1
                                                  |14: addw x1 x1 x1
                                                  |15: addw x1 x1 x1
                                                  |16: addw x1 x1 x1
                                                  |17: addw x1 x1 x1
                                                  |18: addw x1 x1 x1
                                                  |19: addw x1 x1 x1
                                                  |20: addw x1 x1 x1
                                                  |21: addw x1 x1 x1
                                                  |22: addw x1 x1 x1
                                                  |23: addw x1 x1 x1
                                                  |24: addw x1 x1 x1
                                                  |25: addw x1 x1 x1
                                                  |26: addw x1 x1 x1
                                                  |27: addw x1 x1 x1
                                                  |28: addw x1 x1 x1
                                                  |29: addw x1 x1 x1
                                                  |30: addw x1 x1 x1
                                                  |31: addw x1 x1 x1
                                                  |32: addw x1 x1 x1
                                                  |33: addw x1 x1 x1
                                                  |34: addw x1 x1 x1
                                                  |35: addw x1 x1 x1
                                                  |36: addw x1 x1 x1
                                                  |37: addw x1 x1 x1
                                                  |38: addw x1 x1 x1
                                                  |39: addw x1 x1 x1
                                                  |40: addw x1 x1 x1
                                                  |41: addw x1 x1 x1
                                                  |42: addw x1 x1 x1
                                                  |43: addw x1 x1 x1
                                                  |44: addw x1 x1 x1
                                                  |45: addw x1 x1 x1
                                                  |46: addw x1 x1 x1
                                                  |47: addw x1 x1 x1
                                                  |48: addw x1 x1 x1
                                                  |49: addw x1 x1 x1
                                                  |""".stripMargin.split('\n').toSeq*)

    test("ComplexRecipe"):
      object ComplexRecipe extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          val instructionCount = 50
          (0 until instructionCount).foreach { i =>
            instruction(i, isAddw()) {
              rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
            }
          }

          sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
          sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
          sequence(0, instructionCount).coverBins(_.rs2, (1 until 32).map(i => i.S))

      // Verify: 50 addw instructions, all registers in [1,31], cover bins satisfied (x1-x31 all appear)
      val instructions = ComplexRecipe.toInstructions()
      assert(instructions.length == 50)

      val rdSet  = scala.collection.mutable.Set[Int]()
      val rs1Set = scala.collection.mutable.Set[Int]()
      val rs2Set = scala.collection.mutable.Set[Int]()

      instructions.foreach { case (_, instrStr) =>
        assert(instrStr.contains("addw"))
        val parts = instrStr.split("\\s+")
        val rd  = parts(2).stripPrefix("x").toInt
        val rs1 = parts(3).stripPrefix("x").toInt
        val rs2 = parts(4).stripPrefix("x").toInt
        assert(rd >= 1 && rd < 32)
        assert(rs1 >= 1 && rs1 < 32)
        assert(rs2 >= 1 && rs2 < 32)
        rdSet += rd; rs1Set += rs1; rs2Set += rs2
      }

      // Cover bins: all registers x1-x31 should appear in each field
      val expected = (1 to 31).toSet
      assert(rdSet == expected)
      assert(rs1Set == expected)
      assert(rs2Set == expected)
