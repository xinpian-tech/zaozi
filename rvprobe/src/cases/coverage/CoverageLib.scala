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
  // Trap handler that skips faulting instruction (4 bytes for non-compressed).
  // Needed because solver-generated code may trash ra/sp or access invalid addresses.
  private val TrapHandler =
    """
      |    # Set up trap handler to skip faulting instructions
      |    la   t0, _skip_trap_handler
      |    csrw mtvec, t0
      |    j    _after_trap_handler
      |    .balign 4
      |_skip_trap_handler:
      |    csrr t0, mepc
      |    addi t0, t0, 4
      |    csrw mepc, t0
      |    mret
      |_after_trap_handler:
      |""".stripMargin

  private val BaremetalPreamble        = HTIFLib.asmTextStart() + TrapHandler
  private val ScratchBuf =
    """
      |    .data
      |    .balign 16
      |    .globl _scratch_buf
      |_scratch_buf:
      |    .fill 256, 1, 0
      |""".stripMargin

  private val BaremetalEpilogue        =
    s"""${HTIFLib.asmExit()}
       |
       |${HTIFLib.asmTohostSection()}
       |$ScratchBuf""".stripMargin

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
    opcode: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    val start = summon[Recipe].peekNextIdx()
    (0 until n).foreach { _ =>
      inst(opcode) {
        rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32)
      }
    }
    val seq = sequence(start, start + n)
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
    opcode: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint,
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
    opcode: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
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
    opcode: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint,
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
    opcode: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
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
    opcode: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
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
    opcode:        (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint,
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
    opcode: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    // Initialize registers x3-x31 with a valid address so loads don't fault.
    // x1(ra) and x2(sp) are preserved to avoid breaking return/stack.
    raw("la x3, _scratch_buf")
    (4 to 31).foreach { r => raw(s"mv x$r, x3") }
    (0 until n).foreach { i =>
      instruction(i, opcode) {
        rdRange(1, 32) & rs1Range(3, 32) // rs1 >= x3 (valid address)
      }
    }
    val seq = sequence(0, n)
    seq.coverBins(_.rd, allRegs)
    seq.coverBins(_.rs1, (3 until 32).map(_.S))
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
    opcode: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    // Initialize registers x3-x31 with a valid address so stores don't fault.
    raw("la x3, _scratch_buf")
    (4 to 31).foreach { r => raw(s"mv x$r, x3") }
    (0 until n).foreach { i =>
      instruction(i, opcode) {
        rs1Range(3, 32) & rs2Range(0, 32) // rs1 >= x3 (valid), rs2 includes x0 (ZERO)
      }
    }
    val seq = sequence(0, n)
    seq.coverBins(_.rs1, (3 until 32).map(_.S))
    seq.coverBins(_.rs2, (0 until 32).map(_.S))
    seq.coverNoHazard()

  /** JALR instruction coverage: rd + rs1 register bins + hazards.
    *
    * JALR is I-type but used for jumps. rd=x0 means discard return address,
    * rd=x2 (SP) is a key coverage target.
    */
  def jalrCov(
    n:      Int,
    opcode: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
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
    opcode: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
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
    opcode: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
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

  /** RV32I VCS coverage-model patches.
    *
    * These are intentionally separated from the solver-driven coverage helpers above. They mirror the hand-written
    * coverage-report patches for bins that are mostly about a specific coverage model or test harness convention
    * (SP/ZERO bins, scratch-buffer load/store setup, JAL/JALR label cases, CSR bins, ECALL). They are useful for a
    * "RVProbe+patch" experiment, but should not be counted as the core sequence-level abstraction contribution.
    */
  def rv32iCoverageModelPatches(
  )(
    using Recipe
  ): Unit =
    raw(
      """
        |    # RVProbe+patch: coverage-model-specific RV32I holes
        |    # rd=SP (x2) bins
        |    sub  x2, x5, x6
        |    sra  x2, x5, x6
        |    srl  x2, x5, x6
        |    sll  x2, x5, x6
        |    slt  x2, x5, x6
        |    sltu x2, x5, x6
        |    and  x2, x5, x6
        |    xor  x2, x5, x6
        |    or   x2, x5, x6
        |    ori   x2, x5, 10
        |    andi  x2, x5, 10
        |    xori  x2, x5, 10
        |    slti  x2, x5, 10
        |    sltiu x2, x5, 10
        |    srli x2, x5, 1
        |    srai x2, x5, 1
        |    slli x2, x5, 1
        |    lui  x2, 0x12345
        |
        |    # CSR rd bins
        |    csrrw  x2, mscratch, x5
        |    csrrs  x2, mscratch, x5
        |    csrrc  x2, mscratch, x5
        |    csrrwi x2, mscratch, 1
        |    csrrsi x2, mscratch, 1
        |    csrrci x2, mscratch, 1
        |    csrrs  x0, mscratch, x5
        |    csrrs  x0, mscratch, x0
        |
        |    # Load/store SP/ZERO bins; _scratch_buf is emitted by CoverageLib epilogue.
        |    la   x3, _scratch_buf
        |    lb   x2, 0(x3)
        |    lbu  x2, 0(x3)
        |    lh   x2, 0(x3)
        |    lhu  x2, 0(x3)
        |    mv   x4, x2
        |    mv   x2, x3
        |    lb   x6, 0(x2)
        |    lbu  x6, 1(x2)
        |    lh   x6, 2(x2)
        |    lhu  x6, 4(x2)
        |    sb   x6, 40(x2)
        |    sh   x6, 42(x2)
        |    sw   x6, 44(x2)
        |    mv   x2, x4
        |    # cp_rs2=ZERO via base=x0 (riscv-dv covergroup parses base register
        |    # as its `rs2` field, so x0-as-base hits the bin). The CoverageLib
        |    # preamble's _skip_trap_handler absorbs the resulting access fault.
        |    sb   x3, 0(x0)
        |    sh   x3, 0(x0)
        |    sw   x3, 0(x0)
        |
        |    # JAL/JALR coverage-model bins
        |    la   x5, rvprobe_patch_jalr_sp
        |    jalr x2, x5, 0
        |rvprobe_patch_jalr_sp:
        |    la   x5, rvprobe_patch_jalr_s3
        |    jalr x19, x5, 0
        |rvprobe_patch_jalr_s3:
        |    la   x5, rvprobe_patch_jalr_s4
        |    jalr x20, x5, 0
        |rvprobe_patch_jalr_s4:
        |    la   x5, rvprobe_patch_jalr_s10
        |    jalr x26, x5, 0
        |rvprobe_patch_jalr_s10:
        |    la   x5, rvprobe_patch_jalr_s11
        |    jalr x27, x5, 0
        |rvprobe_patch_jalr_s11:
        |    la   x6, rvprobe_patch_jalr_ra_t1
        |    jalr x1, x6, 0
        |rvprobe_patch_jalr_ra_t1:
        |    la   x1, rvprobe_patch_jalr_t1_ra
        |    jalr x6, x1, 0
        |rvprobe_patch_jalr_t1_ra:
        |    j rvprobe_patch_jal_zero
        |rvprobe_patch_jal_zero:
        |    jal x2, rvprobe_patch_jal_sp
        |rvprobe_patch_jal_sp:
        |
        |    # ECALL is safe here because writeCoverageAsm installs a skip-trap handler.
        |    ecall
        |
        |    # U-type producer followed by consumer, for coverage models that sample the consumer RAW.
        |    lui   x5, 0x12345
        |    add   x6, x5, x7
        |    auipc x8, 0x12345
        |    add   x9, x8, x7
        |""".stripMargin
    )
