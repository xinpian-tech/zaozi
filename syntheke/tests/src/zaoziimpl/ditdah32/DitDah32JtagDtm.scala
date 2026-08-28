// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
// SPDX-License-Identifier: MIT
package com.vowstar.ditdah32

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

@generator
object DitDah32JtagDtm
    extends Generator[DitDah32Parameter, DitDah32DebugLayers, JtagDtmIO, DitDah32DebugProbe]:

  override def moduleName(parameter: DitDah32Parameter): String = "DitDah32JtagDtm"

  def architecture(parameter: DitDah32Parameter) =
    val io = summon[Interface[JtagDtmIO]]

    given ClockScope = ClockScope.posedge(io.tck)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)

    val state      = RegInit(TapState.TEST_LOGIC_RESET.U(4))
    val ir         = RegInit(JtagInstruction.IDCODE.U(5))
    val irShift    = RegInit(1.U(5))
    val drShift    = RegInit(0.U(41))
    val bypass     = RegInit(false.B)

    val requestToggle = RegInit(false.B)
    val requestAddr   = RegInit(0.U(7))
    val requestData   = RegInit(0.U(32))
    val requestOp     = RegInit(0.U(2))
    val outstanding   = RegInit(false.B)

    val responseToggleMeta = RegInit(false.B)
    val responseToggleSync = RegInit(false.B)
    val responseToggleSeen = RegInit(false.B)
    val responseAddrReg    = RegInit(0.U(7))
    val responseDataReg    = RegInit(0.U(32))
    val responseStatusReg  = RegInit(DmiOp.SUCCESS.U(2))
    val stickyStatus       = RegInit(DmiOp.SUCCESS.U(2))

    val nextState = Wire(UInt(4))
    nextState := TapState.TEST_LOGIC_RESET.U(4)
    when(state === TapState.TEST_LOGIC_RESET.U(4)) {
      nextState := io.tms.?(TapState.TEST_LOGIC_RESET.U(4), TapState.RUN_TEST_IDLE.U(4))
    }
    when(state === TapState.RUN_TEST_IDLE.U(4)) {
      nextState := io.tms.?(TapState.SELECT_DR_SCAN.U(4), TapState.RUN_TEST_IDLE.U(4))
    }
    when(state === TapState.SELECT_DR_SCAN.U(4)) {
      nextState := io.tms.?(TapState.SELECT_IR_SCAN.U(4), TapState.CAPTURE_DR.U(4))
    }
    when(state === TapState.CAPTURE_DR.U(4)) {
      nextState := io.tms.?(TapState.EXIT1_DR.U(4), TapState.SHIFT_DR.U(4))
    }
    when(state === TapState.SHIFT_DR.U(4)) {
      nextState := io.tms.?(TapState.EXIT1_DR.U(4), TapState.SHIFT_DR.U(4))
    }
    when(state === TapState.EXIT1_DR.U(4)) {
      nextState := io.tms.?(TapState.UPDATE_DR.U(4), TapState.PAUSE_DR.U(4))
    }
    when(state === TapState.PAUSE_DR.U(4)) {
      nextState := io.tms.?(TapState.EXIT2_DR.U(4), TapState.PAUSE_DR.U(4))
    }
    when(state === TapState.EXIT2_DR.U(4)) {
      nextState := io.tms.?(TapState.UPDATE_DR.U(4), TapState.SHIFT_DR.U(4))
    }
    when(state === TapState.UPDATE_DR.U(4)) {
      nextState := io.tms.?(TapState.SELECT_DR_SCAN.U(4), TapState.RUN_TEST_IDLE.U(4))
    }
    when(state === TapState.SELECT_IR_SCAN.U(4)) {
      nextState := io.tms.?(TapState.TEST_LOGIC_RESET.U(4), TapState.CAPTURE_IR.U(4))
    }
    when(state === TapState.CAPTURE_IR.U(4)) {
      nextState := io.tms.?(TapState.EXIT1_IR.U(4), TapState.SHIFT_IR.U(4))
    }
    when(state === TapState.SHIFT_IR.U(4)) {
      nextState := io.tms.?(TapState.EXIT1_IR.U(4), TapState.SHIFT_IR.U(4))
    }
    when(state === TapState.EXIT1_IR.U(4)) {
      nextState := io.tms.?(TapState.UPDATE_IR.U(4), TapState.PAUSE_IR.U(4))
    }
    when(state === TapState.PAUSE_IR.U(4)) {
      nextState := io.tms.?(TapState.EXIT2_IR.U(4), TapState.PAUSE_IR.U(4))
    }
    when(state === TapState.EXIT2_IR.U(4)) {
      nextState := io.tms.?(TapState.UPDATE_IR.U(4), TapState.SHIFT_IR.U(4))
    }
    when(state === TapState.UPDATE_IR.U(4)) {
      nextState := io.tms.?(TapState.SELECT_DR_SCAN.U(4), TapState.RUN_TEST_IDLE.U(4))
    }

    val tdo = Wire(Bool())
    tdo := false.B
    when(state === TapState.SHIFT_IR.U(4)) {
      tdo := irShift.asBits.bit(0)
    }
    when(state === TapState.SHIFT_DR.U(4)) {
      tdo := drShift.asBits.bit(0)
    }

    val dmiCaptureStatus = Wire(UInt(2))
    dmiCaptureStatus := stickyStatus
    when((stickyStatus === DmiOp.SUCCESS.U(2)) & outstanding) {
      dmiCaptureStatus := DmiOp.BUSY.U(2)
    }
    when((stickyStatus === DmiOp.SUCCESS.U(2)) & !outstanding) {
      dmiCaptureStatus := responseStatusReg
    }

    val dtmcs = Wire(UInt(32))
    dtmcs := (
      0.B(11) ##
      0.B(3) ##
      0.B(1) ##
      0.B(1) ##
      0.B(1) ##
      7.B(3) ##
      stickyStatus.asBits ##
      7.B(6) ##
      1.B(4)
    ).asUInt

    responseToggleMeta := io.responseToggle
    responseToggleSync := responseToggleMeta
    when(responseToggleSync =/= responseToggleSeen) {
      responseToggleSeen := responseToggleSync
      when(outstanding) {
        outstanding := false.B
        responseAddrReg := io.responseAddr
        responseDataReg := io.responseData
        responseStatusReg := io.responseOp
        when(io.responseOp =/= DmiOp.SUCCESS.U(2)) {
          stickyStatus := io.responseOp
        }
      }
    }

    state := nextState

    when(state === TapState.TEST_LOGIC_RESET.U(4)) {
      ir := JtagInstruction.IDCODE.U(5)
      requestAddr := 0.U(7)
      requestData := 0.U(32)
      requestOp := DmiOp.NOP.U(2)
      outstanding := false.B
      responseAddrReg := 0.U(7)
      responseDataReg := 0.U(32)
      responseStatusReg := DmiOp.SUCCESS.U(2)
      stickyStatus := DmiOp.SUCCESS.U(2)
    }

    when(state === TapState.CAPTURE_IR.U(4)) {
      irShift := 1.U(5)
    }
    when(state === TapState.SHIFT_IR.U(4)) {
      irShift := (io.tdi.asBits ## irShift.asBits.bits(4, 1)).asUInt
    }
    when(state === TapState.UPDATE_IR.U(4)) {
      ir := irShift
    }

    when(state === TapState.CAPTURE_DR.U(4)) {
      drShift := (0.B(40) ## bypass.asBits).asUInt
      when(ir === JtagInstruction.IDCODE.U(5)) {
        drShift := parameter.jtagIdcode.U(41)
      }
      when(ir === JtagInstruction.DTMCS.U(5)) {
        drShift := (0.B(9) ## dtmcs.asBits).asUInt
      }
      when(ir === JtagInstruction.DMI.U(5)) {
        drShift := (
          responseAddrReg.asBits ##
          responseDataReg.asBits ##
          dmiCaptureStatus.asBits
        ).asUInt
        when((stickyStatus === DmiOp.SUCCESS.U(2)) & outstanding) {
          stickyStatus := DmiOp.BUSY.U(2)
        }
      }
    }
    when(state === TapState.SHIFT_DR.U(4)) {
      when(ir === JtagInstruction.DMI.U(5)) {
        drShift := (io.tdi.asBits ## drShift.asBits.bits(40, 1)).asUInt
      }.otherwise {
        when(
          (ir === JtagInstruction.IDCODE.U(5)) |
          (ir === JtagInstruction.DTMCS.U(5))
        ) {
          drShift := (0.B(9) ## io.tdi.asBits ## drShift.asBits.bits(31, 1)).asUInt
        }.otherwise {
          drShift := (0.B(40) ## io.tdi.asBits).asUInt
        }
      }
    }
    when(state === TapState.UPDATE_DR.U(4)) {
      when(ir === JtagInstruction.DTMCS.U(5)) {
        when(drShift.asBits.bit(16)) {
          stickyStatus := DmiOp.SUCCESS.U(2)
          responseStatusReg := DmiOp.SUCCESS.U(2)
        }
        when(drShift.asBits.bit(17)) {
          requestAddr := 0.U(7)
          requestData := 0.U(32)
          requestOp := DmiOp.NOP.U(2)
          outstanding := false.B
          stickyStatus := DmiOp.SUCCESS.U(2)
          responseAddrReg := 0.U(7)
          responseDataReg := 0.U(32)
          responseStatusReg := DmiOp.SUCCESS.U(2)
        }
      }
      when(ir === JtagInstruction.DMI.U(5)) {
        val updateOp = drShift.asBits.bits(1, 0).asUInt
        when((stickyStatus === DmiOp.SUCCESS.U(2)) & (updateOp =/= DmiOp.NOP.U(2))) {
          when(outstanding) {
            stickyStatus := DmiOp.BUSY.U(2)
          }.otherwise {
            when((updateOp === DmiOp.READ.U(2)) | (updateOp === DmiOp.WRITE.U(2))) {
              requestAddr := drShift.asBits.bits(40, 34).asUInt
              requestData := drShift.asBits.bits(33, 2).asUInt
              requestOp := updateOp
              requestToggle := !requestToggle
              outstanding := true.B
            }.otherwise {
              stickyStatus := DmiOp.FAILED.U(2)
            }
          }
        }
      }
      when(
        (ir =/= JtagInstruction.IDCODE.U(5)) &
        (ir =/= JtagInstruction.DTMCS.U(5)) &
        (ir =/= JtagInstruction.DMI.U(5))
      ) {
        bypass := drShift.asBits.bit(0)
      }
    }

    when(!io.trstN) {
      state := TapState.TEST_LOGIC_RESET.U(4)
      ir := JtagInstruction.IDCODE.U(5)
      irShift := 1.U(5)
      drShift := 0.U(41)
      bypass := false.B
      requestAddr := 0.U(7)
      requestData := 0.U(32)
      requestOp := DmiOp.NOP.U(2)
      outstanding := false.B
      responseAddrReg := 0.U(7)
      responseDataReg := 0.U(32)
      stickyStatus := DmiOp.SUCCESS.U(2)
      responseStatusReg := DmiOp.SUCCESS.U(2)
    }

    io.tdo := tdo
    io.requestToggle := requestToggle
    io.requestAddr := requestAddr
    io.requestData := requestData
    io.requestOp := requestOp
