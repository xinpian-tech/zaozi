// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*

import utest.*

object PaperTest extends TestSuite:
  val tests = Tests:
    // ── 基本指令集验证：整数运算 ──────────────────────────────────────────────
    // 算术：ADD SUB MUL DIV REM
    // 逻辑：AND OR XOR SLL SRL SRA
    // Equivalent pseudo-sequence:
    //   add  x3,  x1, x2
    //   sub  x4,  x1, x2
    //   and  x5,  x1, x2
    //   or   x6,  x1, x2
    //   xor  x7,  x1, x2
    //   sll  x8,  x1, x2
    //   srl  x9,  x1, x2
    //   sra  x10, x1, x2
    //   mul  x11, x1, x2
    //   div  x12, x1, x2
    //   rem  x13, x1, x2
    test("IntegerOps"):
      object IntegerOps extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          instruction(0, isAdd()) { rdEqual(3.S) & rs1Equal(1.S) & rs2Equal(2.S) }
          instruction(1, isSub()) { rdEqual(4.S) & rs1Equal(1.S) & rs2Equal(2.S) }
          instruction(2, isAnd()) { rdEqual(5.S) & rs1Equal(1.S) & rs2Equal(2.S) }
          instruction(3, isOr()) { rdEqual(6.S) & rs1Equal(1.S) & rs2Equal(2.S) }
          instruction(4, isXor()) { rdEqual(7.S) & rs1Equal(1.S) & rs2Equal(2.S) }
          instruction(5, isSll()) { rdEqual(8.S) & rs1Equal(1.S) & rs2Equal(2.S) }
          instruction(6, isSrl()) { rdEqual(9.S) & rs1Equal(1.S) & rs2Equal(2.S) }
          instruction(7, isSra()) { rdEqual(10.S) & rs1Equal(1.S) & rs2Equal(2.S) }
          instruction(8, isMul()) { rdEqual(11.S) & rs1Equal(1.S) & rs2Equal(2.S) }
          instruction(9, isDiv()) { rdEqual(12.S) & rs1Equal(1.S) & rs2Equal(2.S) }
          instruction(10, isRem()) { rdEqual(13.S) & rs1Equal(1.S) & rs2Equal(2.S) }

      IntegerOps.rvprobeTestInstructions(
        "0: add x3 x1 x2",
        "1: sub x4 x1 x2",
        "2: and x5 x1 x2",
        "3: or x6 x1 x2",
        "4: xor x7 x1 x2",
        "5: sll x8 x1 x2",
        "6: srl x9 x1 x2",
        "7: sra x10 x1 x2",
        "8: mul x11 x1 x2",
        "9: div x12 x1 x2",
        "10: rem x13 x1 x2"
      )

    // ── 基本指令集验证：控制流 ─────────────────────────────────────────────────
    // 条件分支：BLT BGE BEQ BNE
    // 无条件跳转：JAL JALR
    // Equivalent pseudo-sequence:
    //   addi x10, x10, 1          # loop counter increment
    //   blt  x10, x11, . + 0      # loop condition (blt)
    //   bge  x10, x11, . + 0
    //   beq  x10, x11, . + 0
    //   bne  x1,  x3,  . + 0      # error check
    //   jal  x1,  . + 0           # unconditional jump
    //   jalr x0,  x1,  0          # indirect jump (return)
    test("ControlFlow"):
      object ControlFlow extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          instruction(0, isAddi()) { rdEqual(10.S) & rs1Equal(10.S) & imm12Equal(1) }
          instruction(1, isBlt()) { rs1Equal(10.S) & rs2Equal(11.S) & bimm12hiEqual(0) & bimm12loEqual(0) }
          instruction(2, isBge()) { rs1Equal(10.S) & rs2Equal(11.S) & bimm12hiEqual(0) & bimm12loEqual(0) }
          instruction(3, isBeq()) { rs1Equal(10.S) & rs2Equal(11.S) & bimm12hiEqual(0) & bimm12loEqual(0) }
          instruction(4, isBne()) { rs1Equal(1.S) & rs2Equal(3.S) & bimm12hiEqual(0) & bimm12loEqual(0) }
          instruction(5, isJal()) { rdEqual(1.S) & jimm20Equal(0) }
          instruction(6, isJalr()) { rdEqual(0.S) & rs1Equal(1.S) & imm12Equal(0) }

      ControlFlow.rvprobeTestRecipeAsm(
        "addi x10, x10, 1",
        "blt x10, x11, . + 0",
        "bge x10, x11, . + 0",
        "beq x10, x11, . + 0",
        "bne x1, x3, . + 0",
        "jal x1, . + 0",
        "jalr x0, x1, 0"
      )

    // ── 基本指令集验证：内存访问 ───────────────────────────────────────────────
    // 加载/存储：SW LW SB LB（含对齐与非对齐偏移）
    // Equivalent pseudo-sequence:
    //   sw  x1, 0(x2)             # 字存储到地址 x2
    //   lw  x3, 0(x2)             # 从同一地址加载
    //   bne x1, x3, . + 0         # 验证数据一致性
    //   sb  x1, 4(x2)             # 字节存储（偏移 4，验证非对齐存取）
    //   lb  x4, 4(x2)             # 字节加载
    //   bne x1, x4, . + 0         # 验证字节读写一致性
    test("MemoryAccess"):
      object MemoryAccess extends RVGenerator with HasRVProbeTest:
        val sets = isRV64GC()
        def constraints() =
          // sw x1, 0(x2)  — imm12hi=0, imm12lo=0 → combined offset = 0
          instruction(0, isSw()) { rs2Equal(1.S) & rs1Equal(2.S) & imm12hiEqual(0) & imm12loEqual(0) }
          // lw x3, 0(x2)
          instruction(1, isLw()) { rdEqual(3.S) & rs1Equal(2.S) & imm12Equal(0) }
          // bne x1, x3, . + 0
          instruction(2, isBne()) { rs1Equal(1.S) & rs2Equal(3.S) & bimm12hiEqual(0) & bimm12loEqual(0) }
          // sb x1, 4(x2)  — imm12hi=0, imm12lo=4 → combined offset = 4
          instruction(3, isSb()) { rs2Equal(1.S) & rs1Equal(2.S) & imm12hiEqual(0) & imm12loEqual(4) }
          // lb x4, 4(x2)
          instruction(4, isLb()) { rdEqual(4.S) & rs1Equal(2.S) & imm12Equal(4) }
          // bne x1, x4, . + 0
          instruction(5, isBne()) { rs1Equal(1.S) & rs2Equal(4.S) & bimm12hiEqual(0) & bimm12loEqual(0) }

      MemoryAccess.rvprobeTestRecipeAsm(
        "sw x1, 0(x2)",
        "lw x3, 0(x2)",
        "bne x1, x3, . + 0",
        "sb x1, 4(x2)",
        "lb x4, 4(x2)",
        "bne x1, x4, . + 0"
      )
