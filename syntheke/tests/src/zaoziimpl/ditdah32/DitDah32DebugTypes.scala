// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
// SPDX-License-Identifier: MIT
package com.vowstar.ditdah32

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*

/** The RISC-V debug constants, plus the port through which an external debug module drives this hart. The debug module
  * and the JTAG transport themselves are no longer part of the core: they are separate IPs on the negotiation graph
  * (`Dm.scala` / `Dtm.scala`), so one debug module can hold several harts.
  */

object JtagInstruction:
  val BYPASS0: Int = 0x00
  val IDCODE:  Int = 0x01
  val DTMCS:   Int = 0x10
  val DMI:     Int = 0x11
  val BYPASS1: Int = 0x1f

object TapState:
  val TEST_LOGIC_RESET: Int = 0
  val RUN_TEST_IDLE:    Int = 1
  val SELECT_DR_SCAN:   Int = 2
  val CAPTURE_DR:       Int = 3
  val SHIFT_DR:         Int = 4
  val EXIT1_DR:         Int = 5
  val PAUSE_DR:         Int = 6
  val EXIT2_DR:         Int = 7
  val UPDATE_DR:        Int = 8
  val SELECT_IR_SCAN:   Int = 9
  val CAPTURE_IR:       Int = 10
  val SHIFT_IR:         Int = 11
  val EXIT1_IR:         Int = 12
  val PAUSE_IR:         Int = 13
  val EXIT2_IR:         Int = 14
  val UPDATE_IR:        Int = 15

object DmiOp:
  val NOP:     Int = 0
  val READ:    Int = 1
  val WRITE:   Int = 2
  val SUCCESS: Int = 0
  val FAILED:  Int = 2
  val BUSY:    Int = 3

object DebugRegister:
  val DATA0:        Int = 0x04
  val DATA1:        Int = 0x05
  val DMCONTROL:    Int = 0x10
  val DMSTATUS:     Int = 0x11
  val HARTINFO:     Int = 0x12
  val ABSTRACTCS:   Int = 0x16
  val COMMAND:      Int = 0x17
  val ABSTRACTAUTO: Int = 0x18
  val HALTSUM0:     Int = 0x40

object AbstractCommandType:
  val ACCESS_REGISTER: Int = 0
  val ACCESS_MEMORY:   Int = 2

object AbstractCommandError:
  val NONE:           Int = 0
  val BUSY:           Int = 1
  val NOT_SUPPORTED:  Int = 2
  val EXCEPTION:      Int = 3
  val HALT_OR_RESUME: Int = 4
  val BUS:            Int = 5
  val OTHER:          Int = 7

object DebugCause:
  val EBREAK:       Int = 1
  val HALT_REQUEST: Int = 3
  val STEP:         Int = 4
  val RESET_HALT:   Int = 5

object DebugCsrAddr:
  val DCSR: Int = 0x7b0
  val DPC:  Int = 0x7b1

/** What an external debug module drives into this hart, and what the hart reports back: the halt, resume and reset
  * requests, one abstract command at a time, and the hart's state.
  */
class HartDebugBundle(parameter: DitDah32Parameter) extends Bundle:
  val haltReq        = Flipped(Bool())
  val resumeReq      = Flipped(Bool())
  val resetReq       = Flipped(Bool())
  val haltOnResetReq = Flipped(Bool())

  val hartHalted    = Aligned(Bool())
  val hartRunning   = Aligned(Bool())
  val hartResumeAck = Aligned(Bool())
  val hartResetAck  = Aligned(Bool())

  val abstractValid   = Flipped(Bool())
  val abstractCmdType = Flipped(UInt(2))
  val abstractWrite   = Flipped(Bool())
  val abstractRegno   = Flipped(UInt(16))
  val abstractSize    = Flipped(UInt(3))
  val abstractData    = Flipped(UInt(parameter.xlen))
  val abstractAddress = Flipped(UInt(parameter.xlen))

  val abstractDone  = Aligned(Bool())
  val abstractError = Aligned(UInt(3))
  val abstractRdata = Aligned(UInt(parameter.xlen))
