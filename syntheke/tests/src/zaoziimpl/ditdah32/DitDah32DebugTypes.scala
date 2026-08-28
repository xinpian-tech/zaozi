// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
// SPDX-License-Identifier: MIT
package com.vowstar.ditdah32

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}

class DitDah32DebugLayers(parameter: DitDah32Parameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class DitDah32DebugProbe(parameter: DitDah32Parameter)
    extends DVBundle[DitDah32Parameter, DitDah32DebugLayers](parameter)

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
  val EBREAK:        Int = 1
  val HALT_REQUEST:  Int = 3
  val STEP:          Int = 4
  val RESET_HALT:    Int = 5

object DebugCsrAddr:
  val DCSR: Int = 0x7b0
  val DPC:  Int = 0x7b1

class JtagDtmIO(parameter: DitDah32Parameter) extends HWBundle(parameter):
  val tck   = Flipped(Clock())
  val reset = Flipped(Reset())
  val tms   = Flipped(Bool())
  val tdi   = Flipped(Bool())
  val trstN = Flipped(Bool())
  val tdo   = Aligned(Bool())

  val requestToggle = Aligned(Bool())
  val requestAddr   = Aligned(UInt(7))
  val requestData   = Aligned(UInt(32))
  val requestOp     = Aligned(UInt(2))

  val responseToggle = Flipped(Bool())
  val responseAddr   = Flipped(UInt(7))
  val responseData   = Flipped(UInt(32))
  val responseOp     = Flipped(UInt(2))

class DebugModuleIO(parameter: DitDah32Parameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())

  val requestToggle = Flipped(Bool())
  val requestAddr   = Flipped(UInt(7))
  val requestData   = Flipped(UInt(32))
  val requestOp     = Flipped(UInt(2))

  val responseToggle = Aligned(Bool())
  val responseAddr   = Aligned(UInt(7))
  val responseData   = Aligned(UInt(32))
  val responseOp     = Aligned(UInt(2))

  val haltReq   = Aligned(Bool())
  val resumeReq = Aligned(Bool())
  val resetReq  = Aligned(Bool())
  val haltOnResetReq = Aligned(Bool())

  val hartHalted    = Flipped(Bool())
  val hartRunning   = Flipped(Bool())
  val hartResumeAck = Flipped(Bool())
  val hartResetAck  = Flipped(Bool())

  val abstractValid   = Aligned(Bool())
  val abstractCmdType = Aligned(UInt(2))
  val abstractWrite   = Aligned(Bool())
  val abstractRegno   = Aligned(UInt(16))
  val abstractSize    = Aligned(UInt(3))
  val abstractData    = Aligned(UInt(32))
  val abstractAddress = Aligned(UInt(32))

  val abstractDone  = Flipped(Bool())
  val abstractError = Flipped(UInt(3))
  val abstractRdata = Flipped(UInt(32))
