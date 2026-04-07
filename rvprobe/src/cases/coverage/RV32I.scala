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

  // JALR disabled: jalr jumps to register values which are data addresses
  // after the load/store preamble, causing execution of garbage → trap loop.
  // JALR holes are covered by handwrite.S instead.

  // CSR disabled: csrrw/csrrs/csrrc to ustatus in M-mode may cause
  // illegal instruction exceptions on rv32i without U-mode support.
  // CSR holes are covered by handwrite.S instead.

  // CSR-immediate (csrrwi/csrrsi/csrrci) disabled:
  // RVProbe asm renderer doesn't emit the uimm operand correctly for CSR-imm format.
  // These 3 covergroups' SP holes are covered by handwrite.S instead.

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
  )
