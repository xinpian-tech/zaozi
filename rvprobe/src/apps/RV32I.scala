// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.apps

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*

import java.nio.file.{Files, Paths}
import scala.util.control.NonFatal

// Run with: mill rvprobe.runMain me.jiuyang.rvprobe.apps.RV32I
@main def RV32I(outputPath: String = "out/rv32i.bin"): Unit =
  val instructionCount = 35

  object Slli extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isSlliRV64I()) {
          rdRange(1, 32) & rs1Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Srai extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isSraiRV64I()) {
          rdRange(1, 32) & rs1Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Srli extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isSrliRV64I()) {
          rdRange(1, 32) & rs1Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Add extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isAdd()) {
          rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs2, (1 until 32).map(i => i.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Addi extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isAddi()) {
          rdRange(1, 32) & rs1Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.imm12, Seq((-1).S, 0.S, 1.S, (-2048).S, 2047.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object And extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isAnd()) {
          rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs2, (1 until 32).map(i => i.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Andi extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isAndi()) {
          rdRange(1, 32) & rs1Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.imm12, Seq((-1).S, 0.S, 1.S, (-2048).S, 2047.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Auipc extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isAuipc()) {
          rdRange(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.imm20, Seq((-1).S, 0.S, 1.S, (-524288).S, 524287.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Beq extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until 5).foreach { i =>
        instruction(i, isAddi()) {
          rdRange(i + 1, i + 2) & rs1Range(0, 1) & imm12Range(-5, -1)
        }
      }

      (5 until instructionCount + 5).foreach { i =>
        instruction(i, isBeq()) {
          rs1Range(1, 32) & rs2Range(1, 32) & bimm12loRange(4, 5) & bimm12hiRange(0, 1)
        }
      }

      sequence(5, instructionCount + 5).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(5, instructionCount + 5).coverBins(_.rs2, (1 until 32).map(i => i.S))

  object Bge extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until 5).foreach { i =>
        instruction(i, isAddi()) {
          rdRange(i + 1, i + 2) & rs1Range(0, 1) & imm12Range(-5, -1)
        }
      }

      (5 until instructionCount + 5).foreach { i =>
        instruction(i, isBge()) {
          rs1Range(1, 32) & rs2Range(1, 32) & bimm12loRange(4, 5) & bimm12hiRange(0, 1)
        }
      }

      sequence(5, instructionCount + 5).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(5, instructionCount + 5).coverBins(_.rs2, (1 until 32).map(i => i.S))

  object Bgeu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until 5).foreach { i =>
        instruction(i, isAddi()) {
          rdRange(i + 1, i + 2) & rs1Range(0, 1) & imm12Range(-5, -1)
        }
      }

      (5 until instructionCount + 5).foreach { i =>
        instruction(i, isBgeu()) {
          rs1Range(1, 32) & rs2Range(1, 32) & bimm12loRange(4, 5) & bimm12hiRange(0, 1)
        }
      }

      sequence(5, instructionCount + 5).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(5, instructionCount + 5).coverBins(_.rs2, (1 until 32).map(i => i.S))

  object Blt extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until 5).foreach { i =>
        instruction(i, isAddi()) {
          rdRange(i + 1, i + 2) & rs1Range(0, 1) & imm12Range(-5, -1)
        }
      }

      (5 until instructionCount + 5).foreach { i =>
        instruction(i, isBlt()) {
          rs1Range(1, 32) & rs2Range(1, 32) & bimm12loRange(4, 5) & bimm12hiRange(0, 1)
        }
      }

      sequence(5, instructionCount + 5).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(5, instructionCount + 5).coverBins(_.rs2, (1 until 32).map(i => i.S))

  object Bltu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until 5).foreach { i =>
        instruction(i, isAddi()) {
          rdRange(i + 1, i + 2) & rs1Range(0, 1) & imm12Range(-5, -1)
        }
      }

      (5 until instructionCount + 5).foreach { i =>
        instruction(i, isBltu()) {
          rs1Range(1, 32) & rs2Range(1, 32) & bimm12loRange(4, 5) & bimm12hiRange(0, 1)
        }
      }

      sequence(5, instructionCount + 5).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(5, instructionCount + 5).coverBins(_.rs2, (1 until 32).map(i => i.S))

  object Bne extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until 5).foreach { i =>
        instruction(i, isAddi()) {
          rdRange(i + 1, i + 2) & rs1Range(0, 1) & imm12Range(-5, -1)
        }
      }

      (5 until instructionCount + 5).foreach { i =>
        instruction(i, isBne()) {
          rs1Range(1, 32) & rs2Range(1, 32) & bimm12loRange(4, 5) & bimm12hiRange(0, 1)
        }
      }

      sequence(5, instructionCount + 5).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(5, instructionCount + 5).coverBins(_.rs2, (1 until 32).map(i => i.S))

  object Lui extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isLui()) {
          rdRange(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.imm20, Seq((-1).S, 0.S, 1.S, (-524288).S, 524287.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Or extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isOr()) {
          rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs2, (1 until 32).map(i => i.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Ori extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isOri()) {
          rdRange(1, 32) & rs1Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.imm12, Seq((-1).S, 0.S, 1.S, (-2048).S, 2047.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Sll extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isSll()) {
          rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs2, (1 until 32).map(i => i.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Slt extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isSlt()) {
          rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs2, (1 until 32).map(i => i.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Slti extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isSlti()) {
          rdRange(1, 32) & rs1Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.imm12, Seq((-1).S, 0.S, 1.S, (-2048).S, 2047.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Sltiu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isSltiu()) {
          rdRange(1, 32) & rs1Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.imm12, Seq((-1).S, 0.S, 1.S, (-2048).S, 2047.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Sltu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isSltu()) {
          rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs2, (1 until 32).map(i => i.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Sra extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isSra()) {
          rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs2, (1 until 32).map(i => i.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Srl extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isSrl()) {
          rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs2, (1 until 32).map(i => i.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Sub extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isSub()) {
          rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs2, (1 until 32).map(i => i.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Xor extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isXor()) {
          rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs2, (1 until 32).map(i => i.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  object Xori extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until instructionCount).foreach { i =>
        instruction(i, isXori()) {
          rdRange(1, 32) & rs1Range(1, 32)
        }
      }

      sequence(0, instructionCount).coverBins(_.rd, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.rs1, (1 until 32).map(i => i.S))
      sequence(0, instructionCount).coverBins(_.imm12, Seq((-1).S, 0.S, 1.S, (-2048).S, 2047.S))

      sequence(0, instructionCount).coverRAW()
      sequence(0, instructionCount).coverWAR()
      sequence(0, instructionCount).coverWAW()
      sequence(0, instructionCount).coverNoHazard()

  // clear the output file if it exists
  try Files.deleteIfExists(Paths.get(outputPath))
  catch
    case NonFatal(e) =>
      System.err.println(s"warning: failed to delete ${outputPath}: ${e.getMessage}")

  // write instructions to the output file
  Slli.writeInstructionsToFile(outputPath)
  Srai.writeInstructionsToFile(outputPath)
  Srli.writeInstructionsToFile(outputPath)
  Add.writeInstructionsToFile(outputPath)
  Addi.writeInstructionsToFile(outputPath)
  And.writeInstructionsToFile(outputPath)
  Andi.writeInstructionsToFile(outputPath)
  Auipc.writeInstructionsToFile(outputPath)
  Beq.writeInstructionsToFile(outputPath)
  Bge.writeInstructionsToFile(outputPath)
  Bgeu.writeInstructionsToFile(outputPath)
  Blt.writeInstructionsToFile(outputPath)
  Bltu.writeInstructionsToFile(outputPath)
  Bne.writeInstructionsToFile(outputPath)
  Lui.writeInstructionsToFile(outputPath)
  Or.writeInstructionsToFile(outputPath)
  Ori.writeInstructionsToFile(outputPath)
  Sll.writeInstructionsToFile(outputPath)
  Slt.writeInstructionsToFile(outputPath)
  Slti.writeInstructionsToFile(outputPath)
  Sltiu.writeInstructionsToFile(outputPath)
  Sltu.writeInstructionsToFile(outputPath)
  Sra.writeInstructionsToFile(outputPath)
  Srl.writeInstructionsToFile(outputPath)
  Sub.writeInstructionsToFile(outputPath)
  Xor.writeInstructionsToFile(outputPath)
  Xori.writeInstructionsToFile(outputPath)
