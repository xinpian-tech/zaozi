// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo.zaoziimpl

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
  * `hartsello` is the full ten bits the spec gives it, and a selection past the last hart reads back as nonexistent:
  * that is how a debugger sizes the field and then counts the harts, by selecting upwards until one is not there.
  *
  * Memory is reached over the system bus port `sb`: the module is an AXI master of its own, so a debugger can read and
  * write memory without borrowing a hart. The alternative the spec allows — the program buffer — needs the hart to
  * execute the debugger's instructions, which this SoC's core does not do.
  *
  * `haltOnReset` is the SoC's halt-on-reset strap: the module comes out of reset active with every hart's halt request
  * asserted, so the harts park in debug mode before fetching and a debugger arriving over JTAG finds them waiting.
  */

case class DmP(
  harts:       Int,
  abits:       Int,
  dataBits:    Int,
  xlen:        Int,
  haltOnReset: Boolean,
  sbAddrBits:  Int,
  sbDataBits:  Int,
  sbIdBits:    Int)
    extends Parameter derives ReadWriter:
  require(harts >= 1 && harts <= 1024, s"hart count $harts must be within 1..1024")
  require(abits >= 7, s"the debug register file needs at least 7 address bits, got $abits")
  require(dataBits == 32, s"the debug data registers are 32 bits, got $dataBits")
  require(xlen == 32, s"the abstract command path is 32 bits wide, got xlen $xlen")
  require(sbAddrBits >= 1 && sbAddrBits <= 32, s"the system bus addresses a 32-bit space, got $sbAddrBits")
  require(sbDataBits == 32 || sbDataBits == 128, s"the system bus rides a 32- or 128-bit fabric, got $sbDataBits")
  def sbShape: AxiShape = AxiShape(sbAddrBits, sbDataBits, sbIdBits)

  /** The width of `dmcontrol.hartsello`, which is what a debugger measures to learn how many harts it may select. */
  val hartselBits: Int = 10

class DmPLayers(p: DmP) extends LayerInterface(p):
  def layers = Seq.empty
class DmPProbe(p: DmP)  extends DVRecord[DmP, DmPLayers](p)
class DmPIO(p: DmP)     extends HWRecord(p):
  val clk   = Flipped("clk", new ClockRecord)
  val dmi   = Flipped("dmi", new DmiRecord(p.abits, p.dataBits))
  val sb    = Aligned("sb", new AxiPortRecord(p.sbShape))
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
    def sb(
      using Arena,
      Context,
      Block
    ) = io.field[Record]("sb")
    def sbCh(
      name: String
    )(
      using Arena,
      Context,
      Block
    ) = sb.field[Record](name)
    def sbBits(
      name: String
    )(
      using Arena,
      Context,
      Block
    ) = sbCh(name).field[Record]("bits")

    val n   = p.harts
    val sel = p.hartselBits

    // ---- the register file ----
    val dmactive  = RegInit(p.haltOnReset.B)
    val ndmreset  = RegInit(false.B)
    val hartreset = RegInit(false.B)
    val hartsel   = RegInit(0.U(sel))

    // Per-hart state: a selection change must never disturb a hart left halted.
    val haltRequest      = Vector.fill(n)(RegInit(p.haltOnReset.B))
    val resetHaltRequest = Vector.fill(n)(RegInit(false.B))
    val resumeAck        = Vector.fill(n)(RegInit(false.B))
    val haveReset        = Vector.fill(n)(RegInit(false.B))

    val data0        = RegInit(0.B(p.dataBits))
    val data1        = RegInit(0.U(p.dataBits))
    val command      = RegInit(0.B(32))
    val abstractBusy = RegInit(false.B)
    val commandError = RegInit(AbstractCommandError.NONE.B(3))
    val abstractHart = RegInit(0.B(sel))

    val resumeReqReg       = RegInit(false.B)
    val abstractValidReg   = RegInit(false.B)
    val abstractCmdKindReg = RegInit(0.B(2))
    val abstractWriteReg   = RegInit(false.B)
    val abstractRegnoReg   = RegInit(0.B(16))
    val abstractSizeReg    = RegInit(0.B(3))
    val abstractDataReg    = RegInit(0.B(p.xlen))
    val abstractAddressReg = RegInit(0.B(p.xlen))

    // The system bus master's register file, and the one transaction it may have in flight.
    val sbAccess     = RegInit(2.B(3))
    val sbAutoInc    = RegInit(false.B)
    val sbReadOnAddr = RegInit(false.B)
    val sbReadOnData = RegInit(false.B)
    val sbError      = RegInit(0.B(3))
    val sbBusyError  = RegInit(false.B)
    val sbAddress    = RegInit(0.U(32))
    val sbData       = RegInit(0.B(32))
    val sbState      = RegInit(0.B(2)) // 0 idle, 1 write, 2 read
    val sbLane       = RegInit(0.B(2)) // which word of a wide beat this address rides in
    val sbAwDone     = RegInit(false.B)
    val sbWDone      = RegInit(false.B)
    val sbArDone     = RegInit(false.B)

    val sbBusy    = Wire(Bool())
    val sbBlocked = Wire(Bool())
    sbBusy    := sbState =/= 0.B(2)
    // While an error stands, the spec forbids starting another access until the debugger clears it.
    sbBlocked := sbBusy | sbBusyError | (sbError =/= 0.B(3))

    // ---- the selected hart's status, and the acting hart's command completion. A selection past the last hart
    // matches nothing, so it reports as neither halted nor running — which is what nonexistent means. ----
    val selHalted   = Wire(Bool())
    val selRunning  = Wire(Bool())
    val selResume   = Wire(Bool())
    val selReset    = Wire(Bool())
    val selNotThere = Wire(Bool())
    selHalted   := false.B
    selRunning  := false.B
    selResume   := false.B
    selReset    := false.B
    selNotThere := hartsel > (n - 1).U(sel)
    for i <- 0 until n do
      when(hartsel === i.U(sel)) {
        selHalted  := statusOf(i).field[Bool]("halted")
        selRunning := statusOf(i).field[Bool]("running")
        selResume  := resumeAck(i)
        selReset   := haveReset(i)
      }

    val cmdDone  = Wire(Bool())
    val cmdError = Wire(Bits(3))
    val cmdRdata = Wire(Bits(p.xlen))
    cmdDone  := false.B
    cmdError := AbstractCommandError.NONE.B(3)
    cmdRdata := 0.B(p.xlen)
    for i <- 0 until n do
      when(abstractHart === i.B(sel)) {
        cmdDone  := statusOf(i).field[Bool]("cmdDone")
        cmdError := statusOf(i).field[Bits]("cmdError")
        cmdRdata := statusOf(i).field[Bits]("cmdRdata")
      }

    // ---- readable registers ----
    val selHaltRequest = Wire(Bool())
    selHaltRequest := false.B
    for i <- 0 until n do when(hartsel === i.U(sel)) { selHaltRequest := haltRequest(i) }

    val dmcontrolRead = Wire(Bits(32))
    dmcontrolRead := (
      selHaltRequest.asBits ##
        0.B(1) ##
        hartreset.asBits ##
        0.B(3) ##
        hartsel.asBits ##
        0.B(14) ##
        ndmreset.asBits ##
        dmactive.asBits
    )

    val dmstatusRead = Wire(Bits(32))
    dmstatusRead := (
      0.B(7) ##
        ndmreset.asBits ##
        0.B(4) ##
        selReset.asBits ##
        selReset.asBits ##
        selResume.asBits ##
        selResume.asBits ##
        selNotThere.asBits ##
        selNotThere.asBits ##
        0.B(2) ##
        selRunning.asBits ##
        selRunning.asBits ##
        selHalted.asBits ##
        selHalted.asBits ##
        1.B(1) ##
        0.B(1) ##
        1.B(1) ##
        0.B(1) ##
        3.B(4)
    )

    val abstractcsRead = Wire(Bits(32))
    abstractcsRead := (
      0.B(19) ##
        abstractBusy.asBits ##
        0.B(1) ##
        commandError ##
        0.B(4) ##
        2.B(4)
    )

    // The system bus port as the debugger reads it: this port moves words, so it advertises 32-bit accesses only.
    val sbcsRead = Wire(Bits(32))
    sbcsRead := (
      1.B(3) ##
        0.B(6) ##
        sbBusyError.asBits ##
        sbBusy.asBits ##
        sbReadOnAddr.asBits ##
        sbAccess ##
        sbAutoInc.asBits ##
        sbReadOnData.asBits ##
        sbError ##
        p.sbAddrBits.B(7) ##
        0.B(2) ##
        1.B(1) ##
        0.B(2)
    )

    // Every hart's halted bit at once, hart 0 in bit 0.
    val haltsum0Read = Wire(Bits(32))
    haltsum0Read := (0.B(32 - n) ## Vector
      .tabulate(n)(i => statusOf(n - 1 - i).field[Bool]("halted").asBits)
      .reduce(
        _ ## _
      ))

    val dmiReadData = Wire(Bits(p.dataBits))
    dmiReadData := 0.B(p.dataBits)
    when(reqBits.field[Bits]("addr") === DebugRegister.DATA0.B(p.abits)) {
      dmiReadData := data0
    }
    when(reqBits.field[Bits]("addr") === DebugRegister.DATA1.B(p.abits)) {
      dmiReadData := data1.asBits
    }
    when(reqBits.field[Bits]("addr") === DebugRegister.DMCONTROL.B(p.abits)) {
      dmiReadData := dmcontrolRead
    }
    when(reqBits.field[Bits]("addr") === DebugRegister.DMSTATUS.B(p.abits)) {
      dmiReadData := dmstatusRead
    }
    when(reqBits.field[Bits]("addr") === DebugRegister.ABSTRACTCS.B(p.abits)) {
      dmiReadData := abstractcsRead
    }
    when(reqBits.field[Bits]("addr") === DebugRegister.COMMAND.B(p.abits)) {
      dmiReadData := command
    }
    when(reqBits.field[Bits]("addr") === DebugRegister.HALTSUM0.B(p.abits)) {
      dmiReadData := haltsum0Read
    }
    when(reqBits.field[Bits]("addr") === DebugRegister.SBCS.B(p.abits)) {
      dmiReadData := sbcsRead
    }
    when(reqBits.field[Bits]("addr") === DebugRegister.SBADDRESS0.B(p.abits)) {
      dmiReadData := sbAddress.asBits
    }
    when(reqBits.field[Bits]("addr") === DebugRegister.SBDATA0.B(p.abits)) {
      dmiReadData := sbData
    }

    // ---- the DMI slave: one transaction at a time, answered the cycle after it is accepted ----
    val respValid = RegInit(false.B)
    val respData  = RegInit(0.B(p.dataBits))
    val respOp    = RegInit(DmiOp.SUCCESS.B(2))

    req.field[Bool]("ready")                       := !respValid
    resp.field[Bool]("valid")                      := respValid
    resp.field[Record]("bits").field[Bits]("data") := respData
    resp.field[Record]("bits").field[Bits]("op")   := respOp
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
      when(cmdError =/= AbstractCommandError.NONE.B(3)) {
        commandError := cmdError
      }.otherwise {
        when(!abstractWriteReg) {
          data0 := cmdRdata
        }
        when(command.bits(31, 24) === AbstractCommandType.ACCESS_REGISTER.B(8)) {
          when(command.bit(19) & command.bit(17)) {
            command := (
              command.bits(31, 16) ##
                (command.bits(15, 0).asUInt + 1.U(16)).asBits.bits(15, 0)
            )
          }
        }
        when(command.bits(31, 24) === AbstractCommandType.ACCESS_MEMORY.B(8)) {
          when(command.bit(19)) {
            when(abstractSizeReg === 0.B(3)) {
              data1 := (data1 + 1.U(p.dataBits)).asBits.bits(p.dataBits - 1, 0).asUInt
            }
            when(abstractSizeReg === 1.B(3)) {
              data1 := (data1 + 2.U(p.dataBits)).asBits.bits(p.dataBits - 1, 0).asUInt
            }
            when(abstractSizeReg === 2.B(3)) {
              data1 := (data1 + 4.U(p.dataBits)).asBits.bits(p.dataBits - 1, 0).asUInt
            }
          }
        }
      }
    }

    // What the debugger's access to the system bus registers asks the master to do, decided in the handler below and
    // carried out by the engine after it.
    val sbStartRead  = Wire(Bool())
    val sbStartWrite = Wire(Bool())
    val sbStartAddr  = Wire(Bits(32))
    sbStartRead  := false.B
    sbStartWrite := false.B
    sbStartAddr  := sbAddress.asBits

    val reqFire = req.field[Bool]("valid") & (!respValid)
    when(reqFire) {
      respValid := true.B
      respData  := dmiReadData
      respOp    := DmiOp.SUCCESS.B(2)

      val addr = reqBits.field[Bits]("addr")
      val data = reqBits.field[Bits]("data")

      when(reqBits.field[Bits]("op") === DmiOp.READ.B(2)) {
        when(
          abstractBusy &
            ((addr === DebugRegister.DATA0.B(p.abits)) |
              (addr === DebugRegister.DATA1.B(p.abits)))
        ) {
          when(commandError === AbstractCommandError.NONE.B(3)) {
            commandError := AbstractCommandError.BUSY.B(3)
          }
        }
        // Reading the data register is itself the request for the next word, which is how a debugger streams memory.
        when(dmactive & (addr === DebugRegister.SBDATA0.B(p.abits))) {
          when(sbBusy) {
            sbBusyError := true.B
          }.otherwise {
            when(sbReadOnData & !sbBlocked) { sbStartRead := true.B }
          }
        }
      }

      when(reqBits.field[Bits]("op") === DmiOp.WRITE.B(2)) {
        when(addr === DebugRegister.DMCONTROL.B(p.abits)) {
          when(!data.bit(0)) {
            dmactive     := false.B
            ndmreset     := false.B
            hartreset    := false.B
            data0        := 0.B(p.dataBits)
            data1        := 0.U(p.dataBits)
            command      := 0.B(32)
            abstractBusy := false.B
            commandError := AbstractCommandError.NONE.B(3)
            sbAccess     := 2.B(3)
            sbAutoInc    := false.B
            sbReadOnAddr := false.B
            sbReadOnData := false.B
            sbError      := 0.B(3)
            sbBusyError  := false.B
            sbAddress    := 0.U(32)
            sbData       := 0.B(32)
            for i <- 0 until n do
              haltRequest(i)      := false.B
              resetHaltRequest(i) := false.B
              resumeAck(i)        := false.B
          }.otherwise {
            dmactive  := true.B
            ndmreset  := data.bit(1)
            hartreset := data.bit(29)
            hartsel   := data.bits(16 + sel - 1, 16).asUInt
            when(!abstractBusy) {
              // The write acts on the hart it selects, which is the one this very write names — not the one selected
              // before it, which is why the halted bit consulted here is that hart's own.
              val target = Wire(Bits(sel))
              target := data.bits(16 + sel - 1, 16)
              for i <- 0 until n do
                when(target === i.B(sel)) {
                  haltRequest(i) := data.bit(31)
                  when(
                    data.bit(30) &
                      !data.bit(31) &
                      statusOf(i).field[Bool]("halted")
                  ) {
                    resumeReqReg := true.B
                    resumeAck(i) := false.B
                  }
                  when(data.bit(28)) {
                    haveReset(i) := false.B
                  }
                  when(data.bit(3)) {
                    resetHaltRequest(i) := true.B
                  }
                  when(data.bit(2)) {
                    resetHaltRequest(i) := false.B
                  }
                }
            }
          }
        }

        when(dmactive & (addr === DebugRegister.DATA0.B(p.abits))) {
          when(abstractBusy) {
            when(commandError === AbstractCommandError.NONE.B(3)) {
              commandError := AbstractCommandError.BUSY.B(3)
            }
          }.otherwise {
            data0 := data
          }
        }
        when(dmactive & (addr === DebugRegister.DATA1.B(p.abits))) {
          when(abstractBusy) {
            when(commandError === AbstractCommandError.NONE.B(3)) {
              commandError := AbstractCommandError.BUSY.B(3)
            }
          }.otherwise {
            data1 := data.asUInt
          }
        }
        when(dmactive & (addr === DebugRegister.ABSTRACTCS.B(p.abits))) {
          when(abstractBusy) {
            when(commandError === AbstractCommandError.NONE.B(3)) {
              commandError := AbstractCommandError.BUSY.B(3)
            }
          }.otherwise {
            commandError := (
              commandError &
                (data.bits(10, 8) ^ 7.B(3))
            )
          }
        }
        when(dmactive & (addr === DebugRegister.COMMAND.B(p.abits))) {
          when(abstractBusy) {
            when(commandError === AbstractCommandError.NONE.B(3)) {
              commandError := AbstractCommandError.BUSY.B(3)
            }
          }.otherwise {
            when(commandError === AbstractCommandError.NONE.B(3)) {
              command := data
              when(data.bits(31, 24) === AbstractCommandType.ACCESS_REGISTER.B(8)) {
                when(
                  data.bit(23) |
                    data.bit(18) |
                    (data.bits(22, 20) =/= 2.B(3))
                ) {
                  commandError := AbstractCommandError.NOT_SUPPORTED.B(3)
                }.otherwise {
                  when(data.bit(17)) {
                    when(!selHalted) {
                      commandError := AbstractCommandError.HALT_OR_RESUME.B(3)
                    }.otherwise {
                      abstractBusy       := true.B
                      abstractValidReg   := true.B
                      abstractHart       := hartsel.asBits
                      abstractCmdKindReg := AbstractCommandType.ACCESS_REGISTER.B(2)
                      abstractWriteReg   := data.bit(16)
                      abstractRegnoReg   := data.bits(15, 0)
                      abstractSizeReg    := 2.B(3)
                      abstractDataReg    := data0
                      abstractAddressReg := 0.B(p.xlen)
                    }
                  }
                }
              }.otherwise {
                when(data.bits(31, 24) === AbstractCommandType.ACCESS_MEMORY.B(8)) {
                  when(
                    (data.bits(22, 20).asUInt > 2.U(3)) |
                      (data.bits(18, 17) =/= 0.B(2)) |
                      (data.bits(15, 14) =/= 0.B(2)) |
                      (data.bits(13, 0) =/= 0.B(14))
                  ) {
                    commandError := AbstractCommandError.NOT_SUPPORTED.B(3)
                  }.otherwise {
                    when(!selHalted) {
                      commandError := AbstractCommandError.HALT_OR_RESUME.B(3)
                    }.otherwise {
                      abstractBusy       := true.B
                      abstractValidReg   := true.B
                      abstractHart       := hartsel.asBits
                      abstractCmdKindReg := AbstractCommandType.ACCESS_MEMORY.B(2)
                      abstractWriteReg   := data.bit(16)
                      abstractRegnoReg   := 0.B(16)
                      abstractSizeReg    := data.bits(22, 20)
                      abstractDataReg    := data0
                      abstractAddressReg := data1.asBits
                    }
                  }
                }.otherwise {
                  commandError := AbstractCommandError.NOT_SUPPORTED.B(3)
                }
              }
            }
          }
        }

        // The system bus registers: the control word, the address — writing it may itself be the read request — and
        // the data word, whose write is the write request.
        when(dmactive & (addr === DebugRegister.SBCS.B(p.abits))) {
          sbReadOnAddr := data.bit(20)
          sbAccess     := data.bits(19, 17)
          sbAutoInc    := data.bit(16)
          sbReadOnData := data.bit(15)
          when(data.bit(22)) { sbBusyError := false.B }
          sbError      := sbError & (data.bits(14, 12) ^ 7.B(3))
        }
        when(dmactive & (addr === DebugRegister.SBADDRESS0.B(p.abits))) {
          when(sbBusy) {
            sbBusyError := true.B
          }.otherwise {
            sbAddress := data.asUInt
            when(sbReadOnAddr & !sbBlocked) {
              sbStartRead := true.B
              sbStartAddr := data
            }
          }
        }
        when(dmactive & (addr === DebugRegister.SBDATA0.B(p.abits))) {
          when(sbBusy) {
            sbBusyError := true.B
          }.otherwise {
            sbData := data
            when(!sbBlocked) { sbStartWrite := true.B }
          }
        }
      }
    }

    // ---- the system bus master: one single-beat transaction at a time, in the word lane the address selects ----
    val sbStart      = Wire(Bool())
    val sbSizeBad    = Wire(Bool())
    val sbMisaligned = Wire(Bool())
    sbStart      := sbStartRead | sbStartWrite
    sbSizeBad    := sbAccess =/= 2.B(3)
    sbMisaligned := sbStartAddr.bits(1, 0) =/= 0.B(2)
    when(sbStart) {
      sbAddress := sbStartAddr.asUInt
      sbLane    := sbStartAddr.bits(3, 2)
      when(sbSizeBad) {
        sbError := 4.B(3) // an access of unsupported size
      }.otherwise {
        when(sbMisaligned) {
          sbError := 3.B(3) // an alignment error
        }.otherwise {
          sbState  := sbStartWrite.?(1.B(2), 2.B(2))
          sbAwDone := false.B
          sbWDone  := false.B
          sbArDone := false.B
        }
      }
    }

    val sbWriting = Wire(Bool())
    val sbReading = Wire(Bool())
    sbWriting := sbState === 1.B(2)
    sbReading := sbState === 2.B(2)

    val sbWData = Wire(Bits(p.sbDataBits))
    val sbWStrb = Wire(Bits(p.sbDataBits / 8))
    if p.sbDataBits == 32 then
      sbWData := sbData
      sbWStrb := 15.B(4)
    else
      sbWData := 0.B(96) ## sbData
      sbWStrb := 0.B(12) ## 15.B(4)
      for l <- 1 until 4 do
        when(sbLane === l.B(2)) {
          if l == 3 then
            sbWData := sbData ## 0.B(96)
            sbWStrb := 15.B(4) ## 0.B(12)
          else
            sbWData := 0.B((3 - l) * 32) ## sbData ## 0.B(l * 32)
            sbWStrb := 0.B((3 - l) * 4) ## 15.B(4) ## 0.B(l * 4)
        }

    val sbAddrOut = Wire(Bits(p.sbAddrBits))
    sbAddrOut := sbAddress.asBits.bits(p.sbAddrBits - 1, 0)

    // AW and W are presented together: a master must never hold WVALID for AWREADY.
    sbCh("aw").field[Bool]("valid")   := sbWriting & (!sbAwDone)
    sbBits("aw").field[Bits]("id")    := 0.B(p.sbIdBits)
    sbBits("aw").field[Bits]("addr")  := sbAddrOut
    sbBits("aw").field[Bits]("len")   := 0.B(8)
    sbBits("aw").field[Bits]("size")  := 2.B(3)
    sbBits("aw").field[Bits]("burst") := 1.B(2)

    sbCh("w").field[Bool]("valid")  := sbWriting & (!sbWDone)
    sbBits("w").field[Bits]("data") := sbWData
    sbBits("w").field[Bits]("strb") := sbWStrb
    sbBits("w").field[Bool]("last") := true.B

    val sbAwHs = sbWriting & (!sbAwDone) & sbCh("aw").field[Bool]("ready")
    val sbWHs  = sbWriting & (!sbWDone) & sbCh("w").field[Bool]("ready")
    when(sbAwHs) { sbAwDone := true.B }
    when(sbWHs) { sbWDone := true.B }

    val sbWriteSent = Wire(Bool())
    sbWriteSent                       := sbWriting & (sbAwDone | sbAwHs) & (sbWDone | sbWHs)
    sbCh("b").field[Bool]("ready")    := sbWriteSent
    when(sbWriteSent & sbCh("b").field[Bool]("valid")) {
      sbState  := 0.B(2)
      sbAwDone := false.B
      sbWDone  := false.B
      when(sbBits("b").field[Bits]("resp") =/= 0.B(2)) {
        sbError := 2.B(3) // a bad address was accessed
      }.otherwise {
        when(sbAutoInc) { sbAddress := (sbAddress + 4.U(32)).asBits.bits(31, 0).asUInt }
      }
    }

    sbCh("ar").field[Bool]("valid")   := sbReading & (!sbArDone)
    sbBits("ar").field[Bits]("id")    := 0.B(p.sbIdBits)
    sbBits("ar").field[Bits]("addr")  := sbAddrOut
    sbBits("ar").field[Bits]("len")   := 0.B(8)
    sbBits("ar").field[Bits]("size")  := 2.B(3)
    sbBits("ar").field[Bits]("burst") := 1.B(2)
    when(sbReading & (!sbArDone) & sbCh("ar").field[Bool]("ready")) { sbArDone := true.B }

    val sbRWord = Wire(Bits(32))
    sbRWord                        := sbBits("r").field[Bits]("data").bits(31, 0)
    if p.sbDataBits == 128 then
      for l <- 1 until 4 do
        when(sbLane === l.B(2)) {
          sbRWord := sbBits("r").field[Bits]("data").bits(l * 32 + 31, l * 32)
        }

    sbCh("r").field[Bool]("ready") := sbReading
    when(sbReading & sbCh("r").field[Bool]("valid")) {
      sbState  := 0.B(2)
      sbArDone := false.B
      when(sbBits("r").field[Bits]("resp") =/= 0.B(2)) {
        sbError := 2.B(3)
      }.otherwise {
        sbData := sbRWord
        when(sbAutoInc) { sbAddress := (sbAddress + 4.U(32)).asBits.bits(31, 0).asUInt }
      }
    }

    // ---- the hart ports: requests to the hart the debugger selected, the command to the one it was issued for ----
    for i <- 0 until n do
      hart(i).field[Bool]("halt")        := dmactive & haltRequest(i)
      hart(i).field[Bool]("resume")      := dmactive & resumeReqReg & (hartsel === i.U(sel))
      hart(i).field[Bool]("reset")       := dmactive & (ndmreset | hartreset)
      hart(i).field[Bool]("haltOnReset") := dmactive & resetHaltRequest(i)

      cmdOf(i).field[Bool]("valid")   := abstractValidReg & (abstractHart === i.B(sel))
      cmdOf(i).field[Bits]("kind")    := abstractCmdKindReg
      cmdOf(i).field[Bool]("write")   := abstractWriteReg
      cmdOf(i).field[Bits]("regno")   := abstractRegnoReg
      cmdOf(i).field[Bits]("size")    := abstractSizeReg
      cmdOf(i).field[Bits]("data")    := abstractDataReg
      cmdOf(i).field[Bits]("address") := abstractAddressReg
