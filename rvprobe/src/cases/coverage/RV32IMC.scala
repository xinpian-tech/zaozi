// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.coverage

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*
import me.jiuyang.rvprobe.cases.coverage.CoverageLib.*

// RV32IMC coverage: RV32I base + M extension + C extension
// Run with: mill rvprobe.runMain me.jiuyang.rvprobe.cases.coverage.RV32IMC
@main def RV32IMC(outputPath: String = "rvprobe/src/cases/output/asm/coverage/RV32IMC.S"): Unit =
  val n = 35

  // ============================================================
  // RV32I base (reuse from RV32I.scala patterns)
  // ============================================================

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

  object Xor extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rTypeLogical(n, isXor(), xor)

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

  object Xori extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = iTypeLogical(n, isXori(), xori)

  object Slti extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = iTypeAlu(n, isSlti())

  object Sltiu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = iTypeAlu(n, isSltiu())

  // --- U-type ---
  object Auipc extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = uType(n, isAuipc())

  object Lui extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = uType(n, isLui())

  // --- Load/Store ---
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

  object Sb extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = store(n, isSb())

  object Sh extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = store(n, isSh())

  object Sw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = store(n, isSw())

  // ============================================================
  // M extension: mul/div/rem
  // ============================================================

  object Mul extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isMul())

  object Mulh extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isMulh())

  object Mulhu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isMulhu())

  object Mulhsu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isMulhsu())

  object Div extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isDiv())

  object Divu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isDivu())

  object Rem extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isRem())

  object Remu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isRemu())

  // ============================================================
  // C extension: compressed instructions
  // ============================================================

  // c.add rd, rs2 — use raw() because asm renderer doesn't handle c.add format
  object CAdd extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      // c.add rd=SP with each rs2
      (1 to 31).foreach { r =>
        raw(s"c.add x2, x$r")
      }
      // c.add each rd with a fixed rs2
      (1 to 31).foreach { r =>
        raw(s"c.add x$r, x5")
      }

  // c.mv rd, rs2 — use raw() because asm renderer doesn't handle c.mv format
  object CMv extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      // Enumerate key registers for c.mv rd=SP coverage
      (1 to 31).foreach { r =>
        raw(s"c.mv x2, x$r")   // rd=SP with each rs2
      }
      (1 to 31).foreach { r =>
        raw(s"c.mv x$r, x5")   // each rd with a fixed rs2
      }

  // c.jr rs1 — jump to rs1 (rd=x0 implicit)
  // Use raw() because c.jr changes control flow
  object CJr extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      // Enumerate all 31 non-zero registers for c.jr rs1
      (1 to 31).foreach { r =>
        val regName = s"x$r"
        raw(s"la x5, cjr_rv_$r")
        raw(s"mv $regName, x5")
        raw(s"c.jr $regName")
        raw(s"cjr_rv_$r:")
      }

  // c.jalr rs1 — jump to rs1, save return addr to ra
  object CJalr extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (1 to 31).foreach { r =>
        val regName = s"x$r"
        raw(s"la x5, cjalr_rv_$r")
        raw(s"mv $regName, x5")
        raw(s"c.jalr $regName")
        raw(s"cjalr_rv_$r:")
      }

  // Supplementary raw instructions for SP/ZERO/CSR holes
  object SpHolePatch extends RVGenerator:
    val sets          = isRV64GC() :+ isRVZICSR()
    def constraints() =
      // Load rs1=SP
      raw("mv x1, x2")
      raw("la x2, _scratch_buf")
      raw("lb  x3, 0(x2)")
      raw("lbu x3, 1(x2)")
      raw("lh  x3, 2(x2)")
      raw("lhu x3, 4(x2)")
      raw("lw  x3, 8(x2)")
      // Store rs1=SP
      raw("sb x3, 16(x2)")
      raw("sh x3, 18(x2)")
      raw("sw x3, 20(x2)")
      raw("mv x2, x1")

      // JALR rd coverage: enumerate all 32 rd registers
      (0 to 31).foreach { r =>
        raw(s"la x5, jalr_rd${r}_rv")
        raw(s"jalr x$r, x5, 0")
        raw(s"jalr_rd${r}_rv:")
      }
      // JALR cross: rd=ra,rs1=t1 and rd=t1,rs1=ra
      raw("la x6, jalr_ra_t1_rv2")
      raw("jalr x1, x6, 0")
      raw("jalr_ra_t1_rv2:")
      raw("la x1, jalr_t1_ra_rv2")
      raw("jalr x6, x1, 0")
      raw("jalr_t1_ra_rv2:")

      // CSR rd=SP
      raw("csrrw x2, mscratch, x3")
      raw("csrrs x2, mscratch, x3")
      raw("csrrc x2, mscratch, x3")
      raw("csrrwi x2, mscratch, 1")
      raw("csrrsi x2, mscratch, 1")
      raw("csrrci x2, mscratch, 1")
      raw("csrrs x0, mscratch, x3")  // rd=ZERO

      // jal rd=ZERO + rd=SP
      raw("j 1f")
      raw("1:")
      raw("jal x2, 2f")
      raw("2:")

      // Store rs2=ZERO
      raw("la x3, _scratch_buf")
      raw("sb x0, 32(x3)")
      raw("sh x0, 34(x3)")
      raw("sw x0, 36(x3)")

      // ECALL with trap handler
      raw("ecall")

      // rem DIV_OVERFLOW
      raw("li x5, 0x80000000")
      raw("li x6, -1")
      raw("rem x7, x5, x6")

  writeCoverageAsm(
    outputPath,
    // RV32I base
    Slli, Srai, Srli,
    Add, And, Or, Xor, Sll, Slt, Sltu, Sra, Srl, Sub,
    Addi, Andi, Ori, Xori, Slti, Sltiu,
    Auipc, Lui,
    Lb, Lbu, Lh, Lhu, Lw, Sb, Sh, Sw,
    // M extension
    Mul, Mulh, Mulhu, Mulhsu, Div, Divu, Rem, Remu,
    // C extension
    CAdd, CMv, CJr, CJalr,
    // SP/ZERO/CSR patches
    SpHolePatch
  )
