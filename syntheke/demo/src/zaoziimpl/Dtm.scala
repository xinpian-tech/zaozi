package me.jiuyang.syntheke.demo.zaoziimpl

import com.vowstar.ditdah32.{DmiOp, JtagInstruction, TapState}
import me.jiuyang.stdlib.default.{SynchronizedReset, SynchronizedResetParameter}
import me.jiuyang.zaozi.*
import org.llvm.circt.scalalib.dialect.firrtl.operation.RegResetPolarity
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import upickle.default.ReadWriter


case class DtmP(idcode: Long, irLength: Int, abits: Int, dataBits: Int) extends Parameter derives ReadWriter:
  require(idcode >= 0L && idcode <= 0xffffffffL, s"idcode 0x${idcode.toHexString} must fit in 32 bits")
  require((idcode & 1L) == 1L, "idcode bit 0 must be one")
  require(irLength == 5, s"the TAP's instruction register is 5 bits, got $irLength")
  require(abits >= 1 && abits <= 63, s"DMI address width $abits must be within 1..63")
  require(dataBits == 32, s"the DMI data field is 32 bits, got $dataBits")
  val drWidth: Int = abits + dataBits + 2

class DtmPLayers(p: DtmP) extends LayerInterface(p):
  def layers = Seq(Layer("Verification"))
class DtmPProbe(p: DtmP)  extends DVBundle[DtmP, DtmPLayers](p)
class DtmPIO(p: DtmP)     extends HWBundle(p):
  val tck  = Flipped(new ClockBundle)
  val jtag = Aligned(new JtagBundle)
  val dmi  = Aligned(new DmiBundle(p.abits, p.dataBits))

@generator
object DtmGen extends Generator[DtmP, DtmPLayers, DtmPIO, DtmPProbe]:
  def architecture(p: DtmP) =
    val io = summon[Interface[DtmPIO]]

    val resetSync = SynchronizedReset.instantiate(SynchronizedResetParameter(stages = 2, RegResetPolarity.PosReset))
    resetSync.io.clock := io.tck.clock
    resetSync.io.reset := io.tck.reset

    given ClockScope = ClockScope.posedge(io.tck.clock)
    given ResetScope = ResetScope.asyncActiveHigh(resetSync.io.synchronizedReset)

    locally {
      val state   = RegInit(TapState.TEST_LOGIC_RESET.B(4))
      val ir      = RegInit(JtagInstruction.IDCODE.B(p.irLength))
      val irShift = RegInit(1.B(p.irLength))
      val drShift = RegInit(0.B(p.drWidth))
      val bypass  = RegInit(false.B)

      val reqValid    = RegInit(false.B)
      val requestAddr = RegInit(0.B(p.abits))
      val requestData = RegInit(0.B(p.dataBits))
      val requestOp   = RegInit(DmiOp.NOP.B(2))
      val outstanding = RegInit(false.B)

      val stickyStatus = RegInit(DmiOp.SUCCESS.B(2))

      val nextState = Wire(Bits(4))
      nextState := TapState.TEST_LOGIC_RESET.B(4)
      when(state === TapState.TEST_LOGIC_RESET.B(4)) {
        nextState := io.jtag.tms.?(TapState.TEST_LOGIC_RESET.B(4), TapState.RUN_TEST_IDLE.B(4))
      }
      when(state === TapState.RUN_TEST_IDLE.B(4)) {
        nextState := io.jtag.tms.?(TapState.SELECT_DR_SCAN.B(4), TapState.RUN_TEST_IDLE.B(4))
      }
      when(state === TapState.SELECT_DR_SCAN.B(4)) {
        nextState := io.jtag.tms.?(TapState.SELECT_IR_SCAN.B(4), TapState.CAPTURE_DR.B(4))
      }
      when(state === TapState.CAPTURE_DR.B(4)) {
        nextState := io.jtag.tms.?(TapState.EXIT1_DR.B(4), TapState.SHIFT_DR.B(4))
      }
      when(state === TapState.SHIFT_DR.B(4)) {
        nextState := io.jtag.tms.?(TapState.EXIT1_DR.B(4), TapState.SHIFT_DR.B(4))
      }
      when(state === TapState.EXIT1_DR.B(4)) {
        nextState := io.jtag.tms.?(TapState.UPDATE_DR.B(4), TapState.PAUSE_DR.B(4))
      }
      when(state === TapState.PAUSE_DR.B(4)) {
        nextState := io.jtag.tms.?(TapState.EXIT2_DR.B(4), TapState.PAUSE_DR.B(4))
      }
      when(state === TapState.EXIT2_DR.B(4)) {
        nextState := io.jtag.tms.?(TapState.UPDATE_DR.B(4), TapState.SHIFT_DR.B(4))
      }
      when(state === TapState.UPDATE_DR.B(4)) {
        nextState := io.jtag.tms.?(TapState.SELECT_DR_SCAN.B(4), TapState.RUN_TEST_IDLE.B(4))
      }
      when(state === TapState.SELECT_IR_SCAN.B(4)) {
        nextState := io.jtag.tms.?(TapState.TEST_LOGIC_RESET.B(4), TapState.CAPTURE_IR.B(4))
      }
      when(state === TapState.CAPTURE_IR.B(4)) {
        nextState := io.jtag.tms.?(TapState.EXIT1_IR.B(4), TapState.SHIFT_IR.B(4))
      }
      when(state === TapState.SHIFT_IR.B(4)) {
        nextState := io.jtag.tms.?(TapState.EXIT1_IR.B(4), TapState.SHIFT_IR.B(4))
      }
      when(state === TapState.EXIT1_IR.B(4)) {
        nextState := io.jtag.tms.?(TapState.UPDATE_IR.B(4), TapState.PAUSE_IR.B(4))
      }
      when(state === TapState.PAUSE_IR.B(4)) {
        nextState := io.jtag.tms.?(TapState.EXIT2_IR.B(4), TapState.PAUSE_IR.B(4))
      }
      when(state === TapState.EXIT2_IR.B(4)) {
        nextState := io.jtag.tms.?(TapState.UPDATE_IR.B(4), TapState.SHIFT_IR.B(4))
      }
      when(state === TapState.UPDATE_IR.B(4)) {
        nextState := io.jtag.tms.?(TapState.SELECT_DR_SCAN.B(4), TapState.RUN_TEST_IDLE.B(4))
      }

      val tdo = Wire(Bool())
      tdo         := false.B
      when(state === TapState.SHIFT_IR.B(4)) {
        tdo := irShift.bit(0)
      }
      when(state === TapState.SHIFT_DR.B(4)) {
        tdo := drShift.bit(0)
      }
      io.jtag.tdo := tdo

      val captureDmi = (state === TapState.CAPTURE_DR.B(4)) & (ir === JtagInstruction.DMI.B(p.irLength))
      val busyNow    = outstanding & (!io.dmi.resp.valid)

      io.dmi.resp.ready := io.dmi.resp.valid
      when(outstanding & (requestOp === DmiOp.READ.B(2))) {
        io.dmi.resp.ready := captureDmi & (!busyNow)
      }
      when(io.dmi.resp.valid & io.dmi.resp.ready) { outstanding := false.B }

      when(
        io.dmi.resp.valid & io.dmi.resp.ready & outstanding &
          (io.dmi.resp.bits.op =/= DmiOp.SUCCESS.B(2))
      ) {
        stickyStatus := io.dmi.resp.bits.op
      }

      val dmiCaptureStatus = Wire(Bits(2))
      dmiCaptureStatus := DmiOp.SUCCESS.B(2)
      when(io.dmi.resp.valid & outstanding) { dmiCaptureStatus := io.dmi.resp.bits.op }
      when(stickyStatus =/= DmiOp.SUCCESS.B(2)) { dmiCaptureStatus := stickyStatus }
      when(busyNow) { dmiCaptureStatus := DmiOp.BUSY.B(2) }

      val dmiCapture = Wire(Bits(p.drWidth))
      dmiCapture := 0.B(p.abits + p.dataBits) ## dmiCaptureStatus
      when(io.dmi.resp.valid & outstanding & (!busyNow) & (stickyStatus === DmiOp.SUCCESS.B(2))) {
        dmiCapture := requestAddr ## io.dmi.resp.bits.data ## io.dmi.resp.bits.op
      }

      val dtmcs = Wire(Bits(32))
      dtmcs := (
        0.B(11) ##
          0.B(3) ##
          0.B(1) ##
          0.B(1) ##
          0.B(1) ##
          7.B(3) ##
          stickyStatus ##
          p.abits.B(6) ##
          1.B(4)
      )

      io.dmi.req.valid     := reqValid
      io.dmi.req.bits.addr := requestAddr
      io.dmi.req.bits.data := requestData
      io.dmi.req.bits.op   := requestOp
      when(reqValid & io.dmi.req.ready) { reqValid := false.B }

      state := nextState

      when(state === TapState.TEST_LOGIC_RESET.B(4)) {
        ir           := JtagInstruction.IDCODE.B(p.irLength)
        reqValid     := false.B
        requestAddr  := 0.B(p.abits)
        requestData  := 0.B(p.dataBits)
        requestOp    := DmiOp.NOP.B(2)
        outstanding  := false.B
        stickyStatus := DmiOp.SUCCESS.B(2)
      }

      when(state === TapState.CAPTURE_IR.B(4)) {
        irShift := 1.B(p.irLength)
      }
      when(state === TapState.SHIFT_IR.B(4)) {
        irShift := (io.jtag.tdi.asBits ## irShift.bits(p.irLength - 1, 1))
      }
      when(state === TapState.UPDATE_IR.B(4)) {
        ir := irShift
      }

      when(state === TapState.CAPTURE_DR.B(4)) {
        drShift := (0.B(p.drWidth - 1) ## bypass.asBits)
        when(ir === JtagInstruction.IDCODE.B(p.irLength)) {
          drShift := BigInt(p.idcode).B(p.drWidth)
        }
        when(ir === JtagInstruction.DTMCS.B(p.irLength)) {
          drShift := (0.B(p.drWidth - 32) ## dtmcs)
        }
        when(ir === JtagInstruction.DMI.B(p.irLength)) {
          drShift := dmiCapture
          when((stickyStatus === DmiOp.SUCCESS.B(2)) & busyNow) {
            stickyStatus := DmiOp.BUSY.B(2)
          }
        }
      }
      when(state === TapState.SHIFT_DR.B(4)) {
        when(ir === JtagInstruction.DMI.B(p.irLength)) {
          drShift := (io.jtag.tdi.asBits ## drShift.bits(p.drWidth - 1, 1))
        }.otherwise {
          when(
            (ir === JtagInstruction.IDCODE.B(p.irLength)) |
              (ir === JtagInstruction.DTMCS.B(p.irLength))
          ) {
            drShift := (0.B(p.drWidth - 32) ## io.jtag.tdi.asBits ## drShift.bits(31, 1))
          }.otherwise {
            drShift := (0.B(p.drWidth - 1) ## io.jtag.tdi.asBits)
          }
        }
      }
      when(state === TapState.UPDATE_DR.B(4)) {
        when(ir === JtagInstruction.DTMCS.B(p.irLength)) {
          when(drShift.bit(16)) {
            stickyStatus := DmiOp.SUCCESS.B(2)
          }
          when(drShift.bit(17)) {
            reqValid     := false.B
            requestAddr  := 0.B(p.abits)
            requestData  := 0.B(p.dataBits)
            requestOp    := DmiOp.NOP.B(2)
            outstanding  := false.B
            stickyStatus := DmiOp.SUCCESS.B(2)
          }
        }
        when(ir === JtagInstruction.DMI.B(p.irLength)) {
          val updateOp = drShift.bits(1, 0)
          when((stickyStatus === DmiOp.SUCCESS.B(2)) & (updateOp =/= DmiOp.NOP.B(2))) {
            when(busyNow | io.dmi.resp.valid) {
              stickyStatus := DmiOp.BUSY.B(2)
            }.otherwise {
              when((updateOp === DmiOp.READ.B(2)) | (updateOp === DmiOp.WRITE.B(2))) {
                requestAddr := drShift.bits(p.drWidth - 1, p.drWidth - p.abits)
                requestData := drShift.bits(p.drWidth - p.abits - 1, 2)
                requestOp   := updateOp
                reqValid    := true.B
                outstanding := true.B
              }.otherwise {
                stickyStatus := DmiOp.FAILED.B(2)
              }
            }
          }
        }
        when(
          (ir =/= JtagInstruction.IDCODE.B(p.irLength)) &
            (ir =/= JtagInstruction.DTMCS.B(p.irLength)) &
            (ir =/= JtagInstruction.DMI.B(p.irLength))
        ) {
          bypass := drShift.bit(0)
        }
      }

      when(io.tck.reset.asBool) {
        state        := TapState.TEST_LOGIC_RESET.B(4)
        ir           := JtagInstruction.IDCODE.B(p.irLength)
        irShift      := 1.B(p.irLength)
        drShift      := 0.B(p.drWidth)
        bypass       := false.B
        reqValid     := false.B
        requestAddr  := 0.B(p.abits)
        requestData  := 0.B(p.dataBits)
        requestOp    := DmiOp.NOP.B(2)
        outstanding  := false.B
        stickyStatus := DmiOp.SUCCESS.B(2)
      }

    }
