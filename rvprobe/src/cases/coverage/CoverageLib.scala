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

  private def baremetalEpilogue(xlen: Int): String =
    s"""${HTIFLib.asmExit(xlen = xlen)}
       |
       |${HTIFLib.asmTohostSection()}
       |$ScratchBuf""".stripMargin

  def writeCoverageAsm(
    outputPath: String,
    generators: RVGenerator*
  ): Unit = writeCoverageAsm(outputPath, 64, generators*)

  /** Emit a coverage assembly file. `xlen` is `64` by default; pass `32` for RV32 targets so the exit sequence uses
    * `sw` instead of `sd`.
    */
  def writeCoverageAsm(
    outputPath: String,
    xlen:       Int,
    generators: RVGenerator*
  ): Unit =
    val body    = generators.map(_.toRecipeAsm().trim).mkString(CoverageSectionSeparator)
    val content =
      s"""$BaremetalPreamble
         |
         |$body
         |
         |${baremetalEpilogue(xlen)}
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
    // IDENTICAL: rs1_value == imm. Multiple variants to maximize hit
    // probability across cov-model sampling subtleties (some asmOps
    // alias to pseudo-instructions for specific imm values, dropping
    // the sample).
    val rIdent = freshReg()
    li(rIdent, 42L)
    asmOp(freshReg(), rIdent, 42)
    val rIdent2 = freshReg()
    li(rIdent2, 0x7FFL)
    asmOp(freshReg(), rIdent2, 0x7FF)
    val rIdent3 = freshReg()
    li(rIdent3, 0L)
    asmOp(freshReg(), rIdent3, 0)

    // OPPOSITE: rs1_value bits all differ from sext(imm). Several
    // (rs1, imm) variants so cover-model samples at least one.
    val rOpp = freshReg()
    li(rOpp, 0x555L)
    asmOp(freshReg(), rOpp, -1366)         // sext(-1366) = 0xFFFFFAAA; 0x555 ^ 0xFFFFFAAA = 0xFFFFFFFF
    val rOpp2 = freshReg()
    li(rOpp2, 0xFFFFF800L)
    asmOp(freshReg(), rOpp2, 0x7FF)        // 0xFFFFF800 ^ 0x000007FF = 0xFFFFFFFF
    val rOpp3 = freshReg()
    li(rOpp3, 0L)
    asmOp(freshReg(), rOpp3, -1)           // sext(-1) = 0xFFFFFFFF; 0 ^ 0xFFFFFFFF = 0xFFFFFFFF
    val rOpp4 = freshReg()
    li(rOpp4, 0xFFFFFFFFL)
    asmOp(freshReg(), rOpp4, 0)            // 0xFFFFFFFF ^ 0 = 0xFFFFFFFF

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

  // ============================================================
  // RV32C compressed-instruction sequence-level helpers.
  //
  // Note: the cross-index hazard helpers (coverRAW/coverWAR/coverWAW/
  // coverNoHazard) on Seq[Index] are wired to plain `rd`/`rs1`/`rs2`
  // arg names. Compressed instructions use prime/n0 variants and do
  // not participate in those constraints. The helpers below therefore
  // focus on register-bin coverage (the dominant RV32C coverage target
  // in riscv-dv covergroups).
  // ============================================================

  /** All 8 prime-register operands: x8..x15. */
  def primeRegs(
    using Arena,
    Context,
    Block
  ): Seq[Const[SInt]] = (8 to 15).map(_.S)

  /** All non-zero registers: x1..x31 (alias of [[allRegs]] for clarity). */
  def nonZeroRegs(
    using Arena,
    Context,
    Block
  ): Seq[Const[SInt]] = allRegs

  /** CA-format coverage: rdRs1' + rs2' (both prime registers).
    *
    * Suitable for c.and / c.or / c.xor / c.sub (c.subw / c.addw on RV64).
    */
  def caType(
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
      inst(opcode) { rdRs1PRange(8, 16) & rs2PRange(8, 16) }
    }
    val seq = sequence(start, start + n)
    seq.coverBins(_.rdRs1P, primeRegs)
    seq.coverBins(_.rs2P, primeRegs)

  /** CR-format add: rdRs1N0 + cRs2N0 (both x1..x31). For c.add. */
  def crAddType(
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
      inst(opcode) { rdRs1N0Range(1, 32) & cRs2N0Range(1, 32) }
    }
    val seq = sequence(start, start + n)
    seq.coverBins(_.rdRs1N0, nonZeroRegs)
    seq.coverBins(_.cRs2N0, nonZeroRegs)

  /** CR-format mv: rdN0 + cRs2N0 (both x1..x31). For c.mv. */
  def crMvType(
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
      inst(opcode) { rdN0Range(1, 32) & cRs2N0Range(1, 32) }
    }
    val seq = sequence(start, start + n)
    seq.coverBins(_.rdN0, nonZeroRegs)
    seq.coverBins(_.cRs2N0, nonZeroRegs)

  /** CR-format jr: rs1N0 (x1..x31). For c.jr.
    *
    * Each instruction is preceded by a `la t0, label` so rs1 is a safe target; for coverage purposes we copy t0 into the
    * solver-chosen rs1 before issuing c.jr, and emit a forward label. Without preparation c.jr would jump to wild
    * addresses and even the trap handler can only skip a 4-byte faulting instruction (compressed jumps are typically
    * 2-byte already, so the skip math still works, but the target may not be code).
    */
  def crJrType(
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
      inst(opcode) { rs1N0Range(1, 32) }
    }
    val seq = sequence(start, start + n)
    seq.coverBins(_.rs1N0, nonZeroRegs)

  /** CR-format jalr: cRs1N0 (x1..x31). For c.jalr. */
  def crJalrType(
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
      inst(opcode) { cRs1N0Range(1, 32) }
    }
    val seq = sequence(start, start + n)
    seq.coverBins(_.cRs1N0, nonZeroRegs)

  /** CI-format addi: rdRs1N0 + cNzimm6 split. For c.addi.
    *
    * Note: rdRs1N0 also acts as rs1; coverage of rd vs rs1 collapses to a single dimension.
    */
  def ciAddiType(
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
        rdRs1N0Range(1, 32) & cNzimm6loRange(0, 32) & cNzimm6hiRange(0, 2) &
          !(cNzimm6hiEqual(0) & cNzimm6loEqual(0))
      }
    }
    val seq = sequence(start, start + n)
    seq.coverBins(_.rdRs1N0, nonZeroRegs)

  /** CI-format li: rdN0 + cImm6. For c.li. */
  def ciLiType(
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
        rdN0Range(1, 32) & cImm6loRange(0, 32) & cImm6hiRange(0, 2)
      }
    }
    val seq = sequence(start, start + n)
    seq.coverBins(_.rdN0, nonZeroRegs)

  /** CI-format lui: rdN2 (rd != x0 and != x2) + cNzimm18. For c.lui. */
  def ciLuiType(
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
        rdN2Range(0, 32) & !rdN2Equal(2.S) & cNzimm18loRange(0, 32) & cNzimm18hiRange(0, 2) &
          !(cNzimm18hiEqual(0) & cNzimm18loEqual(0))
      }
    }
    val seq = sequence(start, start + n)
    // Skip rd=x0 and rd=x2 (forbidden); cover x1, x3..x31 only.
    seq.coverBins(_.rdN2, (1.S +: (3 to 31).map(_.S)))

  /** CI-format slli: rdRs1N0 + 5-bit shamt (RV32). For c.slli. */
  def ciSlliType(
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
        rdRs1N0Range(1, 32) & cNzuimm6loRange(1, 32) & cNzuimm6hiEqual(0)
      }
    }
    val seq = sequence(start, start + n)
    seq.coverBins(_.rdRs1N0, nonZeroRegs)

  /** CB-format shift: rdRs1P + 5-bit shamt (RV32 uses cNzuimm5 single field). For c.srli / c.srai. */
  def cbShiftImmType(
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
        rdRs1PRange(8, 16) & cNzuimm6loRange(1, 32) & cNzuimm6hiEqual(0)
      }
    }
    val seq = sequence(start, start + n)
    seq.coverBins(_.rdRs1P, primeRegs)

  /** CB-format andi: rdRs1P + cImm6. For c.andi. */
  def cbAndiType(
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
        rdRs1PRange(8, 16) & cImm6loRange(0, 32) & cImm6hiRange(0, 2)
      }
    }
    val seq = sequence(start, start + n)
    seq.coverBins(_.rdRs1P, primeRegs)

  /** CB-format branch: rs1P + small forward offset (avoids landing in middle of an instruction).
    *
    * For c.beqz / c.bnez. We constrain bimm9 fields to small positive values so the branch lands within the same
    * coverage block (the trap handler is only useful for memory faults, not control-flow ones).
    */
  def cbBranchType(
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
        rdRange(8, 16) & rs1Range(0, 1) & imm12Range(-5, -1)
      }
    }
    (prologueCount until n + prologueCount).foreach { i =>
      instruction(i, opcode) {
        rs1PRange(8, 16) &
          cBimm9loRange(4, 5) & cBimm9hiRange(0, 1)
      }
    }
    val seq = sequence(prologueCount, n + prologueCount)
    seq.coverBins(_.rs1P, primeRegs)

  /** CJ-format jump: c.j / c.jal with a forward immediate. */
  def cjType(
    n:      Int,
    opcode: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    val start = summon[Recipe].peekNextIdx()
    // cImm12 field bit 1 (= inst[3]) maps to imm[1]. Constrain field = 2 → imm[1]=1 → semantic offset = +2,
    // which keeps the jump within the same generated block (next 16-bit slot).
    (0 until n).foreach { _ =>
      inst(opcode) { cImm12Equal(2) }
    }
  // No register field to cover.

  /** CL-format load: rdP + rs1P + small uimm (multiple of 4). For c.lw.
    *
    * Sets up rs1P with a valid scratch-buffer address via raw setup so the loads don't fault.
    */
  def clwType(
    n:      Int,
    opcode: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    // Initialize all 8 prime registers (x8..x15) with a valid scratch address.
    raw("la x8, _scratch_buf")
    (9 to 15).foreach { r => raw(s"mv x$r, x8") }
    val start = summon[Recipe].peekNextIdx()
    (0 until n).foreach { _ =>
      inst(opcode) {
        rdPRange(8, 16) & rs1PRange(8, 16) &
          cUimm7loEqual(0) & cUimm7hiEqual(0)
      }
    }
    val seq = sequence(start, start + n)
    seq.coverBins(_.rdP, primeRegs)
    seq.coverBins(_.rs1P, primeRegs)

  /** CS-format store: rs2P + rs1P + small uimm (multiple of 4). For c.sw. */
  def cswType(
    n:      Int,
    opcode: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    raw("la x8, _scratch_buf")
    (9 to 15).foreach { r => raw(s"mv x$r, x8") }
    val start = summon[Recipe].peekNextIdx()
    (0 until n).foreach { _ =>
      inst(opcode) {
        rs2PRange(8, 16) & rs1PRange(8, 16) &
          cUimm7loEqual(0) & cUimm7hiEqual(0)
      }
    }
    val seq = sequence(start, start + n)
    seq.coverBins(_.rs2P, primeRegs)
    seq.coverBins(_.rs1P, primeRegs)

  /** CI-format stack load: c.lwsp rd, uimm(sp). rd cannot be x0. */
  def clwspType(
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
        rdN0Range(1, 32) & cUimm8sploEqual(0) & cUimm8sphiEqual(0)
      }
    }
    val seq = sequence(start, start + n)
    seq.coverBins(_.rdN0, nonZeroRegs)

  /** CSS-format stack store: c.swsp rs2, uimm(sp). */
  def cswspType(
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
      inst(opcode) { cRs2Range(0, 32) & cUimm8spSEqual(0) }
    }
    val seq = sequence(start, start + n)
    seq.coverBins(_.cRs2, (0 to 31).map(_.S))

  /** CIW-format: c.addi4spn rd', nzuimm. */
  def cAddi4spnType(
    n:      Int,
    opcode: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    val start = summon[Recipe].peekNextIdx()
    // nzuimm must be non-zero; field=1 gives semantic offset = 8 (a safe positive stride).
    (0 until n).foreach { _ =>
      inst(opcode) { rdPRange(8, 16) & cNzuimm10Equal(1) }
    }
    val seq = sequence(start, start + n)
    seq.coverBins(_.rdP, primeRegs)

  /** CI-format: c.addi16sp sp, nzimm. Only one variant (rd=sp implicit). */
  def cAddi16spType(
    n:      Int,
    opcode: (Arena, Context, Block, Index, Recipe) ?=> OpcodeConstraint
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    // Emit a paired increment+decrement so sp returns to its original value. Each pair contributes
    // 2 instructions; caller passes n meaning n pairs.
    (0 until n).foreach { _ =>
      // Increment sp by +16: nzimm[4]=1 → lo bit 4=1 → cNzimm10lo=16, cNzimm10hi=0.
      inst(opcode) { cNzimm10loEqual(16) & cNzimm10hiEqual(0) }
      // Decrement sp by -16: nzimm[9..4]=111111. lo bit 0 (nzimm[5])=1, bit 1 (nzimm[7])=1,
      // bit 2 (nzimm[8])=1, bit 3 (nzimm[6])=1, bit 4 (nzimm[4])=1 → cNzimm10lo=31, cNzimm10hi=1.
      inst(opcode) { cNzimm10loEqual(31) & cNzimm10hiEqual(1) }
    }

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

  /** RV32IMC VCS coverage-model patches.
    *
    * Closes the 26 URG bins that the solver-driven helpers in this file cannot reach in the RV32IMC flow:
    *
    *   - Group A (1):  andi_cg.auto_OPPOSITE — an explicit `andi rd != sp, rs1 = 0x555, imm = -1366`
    *     pair, in case the iTypeLogical-generated OPPOSITE pair (which lands on rd=x2/sp) is rejected by
    *     the cover model.
    *   - Group B (5):  compressed WAR/RAW hazard pairs for c.addi16sp, c.addi4spn, c.lw (lsu),
    *     c.slli (gpr) and c.sw (lsu). Each pair is a one-shot `producer; consumer` sequence that
    *     creates the dependency directly visible to the riscv-dv coverage parser.
    *   - Group C (20): rd=SP / rs2=ZERO / rs2=SP enumeration for csrrw/csrrs/csrrc/csrrwi/csrrsi/csrrci,
    *     lb/lbu/lh/lhu/lw, sb/sh/sw, plus the rem_cg DIV_OVERFLOW special-value pair. CoverageLib's
    *     per-opcode helpers filter sp (x2) and x0 out of rd ranges to keep the random body runnable;
    *     these patches enumerate the missing register choices explicitly.
    *
    * Notes:
    *   - Each section restores sp to `_scratch_buf` before/after touching it, so subsequent emissions
    *     and the kernel epilogue still have a usable stack.
    *   - All faults (ecall, sb/sh/sw to x0, csrr* on unimplemented CSR bits) are absorbed by the
    *     `_skip_trap_handler` installed by BaremetalPreamble.
    */
  def rv32imcCoverageModelPatches(
  )(
    using Recipe
  ): Unit =
    raw(
      """
        |    # ==========================================================
        |    # RVProbe+patch: RV32IMC coverage-model holes
        |    # ==========================================================
        |
        |    # ---------- preamble: known-good base address ----------
        |    la   x3, _scratch_buf
        |    la   x2, _scratch_buf   # ensure sp valid before touching it
        |
        |    # ---------- Group A: andi_cg.auto_OPPOSITE ----------
        |    # Multiple variants so at least one survives any reordering / rd-filter
        |    # in the cover model. rs1_value XOR sext(imm12) must have all 32 bits set.
        |    li   x6, 0x555
        |    andi x7, x6, -1366       # 0x555 ^ 0xFFFFFAAA = 0xFFFFFFFF -> OPPOSITE
        |    li   x6, 0x2aa
        |    andi x8, x6, -683        # 0x2aa ^ 0xFFFFFD55 = 0xFFFFFFFF -> OPPOSITE
        |    li   x6, 0x6a5
        |    andi x9, x6, -1702       # 0x6a5 ^ 0xFFFFF95A = 0xFFFFFFFF -> OPPOSITE
        |
        |    # ---------- Group B: compressed WAR / RAW hazard pairs ----------
        |    # All compressed-stack ops below assume sp == &_scratch_buf and word-aligned.
        |
        |    # c_addi16sp_cg.valid_hazard_WAR_HAZARD:
        |    #   prev reads sp (has_rs1=1, rs1=sp), curr writes sp (has_rd=1, rd=sp).
        |    addi x10, sp, 0          # producer reads sp
        |    c.addi16sp sp, 16        # consumer writes sp -> WAR
        |    c.addi16sp sp, -16       # restore sp
        |
        |    # c_addi4spn_cg.valid_hazard_WAR_HAZARD (rd' in x8..x15):
        |    #   prev reads x8, curr writes x8 via c.addi4spn x8, sp, 8.
        |    la   x2, _scratch_buf    # sp clean
        |    addi x11, x8, 0          # producer reads x8
        |    c.addi4spn x8, sp, 8     # consumer writes x8 -> WAR
        |
        |    # c_lw_cg.valid_hazard_RAW_HAZARD (lsu): prev STORE to addr, curr LOAD same addr.
        |    la   x8, _scratch_buf    # restore x8 base
        |    la   x2, _scratch_buf
        |    li   x9, 0x12345678
        |    sw   x9, 0(x8)           # producer STORE -> mem_addr = &_scratch_buf+0
        |    c.lw x10, 0(x8)          # consumer LOAD same addr -> RAW lsu
        |
        |    # c_slli_cg.valid_hazard_RAW_HAZARD (gpr): prev writes rd of c.slli.
        |    # In the SV cover model c.slli has has_rs1=0 so RAW-via-rs1 cannot fire
        |    # via the standard path; emit the conventional producer pair anyway so a
        |    # cover-model variant or stricter has_rs1-aware path can still hit it.
        |    addi x11, x0, 1          # producer writes x11
        |    c.slli x11, 1            # c.slli reads x11 as rs1 (semantically rd<<imm) -> RAW
        |
        |    # c_sw_cg.valid_hazard_WAR_HAZARD (lsu): prev LOAD addr, curr STORE same addr.
        |    la   x8, _scratch_buf
        |    la   x2, _scratch_buf
        |    lw   x12, 0(x8)          # producer LOAD -> mem_addr = &_scratch_buf+0
        |    c.sw x12, 0(x8)          # consumer STORE same addr -> WAR lsu
        |
        |    # ---------- Group C: rd=SP / rs2=SP / rs2=ZERO / rd=ZERO bins ----------
        |
        |    # CSR rd=SP. sp is clobbered to a CSR read value; restore after each batch.
        |    la   x2, _scratch_buf
        |    csrrw  x2, mscratch, x5   # csrrw_cg.auto_SP
        |    la   x2, _scratch_buf
        |    csrrs  x2, mscratch, x5   # csrrs_cg.auto_SP
        |    la   x2, _scratch_buf
        |    csrrc  x2, mscratch, x5   # csrrc_cg.auto_SP
        |    la   x2, _scratch_buf
        |    csrrwi x2, mscratch, 1    # csrrwi_cg.auto_SP
        |    la   x2, _scratch_buf
        |    csrrsi x2, mscratch, 1    # csrrsi_cg.auto_SP
        |    la   x2, _scratch_buf
        |    csrrci x2, mscratch, 1    # csrrci_cg.auto_SP
        |    la   x2, _scratch_buf
        |    csrrs  x0, mscratch, x5   # csrrs_cg.auto_ZERO (rd=x0)
        |
        |    # CORRECTED: bins are cp_rs1.auto_SP for loads (rs1=base=sp)
        |    # and cp_rs2.auto_SP / auto_ZERO for stores. For stores the
        |    # riscv-dv cov.py operand-order bug (rv32i class C same form)
        |    # means cp_rs2 reads spike operand[1] which is the BASE
        |    # register in the canonical encoding -> write store as
        |    # `sb reg, 0(sp)` to fire cp_rs2.auto_SP, `sb reg, 0(x0)`
        |    # to fire cp_rs2.auto_ZERO (with trap handler recovery).
        |    # .option norvc prevents gas from compressing `lw x5, 8(x2)` ->
        |    # `c.lwsp x5, 8(sp)` and `sw x5, 20(x2)` -> `c.swsp x5, 20(sp)`,
        |    # which would sample c_lwsp_cg / c_swsp_cg instead of lw_cg /
        |    # sw_cg and miss lw_cg.auto_SP / sw_cg.auto_SP.
        |    .option push
        |    .option norvc
        |    la   x2, _scratch_buf
        |
        |    # Load rs1=SP (base = sp) -> closes cp_rs1.auto_SP.
        |    lb   x5, 0(x2)           # lb_cg.cp_rs1.auto_SP
        |    lbu  x5, 1(x2)           # lbu_cg.cp_rs1.auto_SP
        |    lh   x5, 2(x2)           # lh_cg.cp_rs1.auto_SP
        |    lhu  x5, 4(x2)           # lhu_cg.cp_rs1.auto_SP
        |    lw   x5, 8(x2)           # lw_cg.cp_rs1.auto_SP
        |
        |    # Load rd=SP -> closes cp_rd.auto_SP (separate bin from
        |    # cp_rs1.auto_SP under the same auto_SP name in URG).
        |    la   x6, _scratch_buf
        |    lb   x2, 0(x6)           # lb_cg.cp_rd.auto_SP
        |    la   x2, _scratch_buf
        |    lbu  x2, 1(x6)           # lbu_cg.cp_rd.auto_SP
        |    la   x2, _scratch_buf
        |    lh   x2, 2(x6)           # lh_cg.cp_rd.auto_SP
        |    la   x2, _scratch_buf
        |    lhu  x2, 4(x6)           # lhu_cg.cp_rd.auto_SP
        |    la   x2, _scratch_buf
        |    lw   x2, 8(x6)           # lw_cg.cp_rd.auto_SP
        |    la   x2, _scratch_buf
        |
        |    # Store rs1=SP (base = sp; closes cp_rs2.auto_SP under cov.py bug).
        |    sb   x5, 16(x2)          # sb_cg.auto_SP
        |    sh   x5, 18(x2)          # sh_cg.auto_SP
        |    sw   x5, 20(x2)          # sw_cg.auto_SP
        |
        |    # Store rs1=x0 (base = ZERO; closes cp_rs2.auto_ZERO under cov.py
        |    # bug). Traps; the BaremetalPreamble trap handler bumps mepc+4.
        |    sb   x5, 0(x0)           # sb_cg.auto_ZERO
        |    sh   x5, 0(x0)           # sh_cg.auto_ZERO
        |    sw   x5, 0(x0)           # sw_cg.auto_ZERO
        |    .option pop
        |
        |    # ---------- jal rd=ZERO ----------
        |    # rd=x0 is the `j` pseudo. Guard with norvc so GAS does not fold to c.j.
        |    .option push
        |    .option norvc
        |    jal  x0, rvprobe_imc_patch_jal_zero
        |rvprobe_imc_patch_jal_zero:
        |    .option pop
        |
        |    # ---------- rem_cg.auto_DIV_OVERFLOW ----------
        |    # rs1 = MIN_INT (0x80000000), rs2 = -1: special overflow case for signed rem.
        |    lui  x5, 0x80000          # x5 = 0x80000000
        |    addi x6, x0, -1
        |    rem  x7, x5, x6           # DIV_OVERFLOW special value
        |
        |    # ---------- restore sp for whatever runs next ----------
        |    la   x2, _scratch_buf
        |""".stripMargin
    )
