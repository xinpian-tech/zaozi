// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.coverage

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*
import me.jiuyang.rvprobe.cases.coverage.CoverageLib.*

import java.nio.file.{Files, Paths}
import scala.util.control.NonFatal

// RV32I base integer instruction coverage (27 instructions)
// Run with: mill rvprobe.runMain me.jiuyang.rvprobe.cases.coverage.RV32I
@main def RV32I(outputPath: String = "out/rv32i.bin"): Unit =
  val n = 35

  // --- Shift-immediate ---
  object Slli extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = shiftImm(n, isSlli())

  object Srai extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = shiftImm(n, isSrai())

  object Srli extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = shiftImm(n, isSrli())

  // --- R-type ---
  object Add extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isAdd())

  object And extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isAnd())

  object Or extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isOr())

  object Sll extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isSll())

  object Slt extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isSlt())

  object Sltu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isSltu())

  object Sra extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isSra())

  object Srl extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isSrl())

  object Sub extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isSub())

  object Xor extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isXor())

  // --- I-type ALU ---
  object Addi extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = iTypeAlu(n, isAddi())

  object Andi extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = iTypeAlu(n, isAndi())

  object Ori extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = iTypeAlu(n, isOri())

  object Slti extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = iTypeAlu(n, isSlti())

  object Sltiu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = iTypeAlu(n, isSltiu())

  object Xori extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = iTypeAlu(n, isXori())

  // --- U-type ---
  object Auipc extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = uType(n, isAuipc())

  object Lui extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = uType(n, isLui())

  // --- Branch ---
  object Beq extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = branch(n, isBeq())

  object Bge extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = branch(n, isBge())

  object Bgeu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = branch(n, isBgeu())

  object Blt extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = branch(n, isBlt())

  object Bltu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = branch(n, isBltu())

  object Bne extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = branch(n, isBne())

  // clear the output file if it exists
  try Files.deleteIfExists(Paths.get(outputPath))
  catch case NonFatal(e) => System.err.println(s"warning: failed to delete ${outputPath}: ${e.getMessage}")

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
