// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.coverage

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*
import me.jiuyang.rvprobe.cases.coverage.CoverageLib.*

// RV32I base integer instruction coverage (27 instructions)
// Run with: mill rvprobe.runMain me.jiuyang.rvprobe.cases.coverage.RV32I
@main def RV32I(outputPath: String = "rvprobe/src/cases/output/asm/coverage/RV32I.S"): Unit =
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
    def constraints() = rTypeLogical(n, isAnd(), and)

  object Or extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rTypeLogical(n, isOr(), or)

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
    def constraints() = rTypeLogical(n, isXor(), xor)

  // --- I-type ALU ---
  object Addi extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = iTypeAlu(n, isAddi())

  object Andi extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = iTypeLogical(n, isAndi(), andi)

  object Ori extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = iTypeLogical(n, isOri(), ori)

  object Slti extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = iTypeAlu(n, isSlti())

  object Sltiu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = iTypeAlu(n, isSltiu())

  object Xori extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = iTypeLogical(n, isXori(), xori)

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

  // --- Load ---
  object Lb extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = load(n, isLb())

  object Lbu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = load(n, isLbu())

  object Lh extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = load(n, isLh())

  object Lhu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = load(n, isLhu())

  object Lw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = load(n, isLw())

  // --- Store ---
  object Sb extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = store(n, isSb())

  object Sh extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = store(n, isSh())

  object Sw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = store(n, isSw())

  // Supplementary raw instructions for holes that solver generators can't safely cover:
  //   - JALR/CSR/CSR-imm rd=SP (control flow / privilege issues)
  //   - Load/Store rs1=SP (need SP temporarily set to valid address)
  object SpHolePatch extends RVGenerator:
    val sets          = isRV64GC() :+ isRVZICSR()
    def constraints() =
      // Load rs1=SP: temporarily set SP to scratch_buf
      raw("mv x1, x2")              // save SP in ra
      raw("la x2, _scratch_buf")    // SP = valid address
      raw("lb  x3, 0(x2)")
      raw("lbu x3, 1(x2)")
      raw("lh  x3, 2(x2)")
      raw("lhu x3, 4(x2)")
      raw("lw  x3, 8(x2)")
      // Store rs1=SP
      raw("sb x3, 16(x2)")
      raw("sh x3, 18(x2)")
      raw("mv x2, x1")              // restore SP

      // JALR rd=SP
      raw("la x3, 1f")
      raw("jalr x2, x3, 0")
      raw("1:")

      // CSR rd=SP (use mscratch, safe in M-mode)
      raw("csrrw x2, mscratch, x3")
      raw("csrrs x2, mscratch, x3")
      raw("csrrc x2, mscratch, x3")

      // CSR-imm rd=SP
      raw("csrrwi x2, mscratch, 1")
      raw("csrrsi x2, mscratch, 1")
      raw("csrrci x2, mscratch, 1")

      // csrrs rd=ZERO
      raw("csrrs x0, mscratch, x0")

      // JAL rd=SP + rd=ZERO
      raw("jal x2, jal_sp_rv")
      raw("jal_sp_rv:")
      raw("j jal_zero_rv")
      raw("jal_zero_rv:")

      // Store rs2=ZERO
      raw("la x3, _scratch_buf")
      raw("sb x0, 48(x3)")
      raw("sh x0, 50(x3)")
      raw("sw x0, 52(x3)")

      // JALR rd=S3/S4/S10/S11 (missing rd regs)
      raw("la x5, jalr_s3_rv")
      raw("jalr x19, x5, 0")
      raw("jalr_s3_rv:")
      raw("la x5, jalr_s4_rv")
      raw("jalr x20, x5, 0")
      raw("jalr_s4_rv:")
      raw("la x5, jalr_s10_rv")
      raw("jalr x26, x5, 0")
      raw("jalr_s10_rv:")
      raw("la x5, jalr_s11_rv")
      raw("jalr x27, x5, 0")
      raw("jalr_s11_rv:")

      // JALR cross: rd=ra,rs1=t1 and rd=t1,rs1=ra
      raw("la x6, jalr_ra_t1_rv")
      raw("jalr x1, x6, 0")
      raw("jalr_ra_t1_rv:")
      raw("la x1, jalr_t1_ra_rv")
      raw("jalr x6, x1, 0")
      raw("jalr_t1_ra_rv:")

      // ECALL
      raw("ecall")

  writeCoverageAsm(
    outputPath,
    Slli,
    Srai,
    Srli,
    Add,
    Addi,
    And,
    Andi,
    Auipc,
    Beq,
    Bge,
    Bgeu,
    Blt,
    Bltu,
    Bne,
    Lui,
    Or,
    Ori,
    Sll,
    Slt,
    Slti,
    Sltiu,
    Sltu,
    Sra,
    Srl,
    Sub,
    Xor,
    Xori,
    // Load/Store
    Lb,
    Lbu,
    Lh,
    Lhu,
    Lw,
    Sb,
    Sh,
    Sw,
    // Supplementary raw instructions for SP/JALR/CSR holes
    SpHolePatch
  )
