// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
// SPDX-License-Identifier: MIT
package com.vowstar.ditdah32

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

@generator
object DitDah32DebugModule extends Generator[DitDah32Parameter, DitDah32DebugLayers, DebugModuleIO, DitDah32DebugProbe]:

  override def moduleName(parameter: DitDah32Parameter): String = "DitDah32DebugModule"

  def architecture(parameter: DitDah32Parameter) =
    val io = summon[Interface[DebugModuleIO]]

    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)

    val requestToggleMeta = RegInit(false.B)
    val requestToggleSync = RegInit(false.B)
    val requestToggleSeen = RegInit(false.B)
    val responseToggle    = RegInit(false.B)
    val responseAddr      = RegInit(0.U(7))
    val responseData      = RegInit(0.U(32))
    val responseOp        = RegInit(DmiOp.SUCCESS.U(2))

    val dmactive         = RegInit(false.B)
    val ndmreset         = RegInit(false.B)
    val hartreset        = RegInit(false.B)
    val haltRequest      = RegInit(false.B)
    val resetHaltRequest = RegInit(false.B)
    val resumeAck        = RegInit(false.B)
    val haveReset        = RegInit(false.B)

    val data0        = RegInit(0.U(32))
    val data1        = RegInit(0.U(32))
    val command      = RegInit(0.U(32))
    val abstractBusy = RegInit(false.B)
    val commandError = RegInit(AbstractCommandError.NONE.U(3))

    val resumeReqReg       = RegInit(false.B)
    val abstractValidReg   = RegInit(false.B)
    val abstractCmdTypeReg = RegInit(0.U(2))
    val abstractWriteReg   = RegInit(false.B)
    val abstractRegnoReg   = RegInit(0.U(16))
    val abstractSizeReg    = RegInit(0.U(3))
    val abstractDataReg    = RegInit(0.U(32))
    val abstractAddressReg = RegInit(0.U(32))

    val dmcontrolRead = Wire(UInt(32))
    dmcontrolRead := (
      haltRequest.asBits ##
        0.B(1) ##
        hartreset.asBits ##
        0.B(27) ##
        ndmreset.asBits ##
        dmactive.asBits
    ).asUInt

    val dmstatusRead = Wire(UInt(32))
    dmstatusRead := (
      0.B(7) ##
        ndmreset.asBits ##
        0.B(4) ##
        haveReset.asBits ##
        haveReset.asBits ##
        resumeAck.asBits ##
        resumeAck.asBits ##
        0.B(4) ##
        io.hartRunning.asBits ##
        io.hartRunning.asBits ##
        io.hartHalted.asBits ##
        io.hartHalted.asBits ##
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

    val dmiReadData = Wire(UInt(32))
    dmiReadData := 0.U(32)
    when(io.requestAddr === DebugRegister.DATA0.U(7)) {
      dmiReadData := data0
    }
    when(io.requestAddr === DebugRegister.DATA1.U(7)) {
      dmiReadData := data1
    }
    when(io.requestAddr === DebugRegister.DMCONTROL.U(7)) {
      dmiReadData := dmcontrolRead
    }
    when(io.requestAddr === DebugRegister.DMSTATUS.U(7)) {
      dmiReadData := dmstatusRead
    }
    when(io.requestAddr === DebugRegister.HARTINFO.U(7)) {
      dmiReadData := 0.U(32)
    }
    when(io.requestAddr === DebugRegister.ABSTRACTCS.U(7)) {
      dmiReadData := abstractcsRead
    }
    when(io.requestAddr === DebugRegister.COMMAND.U(7)) {
      dmiReadData := command
    }
    when(io.requestAddr === DebugRegister.ABSTRACTAUTO.U(7)) {
      dmiReadData := 0.U(32)
    }
    when(io.requestAddr === DebugRegister.HALTSUM0.U(7)) {
      dmiReadData := io.hartHalted.asBits.asUInt
    }

    requestToggleMeta := io.requestToggle
    requestToggleSync := requestToggleMeta
    resumeReqReg      := false.B
    abstractValidReg  := false.B

    when(io.hartResumeAck) {
      resumeAck := true.B
    }
    when(io.hartResetAck) {
      haveReset := true.B
      resumeAck := false.B
    }

    when(io.abstractDone & abstractBusy) {
      abstractBusy := false.B
      when(io.abstractError =/= AbstractCommandError.NONE.U(3)) {
        commandError := io.abstractError
      }.otherwise {
        when(!abstractWriteReg) {
          data0 := io.abstractRdata
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
              data1 := (data1 + 1.U(32)).asBits.bits(31, 0).asUInt
            }
            when(abstractSizeReg === 1.U(3)) {
              data1 := (data1 + 2.U(32)).asBits.bits(31, 0).asUInt
            }
            when(abstractSizeReg === 2.U(3)) {
              data1 := (data1 + 4.U(32)).asBits.bits(31, 0).asUInt
            }
          }
        }
      }
    }

    when(requestToggleSync =/= requestToggleSeen) {
      requestToggleSeen := requestToggleSync
      responseAddr      := io.requestAddr
      responseData      := dmiReadData
      responseOp        := DmiOp.SUCCESS.U(2)
      responseToggle    := !responseToggle

      when(io.requestOp === DmiOp.READ.U(2)) {
        when(
          abstractBusy &
            ((io.requestAddr === DebugRegister.DATA0.U(7)) |
              (io.requestAddr === DebugRegister.DATA1.U(7)))
        ) {
          when(commandError === AbstractCommandError.NONE.U(3)) {
            commandError := AbstractCommandError.BUSY.U(3)
          }
        }
      }

      when(io.requestOp === DmiOp.WRITE.U(2)) {
        when(io.requestAddr === DebugRegister.DMCONTROL.U(7)) {
          when(!io.requestData.asBits.bit(0)) {
            dmactive         := false.B
            ndmreset         := false.B
            hartreset        := false.B
            haltRequest      := false.B
            resetHaltRequest := false.B
            resumeAck        := false.B
            data0            := 0.U(32)
            data1            := 0.U(32)
            command          := 0.U(32)
            abstractBusy     := false.B
            commandError     := AbstractCommandError.NONE.U(3)
          }.otherwise {
            dmactive  := true.B
            ndmreset  := io.requestData.asBits.bit(1)
            hartreset := io.requestData.asBits.bit(29)
            when(!abstractBusy) {
              haltRequest := io.requestData.asBits.bit(31)
              when(
                io.requestData.asBits.bit(30) &
                  !io.requestData.asBits.bit(31) &
                  io.hartHalted
              ) {
                resumeReqReg := true.B
                resumeAck    := false.B
              }
              when(io.requestData.asBits.bit(28)) {
                haveReset := false.B
              }
              when(io.requestData.asBits.bit(3)) {
                resetHaltRequest := true.B
              }
              when(io.requestData.asBits.bit(2)) {
                resetHaltRequest := false.B
              }
            }
          }
        }

        when(dmactive & (io.requestAddr === DebugRegister.DATA0.U(7))) {
          when(abstractBusy) {
            when(commandError === AbstractCommandError.NONE.U(3)) {
              commandError := AbstractCommandError.BUSY.U(3)
            }
          }.otherwise {
            data0 := io.requestData
          }
        }
        when(dmactive & (io.requestAddr === DebugRegister.DATA1.U(7))) {
          when(abstractBusy) {
            when(commandError === AbstractCommandError.NONE.U(3)) {
              commandError := AbstractCommandError.BUSY.U(3)
            }
          }.otherwise {
            data1 := io.requestData
          }
        }
        when(dmactive & (io.requestAddr === DebugRegister.ABSTRACTCS.U(7))) {
          when(abstractBusy) {
            when(commandError === AbstractCommandError.NONE.U(3)) {
              commandError := AbstractCommandError.BUSY.U(3)
            }
          }.otherwise {
            commandError := (
              commandError.asBits &
                (io.requestData.asBits.bits(10, 8) ^ 7.B(3))
            ).asUInt
          }
        }
        when(dmactive & (io.requestAddr === DebugRegister.COMMAND.U(7))) {
          when(abstractBusy) {
            when(commandError === AbstractCommandError.NONE.U(3)) {
              commandError := AbstractCommandError.BUSY.U(3)
            }
          }.otherwise {
            when(commandError === AbstractCommandError.NONE.U(3)) {
              command := io.requestData
              when(io.requestData.asBits.bits(31, 24) === AbstractCommandType.ACCESS_REGISTER.B(8)) {
                when(
                  io.requestData.asBits.bit(23) |
                    io.requestData.asBits.bit(18) |
                    (io.requestData.asBits.bits(22, 20) =/= 2.B(3))
                ) {
                  commandError := AbstractCommandError.NOT_SUPPORTED.U(3)
                }.otherwise {
                  when(io.requestData.asBits.bit(17)) {
                    when(!io.hartHalted) {
                      commandError := AbstractCommandError.HALT_OR_RESUME.U(3)
                    }.otherwise {
                      abstractBusy       := true.B
                      abstractValidReg   := true.B
                      abstractCmdTypeReg := AbstractCommandType.ACCESS_REGISTER.U(2)
                      abstractWriteReg   := io.requestData.asBits.bit(16)
                      abstractRegnoReg   := io.requestData.asBits.bits(15, 0).asUInt
                      abstractSizeReg    := 2.U(3)
                      abstractDataReg    := data0
                      abstractAddressReg := 0.U(32)
                    }
                  }
                }
              }.otherwise {
                when(io.requestData.asBits.bits(31, 24) === AbstractCommandType.ACCESS_MEMORY.B(8)) {
                  when(
                    (io.requestData.asBits.bits(22, 20).asUInt > 2.U(3)) |
                      (io.requestData.asBits.bits(18, 17) =/= 0.B(2)) |
                      (io.requestData.asBits.bits(15, 14) =/= 0.B(2)) |
                      (io.requestData.asBits.bits(13, 0) =/= 0.B(14))
                  ) {
                    commandError := AbstractCommandError.NOT_SUPPORTED.U(3)
                  }.otherwise {
                    when(!io.hartHalted) {
                      commandError := AbstractCommandError.HALT_OR_RESUME.U(3)
                    }.otherwise {
                      abstractBusy       := true.B
                      abstractValidReg   := true.B
                      abstractCmdTypeReg := AbstractCommandType.ACCESS_MEMORY.U(2)
                      abstractWriteReg   := io.requestData.asBits.bit(16)
                      abstractRegnoReg   := 0.U(16)
                      abstractSizeReg    := io.requestData.asBits.bits(22, 20).asUInt
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

    io.responseToggle := responseToggle
    io.responseAddr   := responseAddr
    io.responseData   := responseData
    io.responseOp     := responseOp

    io.haltReq        := dmactive & haltRequest
    io.resumeReq      := dmactive & resumeReqReg
    io.resetReq       := dmactive & (ndmreset | hartreset)
    io.haltOnResetReq := dmactive & resetHaltRequest

    io.abstractValid   := abstractValidReg
    io.abstractCmdType := abstractCmdTypeReg
    io.abstractWrite   := abstractWriteReg
    io.abstractRegno   := abstractRegnoReg
    io.abstractSize    := abstractSizeReg
    io.abstractData    := abstractDataReg
    io.abstractAddress := abstractAddressReg
