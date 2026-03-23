// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.coverage

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.tpe.*
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*
import me.jiuyang.rvprobe.cases.HTIFLib

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

/** Reusable coverage patterns for instruction-level constraint tests.
  *
  * These helpers capture common instruction/coverage boilerplate so individual test generators only need to specify
  * what's unique (the opcode and which fields to cover).
  */
object CoverageLib:
  private val CoverageSectionSeparator = "\n\n"
  private val BaremetalPreamble = HTIFLib.asmTextStart()
  private val BaremetalEpilogue =
    s"""${HTIFLib.asmExit()}
       |
       |${HTIFLib.asmTohostSection()}""".stripMargin

  def writeCoverageAsm(
    outputPath: String,
    generators: RVGenerator*
  ): Unit =
    val body = generators.map(_.toRecipeAsm().trim).mkString(CoverageSectionSeparator)
    val content =
      s"""$BaremetalPreamble
         |
         |$body
         |
         |$BaremetalEpilogue
         |""".stripMargin
    os.write.over(os.Path(outputPath, os.pwd), content, createFolders = true)

  /** All non-zero registers: x1..x31 */
  def allRegs(
    using Arena,
    Context,
    Block
  ): Seq[Const[SInt]] = (1 until 32).map(_.S)

  /** Common immediate boundary values for 12-bit signed immediates. */
  def immBoundary12(
    using Arena,
    Context,
    Block
  ): Seq[Const[SInt]] = Seq((-2048).S, (-1).S, 0.S, 1.S, 2047.S)

  /** Common immediate boundary values for 20-bit signed immediates. */
  def immBoundary20(
    using Arena,
    Context,
    Block
  ): Seq[Const[SInt]] = Seq((-524288).S, (-1).S, 0.S, 1.S, 524287.S)

  /** R-type instruction coverage: 3-register (rd, rs1, rs2) with full register bins + all hazard types.
    *
    * @param n
    *   number of instructions to emit
    * @param opcode
    *   the instruction constraint (e.g. isAdd())
    */
  def rType(
    n:      Int,
    opcode: (Arena, Context, Block, Index, Recipe) ?=> InstConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    (0 until n).foreach { i =>
      instruction(i, opcode) {
        rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
      }
    }
    val seq = sequence(0, n)
    seq.coverBins(_.rd, allRegs)
    seq.coverBins(_.rs1, allRegs)
    seq.coverBins(_.rs2, allRegs)
    seq.coverRAW()
    seq.coverWAR()
    seq.coverWAW()
    seq.coverNoHazard()

  /** I-type ALU instruction coverage: 2-register + imm12 with register bins, imm boundary bins + hazards.
    *
    * @param n
    *   number of instructions to emit
    * @param opcode
    *   the instruction constraint (e.g. isAddi())
    */
  def iTypeAlu(
    n:      Int,
    opcode: (Arena, Context, Block, Index, Recipe) ?=> InstConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    (0 until n).foreach { i =>
      instruction(i, opcode) {
        rdRange(1, 32) & rs1Range(1, 32)
      }
    }
    val seq = sequence(0, n)
    seq.coverBins(_.rd, allRegs)
    seq.coverBins(_.rs1, allRegs)
    seq.coverBins(_.imm12, immBoundary12)
    seq.coverRAW()
    seq.coverWAR()
    seq.coverWAW()
    seq.coverNoHazard()

  /** Shift-immediate instruction coverage: 2-register (rd, rs1) with register bins + hazards (no imm bins).
    *
    * Suitable for slli, srli, srai, slliw, srliw, sraiw where shamt is a small unsigned value.
    */
  def shiftImm(
    n:      Int,
    opcode: (Arena, Context, Block, Index, Recipe) ?=> InstConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    (0 until n).foreach { i =>
      instruction(i, opcode) {
        rdRange(1, 32) & rs1Range(1, 32)
      }
    }
    val seq = sequence(0, n)
    seq.coverBins(_.rd, allRegs)
    seq.coverBins(_.rs1, allRegs)
    seq.coverRAW()
    seq.coverWAR()
    seq.coverWAW()
    seq.coverNoHazard()

  /** U-type instruction coverage: rd only with register bins + imm20 boundary bins + hazards.
    *
    * Suitable for lui, auipc.
    */
  def uType(
    n:      Int,
    opcode: (Arena, Context, Block, Index, Recipe) ?=> InstConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    (0 until n).foreach { i =>
      instruction(i, opcode) {
        rdRange(1, 32)
      }
    }
    val seq = sequence(0, n)
    seq.coverBins(_.rd, allRegs)
    seq.coverBins(_.imm20, immBoundary20)
    seq.coverRAW()
    seq.coverWAR()
    seq.coverWAW()
    seq.coverNoHazard()

  /** Branch instruction coverage: prologue of addi instructions to set up registers, then branch instructions.
    *
    * @param n
    *   number of branch instructions
    * @param opcode
    *   the branch constraint (e.g. isBeq())
    * @param prologueCount
    *   number of setup addi instructions (default 5)
    */
  def branch(
    n:             Int,
    opcode:        (Arena, Context, Block, Index, Recipe) ?=> InstConstraint,
    prologueCount: Int = 5
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    (0 until prologueCount).foreach { i =>
      instruction(i, isAddi()) {
        rdRange(i + 1, i + 2) & rs1Range(0, 1) & imm12Range(-5, -1)
      }
    }
    (prologueCount until n + prologueCount).foreach { i =>
      instruction(i, opcode) {
        rs1Range(1, 32) & rs2Range(1, 32) & bimm12loRange(4, 5) & bimm12hiRange(0, 1)
      }
    }
    val seq = sequence(prologueCount, n + prologueCount)
    seq.coverBins(_.rs1, allRegs)
    seq.coverBins(_.rs2, allRegs)

  /** Load instruction coverage: rd + rs1 register bins, imm12 boundary bins + RAW/noHazard.
    *
    * @param n
    *   number of instructions
    * @param opcode
    *   load constraint (e.g. isLw())
    */
  def load(
    n:      Int,
    opcode: (Arena, Context, Block, Index, Recipe) ?=> InstConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    (0 until n).foreach { i =>
      instruction(i, opcode) {
        rdRange(1, 32) & rs1Range(1, 32)
      }
    }
    val seq = sequence(0, n)
    seq.coverBins(_.rd, allRegs)
    seq.coverBins(_.rs1, allRegs)
    seq.coverBins(_.imm12, immBoundary12)
    seq.coverRAW()
    seq.coverNoHazard()

  /** Store instruction coverage: rs1 + rs2 register bins + noHazard.
    *
    * @param n
    *   number of instructions
    * @param opcode
    *   store constraint (e.g. isSw())
    */
  def store(
    n:      Int,
    opcode: (Arena, Context, Block, Index, Recipe) ?=> InstConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    (0 until n).foreach { i =>
      instruction(i, opcode) {
        rs1Range(1, 32) & rs2Range(1, 32)
      }
    }
    val seq = sequence(0, n)
    seq.coverBins(_.rs1, allRegs)
    seq.coverBins(_.rs2, allRegs)
    seq.coverNoHazard()
