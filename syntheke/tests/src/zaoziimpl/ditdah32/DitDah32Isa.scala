// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
// SPDX-License-Identifier: MIT
package com.vowstar.ditdah32

object CoreState:
  val RESET:    Int = 0
  val RUN:      Int = 1
  val TRAP:     Int = 2
  val DEBUG:    Int = 2
  val STRADDLE: Int = 3
  val LOAD:     Int = 4
  val STORE:    Int = 5
  val SLEEP:    Int = 6
  val IRQ:      Int = 7

object TrapCause:
  val NONE:          Int = 0
  val ILLEGAL:       Int = 1
  val EBREAK:        Int = 2
  val RV32E_REGISTER:Int = 3
  val ECALL:         Int = 4
  val LOAD_MISALIGN: Int = 5
  val STORE_MISALIGN:Int = 6
  val AXI_ERROR:     Int = 7
  val INTERRUPT:     Int = 8

object CsrAddr:
  val MSTATUS:   Int = 0x300
  val MISA:      Int = 0x301
  val MIE:       Int = 0x304
  val MTVEC:     Int = 0x305
  val MSCRATCH:  Int = 0x340
  val MEPC:      Int = 0x341
  val MCAUSE:    Int = 0x342
  val MTVAL:     Int = 0x343
  val MIP:       Int = 0x344
  val MVENDORID: Int = 0xf11
  val MARCHID:   Int = 0xf12
  val MIMPID:    Int = 0xf13
  val MHARTID:   Int = 0xf14

object CsrBits:
  val MSTATUS_MIE:  Int = 3
  val MSTATUS_MPIE: Int = 7
  val MSTATUS_MPP_LOW: Int = 11
  val MSTATUS_MPP_HIGH: Int = 12
  val IRQ_SOFTWARE: Int = 3
  val IRQ_TIMER:    Int = 7
  val IRQ_EXTERNAL: Int = 11

object StandardCause:
  val INSTRUCTION_MISALIGNED: Int = 0
  val ILLEGAL_INSTRUCTION:    Int = 2
  val BREAKPOINT:             Int = 3
  val LOAD_MISALIGNED:        Int = 4
  val STORE_MISALIGNED:       Int = 6
  val ECALL_M:                Int = 11
