// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.coverage

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*
import me.jiuyang.rvprobe.cases.coverage.CoverageLib.*

// RV32IMC coverage: RV32I base + M extension + C extension.
//
// Drives solver-based generation through CoverageLib helpers; compressed
// instructions go through the dedicated c*Type/c*Branch helpers added to
// the framework alongside this case.
//
//   mill rvprobe.runMain me.jiuyang.rvprobe.cases.coverage.RV32IMC
@main def RV32IMC(outputPath: String = "rvprobe/src/cases/output/asm/coverage/RV32IMC.S"): Unit =
  val n = 35

  // ============================================================
  // RV32I base
  // ============================================================
  // --- Shift-immediate ---
  object Slli extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = shiftImm(n, isSlli())

  object Srai extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = shiftImm(n, isSrai())

  object Srli extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = shiftImm(n, isSrli())

  // --- R-type ---
  object Add extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rType(n, isAdd())

  object And extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rTypeLogical(n, isAnd(), and)

  object Or extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rTypeLogical(n, isOr(), or)

  object Xor extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rTypeLogical(n, isXor(), xor)

  object Sll extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rType(n, isSll())

  object Slt extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rType(n, isSlt())

  object Sltu extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rType(n, isSltu())

  object Sra extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rType(n, isSra())

  object Srl extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rType(n, isSrl())

  object Sub extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rType(n, isSub())

  // --- I-type ALU ---
  object Addi extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = iTypeAlu(n, isAddi())

  object Andi extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = iTypeLogical(n, isAndi(), andi)

  object Ori extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = iTypeLogical(n, isOri(), ori)

  object Xori extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = iTypeLogical(n, isXori(), xori)

  object Slti extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = iTypeAlu(n, isSlti())

  object Sltiu extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = iTypeAlu(n, isSltiu())

  // --- U-type ---
  object Auipc extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = uType(n, isAuipc())

  object Lui extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = uType(n, isLui())

  // --- Branch ---
  object Beq extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = branch(n, isBeq())

  object Bge extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = branch(n, isBge())

  object Bgeu extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = branch(n, isBgeu())

  object Blt extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = branch(n, isBlt())

  object Bltu extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = branch(n, isBltu())

  object Bne extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = branch(n, isBne())

  // --- Load/Store ---
  object Lb extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = load(n, isLb())

  object Lbu extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = load(n, isLbu())

  object Lh extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = load(n, isLh())

  object Lhu extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = load(n, isLhu())

  object Lw extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = load(n, isLw())

  object Sb extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = store(n, isSb())

  object Sh extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = store(n, isSh())

  object Sw extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = store(n, isSw())

  // ============================================================
  // M extension: mul/div/rem
  // ============================================================
  object Mul extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rType(n, isMul())

  object Mulh extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rType(n, isMulh())

  object Mulhu extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rType(n, isMulhu())

  object Mulhsu extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rType(n, isMulhsu())

  object Div extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rType(n, isDiv())

  object Divu extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rType(n, isDivu())

  object Rem extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rType(n, isRem())

  object Remu extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rType(n, isRemu())

  // ============================================================
  // C extension — solver-driven
  // ============================================================
  // CR format
  object CAdd extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = crAddType(n, isCAdd())

  object CMv extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = crMvType(n, isCMv())

  // c.jr / c.jalr require a valid target. Setup a forward label, drive rs1
  // through every non-zero register, jump to label, fall through.
  object CJr extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() =
      (1 to 31).foreach { r =>
        val regName = s"x$r"
        raw(s"la x5, cjr_rv32imc_$r")
        raw(s"mv $regName, x5")
        raw(s"c.jr $regName")
        raw(s"cjr_rv32imc_$r:")
      }

  object CJalr extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() =
      (1 to 31).foreach { r =>
        val regName = s"x$r"
        raw(s"la x5, cjalr_rv32imc_$r")
        raw(s"mv $regName, x5")
        raw(s"c.jalr $regName")
        raw(s"cjalr_rv32imc_$r:")
      }

  // CI format
  object CAddi extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = ciAddiType(n, isCAddi())

  object CLi extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = ciLiType(n, isCLi())

  object CLui extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = ciLuiType(n, isCLui())

  object CSlli extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = ciSlliType(n, isCSlli())

  // Stack-relative: setup sp before any c.lwsp/c.swsp so loads/stores are safe.
  // The kernel preamble already sets up a stack; we just route loads/stores
  // through it.
  object CLwsp extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() =
      // Ensure sp is word-aligned and points at the scratch buffer for the
      // duration of this block.
      raw("la x2, _scratch_buf")
      clwspType(n, isCLwsp())
      // No need to restore; the next generator's preamble would re-init if it cares.

  object CSwsp extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() =
      raw("la x2, _scratch_buf")
      cswspType(n, isCSwsp())

  object CAddi4spn extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() =
      raw("la x2, _scratch_buf")
      cAddi4spnType(n, isCAddi4spn())

  object CAddi16sp extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() =
      raw("la x2, _scratch_buf")
      cAddi16spType(n, isCAddi16sp())
      raw("la x2, _scratch_buf") // restore

  // CL/CS format — prime register loads/stores
  object CLw extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = clwType(n, isCLw())

  object CSw extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = cswType(n, isCSw())

  // CA format
  object CAnd extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = caType(n, isCAnd())

  object COr extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = caType(n, isCOr())

  object CXor extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = caType(n, isCXor())

  object CSub extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = caType(n, isCSub())

  // CB format
  object CAndi extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = cbAndiType(n, isCAndi())

  object CSrli extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = cbShiftImmType(n, isCSrli())

  object CSrai extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = cbShiftImmType(n, isCSrai())

  object CBeqz extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = cbBranchType(n, isCBeqz())

  object CBnez extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = cbBranchType(n, isCBnez())

  // CJ format
  object CJ extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = cjType(n, isCJ())

  object CJal extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = cjType(n, isCJal())

  // Non-compressed jal/jalr.
  //
  // GAS auto-compresses jal/jalr to c.jal/c.jr when the C extension is enabled
  // and the offset fits. To exercise jal_cg / jalr_cg (which sample the
  // *non-compressed* RV32I jal/jalr opcodes), wrap the raw asm in
  // `.option push; .option norvc` so each jal/jalr stays 32 bits wide.
  object Jal extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() =
      raw(".option push")
      raw(".option norvc")
      // Walk rd through every register including x0; each jal jumps to the next slot.
      // Including x0 closes jal_cg.auto_ZERO (rd=ZERO bin) which the original
      // (1..31) loop skipped.
      (0 to 31).foreach { r =>
        raw(s"jal x$r, jal_norvc_$r")
        raw(s"jal_norvc_$r:")
      }
      raw(".option pop")

  object Jalr extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() =
      raw(".option push")
      raw(".option norvc")
      (1 to 31).foreach { r =>
        raw(s"la x5, jalr_norvc_$r")
        raw(s"jalr x$r, x5, 0")
        raw(s"jalr_norvc_$r:")
      }
      raw(".option pop")

  // Raw-asm coverage-model patches for bins that the SMT-driven helpers cannot
  // reach (CoverageLib filters x0/x2 out of rd ranges for most opcodes, and the
  // compressed-instruction hazard pairs require an explicit producer/consumer
  // pairing that the per-opcode helpers don't synthesise). Mirrors the
  // rv32iCoverageModelPatches() pattern used by RV32I.scala.
  object CoverageModelPatches extends RVGenerator:
    val sets          = isRV32IMC()
    def constraints() = rv32imcCoverageModelPatches()

  writeCoverageAsm(
    outputPath,
    xlen = 32,
    // RV32I base
    Slli, Srai, Srli,
    Add, And, Or, Xor, Sll, Slt, Sltu, Sra, Srl, Sub,
    Addi, Andi, Ori, Xori, Slti, Sltiu,
    Auipc, Lui,
    Beq, Bge, Bgeu, Blt, Bltu, Bne,
    Lb, Lbu, Lh, Lhu, Lw, Sb, Sh, Sw,
    // M extension
    Mul, Mulh, Mulhu, Mulhsu, Div, Divu, Rem, Remu,
    // C extension
    CAdd, CMv, CJr, CJalr,
    CAddi, CLi, CLui, CSlli,
    CLwsp, CSwsp, CAddi4spn, CAddi16sp,
    CLw, CSw,
    CAnd, COr, CXor, CSub,
    CAndi, CSrli, CSrai, CBeqz, CBnez,
    CJ, CJal,
    // Non-compressed jal/jalr for jal_cg / jalr_cg coverage.
    Jal, Jalr,
    // Coverage-model raw-asm patches for the 26 bins the SMT path misses
    // (andi OPPOSITE, compressed WAR/RAW hazard pairs, CSR/load/store rd=SP,
    //  store rs2=ZERO/SP, rem DIV_OVERFLOW). Must run last so the trap
    //  handler and scratch buffer are still in place.
    CoverageModelPatches
  )
