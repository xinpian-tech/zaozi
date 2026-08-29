// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import com.vowstar.ditdah32.{DmiOp, JtagInstruction, TapState}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.ReadWriter

/** The debug transport, lifted out of DitDah32 into its own IP: a RISC-V debug JTAG TAP whose DMI side is a plain
  * request/response bus. The TAP itself is clocked by `tck` and the bus by the system clock, so the module holds the
  * one crossing the chain needs — a request toggle up, a response toggle back, the payload registers stable across it.
  *
  * The scan register is `{addr, data, op}`, shifted LSB first; `dtmcs` publishes the address width the transport
  * carries, which negotiation has already checked against the debug module's register file.
  */

case class DtmP(idcode: Long, irLength: Int, abits: Int, dataBits: Int) extends Parameter derives ReadWriter:
  require(idcode >= 0L && idcode <= 0xffffffffL, s"idcode 0x${idcode.toHexString} must fit in 32 bits")
  require((idcode & 1L) == 1L, "idcode bit 0 must be one")
  require(irLength == 5, s"the TAP's instruction register is 5 bits, got $irLength")
  require(abits >= 1 && abits <= 63, s"DMI address width $abits must be within 1..63")
  require(dataBits == 32, s"the DMI data field is 32 bits, got $dataBits")
  val drWidth: Int = abits + dataBits + 2

class DtmPLayers(p: DtmP) extends LayerInterface(p):
  def layers = Seq.empty
class DtmPProbe(p: DtmP)  extends DVBundle[DtmP, DtmPLayers](p)
class DtmPIO(p: DtmP)     extends HWBundle(p):
  val clk  = Flipped(new ClockBundle)
  val jtag = Aligned(new JtagBundle)
  val dmi  = Aligned(new DmiBundle(p.abits, p.dataBits))

@generator
object DtmGen extends Generator[DtmP, DtmPLayers, DtmPIO, DtmPProbe]:
  def architecture(p: DtmP) =
    val io           = summon[Interface[DtmPIO]]
    given ResetScope = ResetScope.asyncActiveHigh(io.clk.reset)
    given ClockScope = ClockScope.posedge(io.clk.clock)

    // ---- system clock domain: the request the TAP handed over, and the bus transaction it becomes ----
    val requestToggleMeta = RegInit(false.B)
    val requestToggleSync = RegInit(false.B)
    val requestToggleSeen = RegInit(false.B)
    val busAddr           = RegInit(0.U(p.abits))
    val busData           = RegInit(0.U(p.dataBits))
    val busOp             = RegInit(0.U(2))
    val busState          = RegInit(0.U(2)) // 0 idle, 1 request, 2 response
    val responseToggle    = RegInit(false.B)
    val responseData      = RegInit(0.U(p.dataBits))
    val responseOp        = RegInit(DmiOp.SUCCESS.U(2))

    // ---- tck domain: the TAP. It reads the response registers above, stable while their toggle crosses back. ----
    val (requestToggle, requestAddr, requestData, requestOp) = locally {
      given ClockScope = ClockScope.posedge(io.jtag.tck)

      val state   = RegInit(TapState.TEST_LOGIC_RESET.U(4))
      val ir      = RegInit(JtagInstruction.IDCODE.U(p.irLength))
      val irShift = RegInit(1.U(p.irLength))
      val drShift = RegInit(0.U(p.drWidth))
      val bypass  = RegInit(false.B)

      val requestToggle = RegInit(false.B)
      val requestAddr   = RegInit(0.U(p.abits))
      val requestData   = RegInit(0.U(p.dataBits))
      val requestOp     = RegInit(DmiOp.NOP.U(2))
      val outstanding   = RegInit(false.B)

      val responseToggleMeta = RegInit(false.B)
      val responseToggleSync = RegInit(false.B)
      val responseToggleSeen = RegInit(false.B)
      val responseAddrReg    = RegInit(0.U(p.abits))
      val responseDataReg    = RegInit(0.U(p.dataBits))
      val responseStatusReg  = RegInit(DmiOp.SUCCESS.U(2))
      val stickyStatus       = RegInit(DmiOp.SUCCESS.U(2))

      val nextState = Wire(UInt(4))
      nextState := TapState.TEST_LOGIC_RESET.U(4)
      when(state === TapState.TEST_LOGIC_RESET.U(4)) {
        nextState := io.jtag.tms.?(TapState.TEST_LOGIC_RESET.U(4), TapState.RUN_TEST_IDLE.U(4))
      }
      when(state === TapState.RUN_TEST_IDLE.U(4)) {
        nextState := io.jtag.tms.?(TapState.SELECT_DR_SCAN.U(4), TapState.RUN_TEST_IDLE.U(4))
      }
      when(state === TapState.SELECT_DR_SCAN.U(4)) {
        nextState := io.jtag.tms.?(TapState.SELECT_IR_SCAN.U(4), TapState.CAPTURE_DR.U(4))
      }
      when(state === TapState.CAPTURE_DR.U(4)) {
        nextState := io.jtag.tms.?(TapState.EXIT1_DR.U(4), TapState.SHIFT_DR.U(4))
      }
      when(state === TapState.SHIFT_DR.U(4)) {
        nextState := io.jtag.tms.?(TapState.EXIT1_DR.U(4), TapState.SHIFT_DR.U(4))
      }
      when(state === TapState.EXIT1_DR.U(4)) {
        nextState := io.jtag.tms.?(TapState.UPDATE_DR.U(4), TapState.PAUSE_DR.U(4))
      }
      when(state === TapState.PAUSE_DR.U(4)) {
        nextState := io.jtag.tms.?(TapState.EXIT2_DR.U(4), TapState.PAUSE_DR.U(4))
      }
      when(state === TapState.EXIT2_DR.U(4)) {
        nextState := io.jtag.tms.?(TapState.UPDATE_DR.U(4), TapState.SHIFT_DR.U(4))
      }
      when(state === TapState.UPDATE_DR.U(4)) {
        nextState := io.jtag.tms.?(TapState.SELECT_DR_SCAN.U(4), TapState.RUN_TEST_IDLE.U(4))
      }
      when(state === TapState.SELECT_IR_SCAN.U(4)) {
        nextState := io.jtag.tms.?(TapState.TEST_LOGIC_RESET.U(4), TapState.CAPTURE_IR.U(4))
      }
      when(state === TapState.CAPTURE_IR.U(4)) {
        nextState := io.jtag.tms.?(TapState.EXIT1_IR.U(4), TapState.SHIFT_IR.U(4))
      }
      when(state === TapState.SHIFT_IR.U(4)) {
        nextState := io.jtag.tms.?(TapState.EXIT1_IR.U(4), TapState.SHIFT_IR.U(4))
      }
      when(state === TapState.EXIT1_IR.U(4)) {
        nextState := io.jtag.tms.?(TapState.UPDATE_IR.U(4), TapState.PAUSE_IR.U(4))
      }
      when(state === TapState.PAUSE_IR.U(4)) {
        nextState := io.jtag.tms.?(TapState.EXIT2_IR.U(4), TapState.PAUSE_IR.U(4))
      }
      when(state === TapState.EXIT2_IR.U(4)) {
        nextState := io.jtag.tms.?(TapState.UPDATE_IR.U(4), TapState.SHIFT_IR.U(4))
      }
      when(state === TapState.UPDATE_IR.U(4)) {
        nextState := io.jtag.tms.?(TapState.SELECT_DR_SCAN.U(4), TapState.RUN_TEST_IDLE.U(4))
      }

      val tdo = Wire(Bool())
      tdo         := false.B
      when(state === TapState.SHIFT_IR.U(4)) {
        tdo := irShift.asBits.bit(0)
      }
      when(state === TapState.SHIFT_DR.U(4)) {
        tdo := drShift.asBits.bit(0)
      }
      io.jtag.tdo := tdo

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
          p.abits.B(6) ##
          1.B(4)
      ).asUInt

      responseToggleMeta := responseToggle
      responseToggleSync := responseToggleMeta
      when(responseToggleSync =/= responseToggleSeen) {
        responseToggleSeen := responseToggleSync
        when(outstanding) {
          outstanding       := false.B
          responseAddrReg   := requestAddr
          responseDataReg   := responseData
          responseStatusReg := responseOp
          when(responseOp =/= DmiOp.SUCCESS.U(2)) {
            stickyStatus := responseOp
          }
        }
      }

      state := nextState

      when(state === TapState.TEST_LOGIC_RESET.U(4)) {
        ir                := JtagInstruction.IDCODE.U(p.irLength)
        requestAddr       := 0.U(p.abits)
        requestData       := 0.U(p.dataBits)
        requestOp         := DmiOp.NOP.U(2)
        outstanding       := false.B
        responseAddrReg   := 0.U(p.abits)
        responseDataReg   := 0.U(p.dataBits)
        responseStatusReg := DmiOp.SUCCESS.U(2)
        stickyStatus      := DmiOp.SUCCESS.U(2)
      }

      when(state === TapState.CAPTURE_IR.U(4)) {
        irShift := 1.U(p.irLength)
      }
      when(state === TapState.SHIFT_IR.U(4)) {
        irShift := (io.jtag.tdi.asBits ## irShift.asBits.bits(p.irLength - 1, 1)).asUInt
      }
      when(state === TapState.UPDATE_IR.U(4)) {
        ir := irShift
      }

      when(state === TapState.CAPTURE_DR.U(4)) {
        drShift := (0.B(p.drWidth - 1) ## bypass.asBits).asUInt
        when(ir === JtagInstruction.IDCODE.U(p.irLength)) {
          drShift := BigInt(p.idcode).U(p.drWidth)
        }
        when(ir === JtagInstruction.DTMCS.U(p.irLength)) {
          drShift := (0.B(p.drWidth - 32) ## dtmcs.asBits).asUInt
        }
        when(ir === JtagInstruction.DMI.U(p.irLength)) {
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
        when(ir === JtagInstruction.DMI.U(p.irLength)) {
          drShift := (io.jtag.tdi.asBits ## drShift.asBits.bits(p.drWidth - 1, 1)).asUInt
        }.otherwise {
          when(
            (ir === JtagInstruction.IDCODE.U(p.irLength)) |
              (ir === JtagInstruction.DTMCS.U(p.irLength))
          ) {
            drShift := (0.B(p.drWidth - 32) ## io.jtag.tdi.asBits ## drShift.asBits.bits(31, 1)).asUInt
          }.otherwise {
            drShift := (0.B(p.drWidth - 1) ## io.jtag.tdi.asBits).asUInt
          }
        }
      }
      when(state === TapState.UPDATE_DR.U(4)) {
        when(ir === JtagInstruction.DTMCS.U(p.irLength)) {
          when(drShift.asBits.bit(16)) {
            stickyStatus      := DmiOp.SUCCESS.U(2)
            responseStatusReg := DmiOp.SUCCESS.U(2)
          }
          when(drShift.asBits.bit(17)) {
            requestAddr       := 0.U(p.abits)
            requestData       := 0.U(p.dataBits)
            requestOp         := DmiOp.NOP.U(2)
            outstanding       := false.B
            stickyStatus      := DmiOp.SUCCESS.U(2)
            responseAddrReg   := 0.U(p.abits)
            responseDataReg   := 0.U(p.dataBits)
            responseStatusReg := DmiOp.SUCCESS.U(2)
          }
        }
        when(ir === JtagInstruction.DMI.U(p.irLength)) {
          val updateOp = drShift.asBits.bits(1, 0).asUInt
          when((stickyStatus === DmiOp.SUCCESS.U(2)) & (updateOp =/= DmiOp.NOP.U(2))) {
            when(outstanding) {
              stickyStatus := DmiOp.BUSY.U(2)
            }.otherwise {
              when((updateOp === DmiOp.READ.U(2)) | (updateOp === DmiOp.WRITE.U(2))) {
                requestAddr   := drShift.asBits.bits(p.drWidth - 1, p.drWidth - p.abits).asUInt
                requestData   := drShift.asBits.bits(p.drWidth - p.abits - 1, 2).asUInt
                requestOp     := updateOp
                requestToggle := !requestToggle
                outstanding   := true.B
              }.otherwise {
                stickyStatus := DmiOp.FAILED.U(2)
              }
            }
          }
        }
        when(
          (ir =/= JtagInstruction.IDCODE.U(p.irLength)) &
            (ir =/= JtagInstruction.DTMCS.U(p.irLength)) &
            (ir =/= JtagInstruction.DMI.U(p.irLength))
        ) {
          bypass := drShift.asBits.bit(0)
        }
      }

      when(!io.jtag.trstN) {
        state             := TapState.TEST_LOGIC_RESET.U(4)
        ir                := JtagInstruction.IDCODE.U(p.irLength)
        irShift           := 1.U(p.irLength)
        drShift           := 0.U(p.drWidth)
        bypass            := false.B
        requestAddr       := 0.U(p.abits)
        requestData       := 0.U(p.dataBits)
        requestOp         := DmiOp.NOP.U(2)
        outstanding       := false.B
        responseAddrReg   := 0.U(p.abits)
        responseDataReg   := 0.U(p.dataBits)
        stickyStatus      := DmiOp.SUCCESS.U(2)
        responseStatusReg := DmiOp.SUCCESS.U(2)
      }

      (requestToggle, requestAddr, requestData, requestOp)
    }

    // ---- the bus transaction: one request per toggle edge, the response toggled back ----
    requestToggleMeta := requestToggle
    requestToggleSync := requestToggleMeta
    when(requestToggleSync =/= requestToggleSeen) {
      requestToggleSeen := requestToggleSync
      busAddr           := requestAddr
      busData           := requestData
      busOp             := requestOp
      busState          := 1.U(2)
    }

    io.dmi.req.valid     := busState === 1.U(2)
    io.dmi.req.bits.addr := busAddr
    io.dmi.req.bits.data := busData
    io.dmi.req.bits.op   := busOp
    when((busState === 1.U(2)) & io.dmi.req.ready) { busState := 2.U(2) }

    io.dmi.resp.ready := busState === 2.U(2)
    when((busState === 2.U(2)) & io.dmi.resp.valid) {
      busState       := 0.U(2)
      responseData   := io.dmi.resp.bits.data
      responseOp     := io.dmi.resp.bits.op
      responseToggle := !responseToggle
    }
