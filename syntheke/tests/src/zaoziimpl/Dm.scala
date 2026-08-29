// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import com.vowstar.ditdah32.{AbstractCommandError, AbstractCommandType, DebugRegister, DmiOp}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import upickle.default.ReadWriter

import java.lang.foreign.Arena

/** The debug module, lifted out of DitDah32 into its own IP and widened to a hart array: a DMI slave holding the debug
  * register file, with one port per hart. `dmcontrol.hartsello` selects which hart the halt, resume and abstract
  * commands act on; `haltsum0` reports all of them at once. Each hart keeps its own halt request, halt-on-reset
  * request, resume acknowledgement and have-reset flag, so a selection change never disturbs a hart left halted.
  *
  * `haltOnReset` is the SoC's halt-on-reset strap: the module comes out of reset active with every hart's halt request
  * asserted, so the harts park in debug mode before fetching and a debugger arriving over JTAG finds them waiting.
  */

case class DmP(harts: Int, abits: Int, dataBits: Int, xlen: Int, haltOnReset: Boolean) extends Parameter
    derives ReadWriter:
  require(harts >= 1 && harts <= 1024, s"hart count $harts must be within 1..1024")
  require(abits >= 7, s"the debug register file needs at least 7 address bits, got $abits")
  require(dataBits == 32, s"the debug data registers are 32 bits, got $dataBits")
  require(xlen == 32, s"the abstract command path is 32 bits wide, got xlen $xlen")
  val selBits: Int = math.max(1, 32 - Integer.numberOfLeadingZeros(math.max(1, harts - 1)))

class DmPLayers(p: DmP) extends LayerInterface(p):
  def layers = Seq.empty
class DmPProbe(p: DmP)  extends DVRecord[DmP, DmPLayers](p)
class DmPIO(p: DmP)     extends HWRecord(p):
  val clk   = Flipped("clk", new ClockRecord)
  val dmi   = Flipped("dmi", new DmiRecord(p.abits, p.dataBits))
  val harts = (0 until p.harts).map(i => Aligned(s"hart$i", new DebugHartRecord(p.xlen)))

@generator
object DmGen extends Generator[DmP, DmPLayers, DmPIO, DmPProbe]:
  def architecture(p: DmP) =
    val io           = summon[Interface[DmPIO]]
    given ClockScope = ClockScope.posedge(io.field[Record]("clk").field[Clock]("clock"))
    given ResetScope = ResetScope.asyncActiveHigh(io.field[Record]("clk").field[Reset]("reset"))

    // Field access must build in the caller's block, so every accessor carries the context.
    def hart(
      i: Int
    )(
      using Arena,
      Context,
      Block
    ) = io.field[Record](s"hart$i")
    def cmdOf(
      i: Int
    )(
      using Arena,
      Context,
      Block
    ) = hart(i).field[Record]("cmd")
    def statusOf(
      i: Int
    )(
      using Arena,
      Context,
      Block
    ) = hart(i).field[Record]("hart")
    def req(
      using Arena,
      Context,
      Block
    ) = io.field[Record]("dmi").field[Record]("req")
    def resp(
      using Arena,
      Context,
      Block
    ) = io.field[Record]("dmi").field[Record]("resp")
    def reqBits(
      using Arena,
      Context,
      Block
    ) = req.field[Record]("bits")

    val n = p.harts

    // ---- the register file ----
    val dmactive  = RegInit(p.haltOnReset.B)
    val ndmreset  = RegInit(false.B)
    val hartreset = RegInit(false.B)
    val hartsel   = RegInit(0.U(p.selBits))

    // Per-hart state: a selection change must never disturb a hart left halted.
    val haltRequest      = Vector.fill(n)(RegInit(p.haltOnReset.B))
    val resetHaltRequest = Vector.fill(n)(RegInit(false.B))
    val resumeAck        = Vector.fill(n)(RegInit(false.B))
    val haveReset        = Vector.fill(n)(RegInit(false.B))

    val data0        = RegInit(0.U(p.dataBits))
    val data1        = RegInit(0.U(p.dataBits))
    val command      = RegInit(0.U(32))
    val abstractBusy = RegInit(false.B)
    val commandError = RegInit(AbstractCommandError.NONE.U(3))
    val abstractHart = RegInit(0.U(p.selBits))

    val resumeReqReg       = RegInit(false.B)
    val abstractValidReg   = RegInit(false.B)
    val abstractCmdKindReg = RegInit(0.U(2))
    val abstractWriteReg   = RegInit(false.B)
    val abstractRegnoReg   = RegInit(0.U(16))
    val abstractSizeReg    = RegInit(0.U(3))
    val abstractDataReg    = RegInit(0.U(p.xlen))
    val abstractAddressReg = RegInit(0.U(p.xlen))

    // ---- the selected hart's status, and the acting hart's command completion ----
    val selHalted  = Wire(Bool())
    val selRunning = Wire(Bool())
    val selResume  = Wire(Bool())
    val selReset   = Wire(Bool())
    selHalted  := statusOf(0).field[Bool]("halted")
    selRunning := statusOf(0).field[Bool]("running")
    selResume  := resumeAck(0)
    selReset   := haveReset(0)
    for i <- 1 until n do
      when(hartsel === i.U(p.selBits)) {
        selHalted  := statusOf(i).field[Bool]("halted")
        selRunning := statusOf(i).field[Bool]("running")
        selResume  := resumeAck(i)
        selReset   := haveReset(i)
      }

    val cmdDone  = Wire(Bool())
    val cmdError = Wire(UInt(3))
    val cmdRdata = Wire(UInt(p.xlen))
    cmdDone  := statusOf(0).field[Bool]("cmdDone")
    cmdError := statusOf(0).field[UInt]("cmdError")
    cmdRdata := statusOf(0).field[UInt]("cmdRdata")
    for i <- 1 until n do
      when(abstractHart === i.U(p.selBits)) {
        cmdDone  := statusOf(i).field[Bool]("cmdDone")
        cmdError := statusOf(i).field[UInt]("cmdError")
        cmdRdata := statusOf(i).field[UInt]("cmdRdata")
      }

    // ---- readable registers ----
    val hartselWide = Wire(UInt(10))
    hartselWide := (if p.selBits >= 10 then hartsel.asBits.bits(9, 0).asUInt
                    else (0.B(10 - p.selBits) ## hartsel.asBits).asUInt)

    val selHaltRequest = Wire(Bool())
    selHaltRequest := haltRequest(0)
    for i <- 1 until n do when(hartsel === i.U(p.selBits)) { selHaltRequest := haltRequest(i) }

    val dmcontrolRead = Wire(UInt(32))
    dmcontrolRead := (
      selHaltRequest.asBits ##
        0.B(1) ##
        hartreset.asBits ##
        0.B(3) ##
        hartselWide.asBits ##
        0.B(14) ##
        ndmreset.asBits ##
        dmactive.asBits
    ).asUInt

    val dmstatusRead = Wire(UInt(32))
    dmstatusRead := (
      0.B(7) ##
        ndmreset.asBits ##
        0.B(4) ##
        selReset.asBits ##
        selReset.asBits ##
        selResume.asBits ##
        selResume.asBits ##
        0.B(4) ##
        selRunning.asBits ##
        selRunning.asBits ##
        selHalted.asBits ##
        selHalted.asBits ##
        1.B(1) ##
        0.B(1) ##
        1.B(1) ##
        0.B(1) ##
        3.B(4)
    ).asUInt

    val abstractcsRead = Wire(UInt(32))
    abstractcsRead := (
      0.B(19) ##
        abstractBusy.asBits ##
        0.B(1) ##
        commandError.asBits ##
        0.B(4) ##
        2.B(4)
    ).asUInt

    // Every hart's halted bit at once, hart 0 in bit 0.
    val haltsum0Read = Wire(UInt(32))
    haltsum0Read := (0.B(32 - n) ## Vector
      .tabulate(n)(i => statusOf(n - 1 - i).field[Bool]("halted").asBits)
      .reduce(
        _ ## _
      )).asUInt

    val dmiReadData = Wire(UInt(p.dataBits))
    dmiReadData := 0.U(p.dataBits)
    when(reqBits.field[UInt]("addr") === DebugRegister.DATA0.U(p.abits)) {
      dmiReadData := data0
    }
    when(reqBits.field[UInt]("addr") === DebugRegister.DATA1.U(p.abits)) {
      dmiReadData := data1
    }
    when(reqBits.field[UInt]("addr") === DebugRegister.DMCONTROL.U(p.abits)) {
      dmiReadData := dmcontrolRead
    }
    when(reqBits.field[UInt]("addr") === DebugRegister.DMSTATUS.U(p.abits)) {
      dmiReadData := dmstatusRead
    }
    when(reqBits.field[UInt]("addr") === DebugRegister.ABSTRACTCS.U(p.abits)) {
      dmiReadData := abstractcsRead
    }
    when(reqBits.field[UInt]("addr") === DebugRegister.COMMAND.U(p.abits)) {
      dmiReadData := command
    }
    when(reqBits.field[UInt]("addr") === DebugRegister.HALTSUM0.U(p.abits)) {
      dmiReadData := haltsum0Read
    }

    // ---- the DMI slave: one transaction at a time, answered the cycle after it is accepted ----
    val respValid = RegInit(false.B)
    val respData  = RegInit(0.U(p.dataBits))
    val respOp    = RegInit(DmiOp.SUCCESS.U(2))

    req.field[Bool]("ready")                       := !respValid
    resp.field[Bool]("valid")                      := respValid
    resp.field[Record]("bits").field[UInt]("data") := respData
    resp.field[Record]("bits").field[UInt]("op")   := respOp
    when(respValid & resp.field[Bool]("ready")) { respValid := false.B }

    resumeReqReg     := false.B
    abstractValidReg := false.B

    for i <- 0 until n do
      when(statusOf(i).field[Bool]("resumeAck")) { resumeAck(i) := true.B }
      when(statusOf(i).field[Bool]("resetAck")) {
        haveReset(i) := true.B
        resumeAck(i) := false.B
      }

    when(cmdDone & abstractBusy) {
      abstractBusy := false.B
      when(cmdError =/= AbstractCommandError.NONE.U(3)) {
        commandError := cmdError
      }.otherwise {
        when(!abstractWriteReg) {
          data0 := cmdRdata
        }
        when(command.asBits.bits(31, 24) === AbstractCommandType.ACCESS_REGISTER.B(8)) {
          when(command.asBits.bit(19) & command.asBits.bit(17)) {
            command := (
              command.asBits.bits(31, 16) ##
                (command.asBits.bits(15, 0).asUInt + 1.U(16)).asBits.bits(15, 0)
            ).asUInt
          }
        }
        when(command.asBits.bits(31, 24) === AbstractCommandType.ACCESS_MEMORY.B(8)) {
          when(command.asBits.bit(19)) {
            when(abstractSizeReg === 0.U(3)) {
              data1 := (data1 + 1.U(p.dataBits)).asBits.bits(p.dataBits - 1, 0).asUInt
            }
            when(abstractSizeReg === 1.U(3)) {
              data1 := (data1 + 2.U(p.dataBits)).asBits.bits(p.dataBits - 1, 0).asUInt
            }
            when(abstractSizeReg === 2.U(3)) {
              data1 := (data1 + 4.U(p.dataBits)).asBits.bits(p.dataBits - 1, 0).asUInt
            }
          }
        }
      }
    }

    val reqFire = req.field[Bool]("valid") & (!respValid)
    when(reqFire) {
      respValid := true.B
      respData  := dmiReadData
      respOp    := DmiOp.SUCCESS.U(2)

      val addr = reqBits.field[UInt]("addr")
      val data = reqBits.field[UInt]("data")

      when(reqBits.field[UInt]("op") === DmiOp.READ.U(2)) {
        when(
          abstractBusy &
            ((addr === DebugRegister.DATA0.U(p.abits)) |
              (addr === DebugRegister.DATA1.U(p.abits)))
        ) {
          when(commandError === AbstractCommandError.NONE.U(3)) {
            commandError := AbstractCommandError.BUSY.U(3)
          }
        }
      }

      when(reqBits.field[UInt]("op") === DmiOp.WRITE.U(2)) {
        when(addr === DebugRegister.DMCONTROL.U(p.abits)) {
          when(!data.asBits.bit(0)) {
            dmactive     := false.B
            ndmreset     := false.B
            hartreset    := false.B
            data0        := 0.U(p.dataBits)
            data1        := 0.U(p.dataBits)
            command      := 0.U(32)
            abstractBusy := false.B
            commandError := AbstractCommandError.NONE.U(3)
            for i <- 0 until n do
              haltRequest(i)      := false.B
              resetHaltRequest(i) := false.B
              resumeAck(i)        := false.B
          }.otherwise {
            dmactive  := true.B
            ndmreset  := data.asBits.bit(1)
            hartreset := data.asBits.bit(29)
            hartsel   := data.asBits.bits(16 + p.selBits - 1, 16).asUInt
            when(!abstractBusy) {
              // The write acts on the hart it selects, which is the one this very write names.
              val target = Wire(UInt(p.selBits))
              target := data.asBits.bits(16 + p.selBits - 1, 16).asUInt
              for i <- 0 until n do
                when(target === i.U(p.selBits)) {
                  haltRequest(i) := data.asBits.bit(31)
                  when(
                    data.asBits.bit(30) &
                      !data.asBits.bit(31) &
                      selHalted
                  ) {
                    resumeReqReg := true.B
                    resumeAck(i) := false.B
                  }
                  when(data.asBits.bit(28)) {
                    haveReset(i) := false.B
                  }
                  when(data.asBits.bit(3)) {
                    resetHaltRequest(i) := true.B
                  }
                  when(data.asBits.bit(2)) {
                    resetHaltRequest(i) := false.B
                  }
                }
            }
          }
        }

        when(dmactive & (addr === DebugRegister.DATA0.U(p.abits))) {
          when(abstractBusy) {
            when(commandError === AbstractCommandError.NONE.U(3)) {
              commandError := AbstractCommandError.BUSY.U(3)
            }
          }.otherwise {
            data0 := data
          }
        }
        when(dmactive & (addr === DebugRegister.DATA1.U(p.abits))) {
          when(abstractBusy) {
            when(commandError === AbstractCommandError.NONE.U(3)) {
              commandError := AbstractCommandError.BUSY.U(3)
            }
          }.otherwise {
            data1 := data
          }
        }
        when(dmactive & (addr === DebugRegister.ABSTRACTCS.U(p.abits))) {
          when(abstractBusy) {
            when(commandError === AbstractCommandError.NONE.U(3)) {
              commandError := AbstractCommandError.BUSY.U(3)
            }
          }.otherwise {
            commandError := (
              commandError.asBits &
                (data.asBits.bits(10, 8) ^ 7.B(3))
            ).asUInt
          }
        }
        when(dmactive & (addr === DebugRegister.COMMAND.U(p.abits))) {
          when(abstractBusy) {
            when(commandError === AbstractCommandError.NONE.U(3)) {
              commandError := AbstractCommandError.BUSY.U(3)
            }
          }.otherwise {
            when(commandError === AbstractCommandError.NONE.U(3)) {
              command := data
              when(data.asBits.bits(31, 24) === AbstractCommandType.ACCESS_REGISTER.B(8)) {
                when(
                  data.asBits.bit(23) |
                    data.asBits.bit(18) |
                    (data.asBits.bits(22, 20) =/= 2.B(3))
                ) {
                  commandError := AbstractCommandError.NOT_SUPPORTED.U(3)
                }.otherwise {
                  when(data.asBits.bit(17)) {
                    when(!selHalted) {
                      commandError := AbstractCommandError.HALT_OR_RESUME.U(3)
                    }.otherwise {
                      abstractBusy       := true.B
                      abstractValidReg   := true.B
                      abstractHart       := hartsel
                      abstractCmdKindReg := AbstractCommandType.ACCESS_REGISTER.U(2)
                      abstractWriteReg   := data.asBits.bit(16)
                      abstractRegnoReg   := data.asBits.bits(15, 0).asUInt
                      abstractSizeReg    := 2.U(3)
                      abstractDataReg    := data0
                      abstractAddressReg := 0.U(p.xlen)
                    }
                  }
                }
              }.otherwise {
                when(data.asBits.bits(31, 24) === AbstractCommandType.ACCESS_MEMORY.B(8)) {
                  when(
                    (data.asBits.bits(22, 20).asUInt > 2.U(3)) |
                      (data.asBits.bits(18, 17) =/= 0.B(2)) |
                      (data.asBits.bits(15, 14) =/= 0.B(2)) |
                      (data.asBits.bits(13, 0) =/= 0.B(14))
                  ) {
                    commandError := AbstractCommandError.NOT_SUPPORTED.U(3)
                  }.otherwise {
                    when(!selHalted) {
                      commandError := AbstractCommandError.HALT_OR_RESUME.U(3)
                    }.otherwise {
                      abstractBusy       := true.B
                      abstractValidReg   := true.B
                      abstractHart       := hartsel
                      abstractCmdKindReg := AbstractCommandType.ACCESS_MEMORY.U(2)
                      abstractWriteReg   := data.asBits.bit(16)
                      abstractRegnoReg   := 0.U(16)
                      abstractSizeReg    := data.asBits.bits(22, 20).asUInt
                      abstractDataReg    := data0
                      abstractAddressReg := data1
                    }
                  }
                }.otherwise {
                  commandError := AbstractCommandError.NOT_SUPPORTED.U(3)
                }
              }
            }
          }
        }
      }
    }

    // ---- the hart ports: requests to the hart the debugger selected, the command to the one it was issued for ----
    for i <- 0 until n do
      hart(i).field[Bool]("halt")        := dmactive & haltRequest(i)
      hart(i).field[Bool]("resume")      := dmactive & resumeReqReg & (hartsel === i.U(p.selBits))
      hart(i).field[Bool]("reset")       := dmactive & (ndmreset | hartreset)
      hart(i).field[Bool]("haltOnReset") := dmactive & resetHaltRequest(i)

      cmdOf(i).field[Bool]("valid")   := abstractValidReg & (abstractHart === i.U(p.selBits))
      cmdOf(i).field[UInt]("kind")    := abstractCmdKindReg
      cmdOf(i).field[Bool]("write")   := abstractWriteReg
      cmdOf(i).field[UInt]("regno")   := abstractRegnoReg
      cmdOf(i).field[UInt]("size")    := abstractSizeReg
      cmdOf(i).field[UInt]("data")    := abstractDataReg
      cmdOf(i).field[UInt]("address") := abstractAddressReg
