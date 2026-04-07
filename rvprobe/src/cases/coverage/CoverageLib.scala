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
  private val BaremetalPreamble        = HTIFLib.asmTextStart()
  private val BaremetalEpilogue        =
    s"""${HTIFLib.asmExit()}
       |
       |${HTIFLib.asmTohostSection()}""".stripMargin

  def writeCoverageAsm(
    outputPath: String,
    generators: RVGenerator*
  ): Unit =
    val body    = generators.map(_.toRecipeAsm().trim).mkString(CoverageSectionSeparator)
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

  /** R-type logical instruction coverage: rType + logical similarity (IDENTICAL, OPPOSITE, DIFFERENT).
    *
    * Logical similarity is a value-level property measured by riscv-dv: how many bits differ between rs1_value and
    * rs2_value. Since the SMT solver controls register indices (not runtime values), we use setup `li` instructions to
    * load known values, then emit the logical op targeting those registers.
    *
    *   - IDENTICAL: rs1 == rs2 (same register → same value, 0 bits differ)
    *   - OPPOSITE: rs1_value == ~rs2_value (all 32 bits differ)
    *   - DIFFERENT: rs1_value and rs2_value differ by ≥5 bits
    *
    * @param n
    *   number of solver-driven instructions (for hazard/register bin coverage)
    * @param opcode
    *   the instruction constraint (e.g. isAnd())
    * @param asmOp
    *   the AsmApi function for this instruction (e.g. `and`)
    */
  def rTypeLogical(
    n:     Int,
    opcode: (Arena, Context, Block, Index, Recipe) ?=> InstConstraint,
    asmOp: (Referable[SInt], Referable[SInt], Referable[SInt]) => (Arena, Context, Block, Recipe) ?=> Int
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    rType(n, opcode)
    // IDENTICAL: same register for rs1 and rs2
    val rIdent = freshReg()
    li(rIdent, 42)
    asmOp(freshReg(), rIdent, rIdent)
    // OPPOSITE: all 32 bits differ
    val rOppA = freshReg()
    val rOppB = freshReg()
    li(rOppA, 0x55555555L)
    li(rOppB, 0xAAAAAAAAL.toInt.toLong) // = ~0x55555555 sign-extended
    asmOp(freshReg(), rOppA, rOppB)
    // DIFFERENT: ≥5 bits differ
    val rDiffA = freshReg()
    val rDiffB = freshReg()
    li(rDiffA, 0L)
    li(rDiffB, 0xFFL)
    asmOp(freshReg(), rDiffA, rDiffB)

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

  /** I-type logical instruction coverage: iTypeAlu + logical similarity (IDENTICAL, OPPOSITE, DIFFERENT).
    *
    * For I-type logical ops, riscv-dv compares rs1_value vs imm_value. We use `li` to load a known value into rs1, then
    * emit the logical op with a matching/complementary immediate.
    *
    *   - IDENTICAL: rs1_value == imm (e.g. li r,42; andi rd,r,42)
    *   - OPPOSITE: all 12 significant bits differ (e.g. li r,0x555; xori rd,r,-1366) — note imm12 is sign-extended to
    *     XLEN, so "opposite" is approximate within 12-bit range
    *   - DIFFERENT: ≥5 bits differ (e.g. li r,0; ori rd,r,0xFF)
    *
    * @param n
    *   number of solver-driven instructions
    * @param opcode
    *   the instruction constraint (e.g. isAndi())
    * @param asmOp
    *   the AsmApi function (e.g. `andi`)
    */
  def iTypeLogical(
    n:     Int,
    opcode: (Arena, Context, Block, Index, Recipe) ?=> InstConstraint,
    asmOp: (Referable[SInt], Referable[SInt], Int) => (Arena, Context, Block, Recipe) ?=> Int
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    iTypeAlu(n, opcode)
    // IDENTICAL: rs1_value == imm
    val rIdent = freshReg()
    li(rIdent, 42L)
    asmOp(freshReg(), rIdent, 42)
    // OPPOSITE: rs1_value bits all differ from imm (within 12-bit signed range)
    val rOpp = freshReg()
    li(rOpp, 0x555L)
    asmOp(freshReg(), rOpp, -1366) // -1366 = 0xFFFFFAAA sign-extended, ~0x555 in low 12 bits
    // DIFFERENT: ≥5 bits differ
    val rDiff = freshReg()
    li(rDiff, 0L)
    asmOp(freshReg(), rDiff, 0xFF)

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
        rs1Range(1, 32) & rs2Range(0, 32) // rs2 includes x0 (ZERO) for store-zero coverage
      }
    }
    val seq = sequence(0, n)
    seq.coverBins(_.rs1, allRegs)
    seq.coverBins(_.rs2, (0 until 32).map(_.S)) // include x0
    seq.coverNoHazard()

  /** JALR instruction coverage: rd + rs1 register bins + hazards.
    *
    * JALR is I-type but used for jumps. rd=x0 means discard return address,
    * rd=x2 (SP) is a key coverage target.
    */
  def jalr(
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
        rdRange(0, 32) & rs1Range(1, 32) // rd includes x0 for j-type pseudo-instructions
      }
    }
    val seq = sequence(0, n)
    seq.coverBins(_.rd, (0 until 32).map(_.S)) // include x0
    seq.coverBins(_.rs1, allRegs)
    seq.coverRAW()
    seq.coverWAR()
    seq.coverWAW()
    seq.coverNoHazard()

  /** CSR instruction coverage: rd + rs1 register bins + hazards.
    *
    * CSR instructions (csrrw, csrrs, csrrc) have rd and rs1 fields.
    * rd=x0 means read-only, rd=SP is a key coverage target.
    */
  def csr(
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
        rdRange(0, 32) & rs1Range(1, 32)
      }
    }
    val seq = sequence(0, n)
    seq.coverBins(_.rd, (0 until 32).map(_.S)) // include x0
    seq.coverBins(_.rs1, allRegs)
    seq.coverRAW()
    seq.coverWAR()
    seq.coverWAW()
    seq.coverNoHazard()

  /** CSR-immediate instruction coverage: rd register bins + hazards.
    *
    * CSR-immediate instructions (csrrwi, csrrsi, csrrci) have rd field only.
    * rd=SP is a key coverage target.
    */
  def csrImm(
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
        rdRange(0, 32)
      }
    }
    val seq = sequence(0, n)
    seq.coverBins(_.rd, (0 until 32).map(_.S))
    seq.coverRAW()
    seq.coverWAR()
    seq.coverWAW()
    seq.coverNoHazard()
