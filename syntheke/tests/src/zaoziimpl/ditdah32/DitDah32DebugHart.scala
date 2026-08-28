// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
// SPDX-License-Identifier: MIT
package com.vowstar.ditdah32

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

trait DitDah32DebugHart:
  this: DitDah32Csr =>

  protected def connectDebugHart(
    parameter:           DitDah32Parameter,
    io:                  Interface[DitDah32IO],
    dm:                  me.jiuyang.zaozi.reftpe.Interface[DebugModuleIO],
    pc:                  Reg[UInt],
    instrReg:            Reg[Bits],
    fetched:             Reg[Bool],
    fetchOutstanding:    Reg[Bool],
    memOutstanding:      Reg[Bool],
    storeAwDone:         Reg[Bool],
    storeWDone:          Reg[Bool],
    state:               Reg[UInt],
    straddleLowHalfword: Reg[Bits],
    memPcReg:            Reg[UInt],
    memInstrReg:         Reg[Bits],
    memLenReg:           Reg[UInt],
    memNextPcReg:        Reg[UInt],
    memAddrReg:          Reg[UInt],
    memRdReg:            Reg[Bits],
    memFunct3Reg:        Reg[UInt],
    memStoreDataReg:     Reg[Bits],
    memStoreBeReg:       Reg[UInt],
    irqCauseReg:         Reg[Bits],
    csrMstatus:          Reg[Bits],
    csrMie:              Reg[Bits],
    csrMtvec:            Reg[UInt],
    csrMscratch:         Reg[Bits],
    csrMepc:             Reg[UInt],
    csrMcause:           Reg[Bits],
    csrMtval:            Reg[UInt],
    trapEventReg:        Reg[Bool],
    gprIo:               me.jiuyang.zaozi.reftpe.Interface[DitDah32GprIO],
    debugDcsr:           Reg[UInt],
    debugDpc:            Reg[UInt],
    debugStepActive:     Reg[Bool],
    debugResumeAck:      Reg[Bool],
    debugResetAck:       Reg[Bool],
    debugResetActive:    Reg[Bool],
    debugAbstractDone:   Reg[Bool],
    debugAbstractError:  Reg[UInt],
    debugAbstractRdata:  Reg[UInt],
    debugMemBusy:        Reg[Bool],
    debugMemWrite:       Reg[Bool],
    debugMemAddr:        Reg[UInt],
    debugMemSize:        Reg[UInt],
    debugMemData:        Reg[UInt],
    debugMemOutstanding: Reg[Bool],
    debugMemAwDone:      Reg[Bool],
    debugMemWDone:       Reg[Bool],
    stateRun:            Referable[Bool],
    stateStraddle:       Referable[Bool],
    stateLoad:           Referable[Bool],
    stateStore:          Referable[Bool],
    stateSleep:          Referable[Bool],
    stateIrq:            Referable[Bool],
    stateDebug:          Referable[Bool],
    fetchResponseFire:   Referable[Bool],
    loadResponseOk:      Referable[Bool],
    loadResponseError:   Referable[Bool],
    storeResponseOk:     Referable[Bool],
    storeResponseError:  Referable[Bool],
    commitNow:           Referable[Bool],
    commitNonMem:        Referable[Bool],
    debugEbreak:         Referable[Bool],
    execTrap:            Referable[Bool],
    execNextPc:          Referable[UInt],
    trapVector:          Referable[UInt],
    irqMip:              Referable[Bits]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit =
    val axiAw = io.axi.aw
    val axiW  = io.axi.w
    val axiB  = io.axi.b
    val axiAr = io.axi.ar
    val axiR  = io.axi.r

    val debugCsrAddr                                        = dm.abstractRegno.asBits.bits(11, 0)
    val (debugCsrReadData, debugCsrValid, debugCsrReadOnly) =
      csrReadSignals(
        debugCsrAddr,
        csrMstatus,
        csrMie,
        csrMtvec.asBits,
        csrMscratch,
        csrMepc.asBits,
        csrMcause,
        csrMtval.asBits,
        irqMip,
        parameter
      )
    gprIo.raddr3 := dm.abstractRegno.asBits.bits(4, 0).asUInt
    val debugGprData           = gprIo.rdata3
    val debugIsGpr             =
      (dm.abstractRegno.asBits.bits(15, 5) === 0x80.B(11)) &
        !dm.abstractRegno.asBits.bit(4)
    val debugIsDcsr            = dm.abstractRegno === DebugCsrAddr.DCSR.U(16)
    val debugIsDpc             = dm.abstractRegno === DebugCsrAddr.DPC.U(16)
    val debugIsCsr             = (dm.abstractRegno.asBits.bits(15, 12) === 0.B(4)) & debugCsrValid
    val debugRegisterSupported = debugIsGpr | debugIsDcsr | debugIsDpc | debugIsCsr

    val debugRegisterReadData = Wire(UInt(parameter.xlen))
    debugRegisterReadData := 0.U(parameter.xlen)
    when(debugIsGpr) {
      debugRegisterReadData := debugGprData
    }
    when(debugIsCsr) {
      debugRegisterReadData := debugCsrReadData.asUInt
    }
    when(debugIsDcsr) {
      debugRegisterReadData := debugDcsr
    }
    when(debugIsDpc) {
      debugRegisterReadData := debugDpc
    }

    val debugReadActive          = stateDebug & debugMemBusy & !debugMemWrite
    val debugWriteActive         = stateDebug & debugMemBusy & debugMemWrite
    val debugArValid             = debugReadActive & !debugMemOutstanding
    val debugArFire              = debugArValid & axiAr.ready
    val debugReadAcceptsResponse = debugReadActive & (debugMemOutstanding | debugArFire)
    val debugReadResponseFire    = debugReadAcceptsResponse & axiR.valid
    val debugReadResponseError   = debugReadResponseFire & (axiR.bits.resp =/= 0.U(2))
    val debugReadResponseOk      = debugReadResponseFire & !debugReadResponseError
    val debugAwValid             = debugWriteActive & !debugMemAwDone
    val debugWValid              = debugWriteActive & !debugMemWDone
    val debugAwFire              = debugAwValid & axiAw.ready
    val debugWFire               = debugWValid & axiW.ready
    val debugWriteChannelsDone   =
      (debugMemAwDone | debugAwFire) & (debugMemWDone | debugWFire)
    val debugWriteResponseFire   = debugWriteActive & debugWriteChannelsDone & axiB.valid
    val debugWriteResponseError  = debugWriteResponseFire & (axiB.bits.resp =/= 0.U(2))
    val debugWriteResponseOk     = debugWriteResponseFire & !debugWriteResponseError

    val debugStoreData = Wire(UInt(parameter.xlen))
    val debugStoreBe   = Wire(UInt(4))
    debugStoreData := debugMemData
    debugStoreBe   := 0.U(4)
    when(debugMemSize === 0.U(3)) {
      when(debugMemAddr.asBits.bits(1, 0) === 0.B(2)) {
        debugStoreData := (0.B(24) ## debugMemData.asBits.bits(7, 0)).asUInt
        debugStoreBe   := 1.U(4)
      }
      when(debugMemAddr.asBits.bits(1, 0) === 1.B(2)) {
        debugStoreData := (0.B(16) ## debugMemData.asBits.bits(7, 0) ## 0.B(8)).asUInt
        debugStoreBe   := 2.U(4)
      }
      when(debugMemAddr.asBits.bits(1, 0) === 2.B(2)) {
        debugStoreData := (0.B(8) ## debugMemData.asBits.bits(7, 0) ## 0.B(16)).asUInt
        debugStoreBe   := 4.U(4)
      }
      when(debugMemAddr.asBits.bits(1, 0) === 3.B(2)) {
        debugStoreData := (debugMemData.asBits.bits(7, 0) ## 0.B(24)).asUInt
        debugStoreBe   := 8.U(4)
      }
    }
    when(debugMemSize === 1.U(3)) {
      when(!debugMemAddr.asBits.bit(1)) {
        debugStoreData := (0.B(16) ## debugMemData.asBits.bits(15, 0)).asUInt
        debugStoreBe   := 3.U(4)
      }
      when(debugMemAddr.asBits.bit(1)) {
        debugStoreData := (debugMemData.asBits.bits(15, 0) ## 0.B(16)).asUInt
        debugStoreBe   := 0xc.U(4)
      }
    }
    when(debugMemSize === 2.U(3)) {
      debugStoreData := debugMemData
      debugStoreBe   := 0xf.U(4)
    }

    val debugLoadByte = Wire(Bits(8))
    debugLoadByte := axiR.bits.data.asBits.bits(7, 0)
    when(debugMemAddr.asBits.bits(1, 0) === 1.B(2)) {
      debugLoadByte := axiR.bits.data.asBits.bits(15, 8)
    }
    when(debugMemAddr.asBits.bits(1, 0) === 2.B(2)) {
      debugLoadByte := axiR.bits.data.asBits.bits(23, 16)
    }
    when(debugMemAddr.asBits.bits(1, 0) === 3.B(2)) {
      debugLoadByte := axiR.bits.data.asBits.bits(31, 24)
    }
    val debugLoadHalf =
      debugMemAddr.asBits
        .bit(1)
        .?(
          axiR.bits.data.asBits.bits(31, 16),
          axiR.bits.data.asBits.bits(15, 0)
        )
    val debugLoadData = Wire(UInt(parameter.xlen))
    debugLoadData := axiR.bits.data
    when(debugMemSize === 0.U(3)) {
      debugLoadData := (0.B(24) ## debugLoadByte).asUInt
    }
    when(debugMemSize === 1.U(3)) {
      debugLoadData := (0.B(16) ## debugLoadHalf).asUInt
    }

    when(stateDebug & debugMemBusy) {
      axiAw.valid     := debugAwValid
      axiAw.bits.addr := (debugMemAddr.asBits.bits(parameter.xlen - 1, 2) ## 0.B(2)).asUInt
      axiAw.bits.prot := 2.U(3)
      axiW.valid      := debugWValid
      axiW.bits.data  := debugStoreData
      axiW.bits.strb  := debugStoreBe
      axiB.ready      := debugWriteChannelsDone
      axiAr.valid     := debugArValid
      axiAr.bits.addr := (debugMemAddr.asBits.bits(parameter.xlen - 1, 2) ## 0.B(2)).asUInt
      axiAr.bits.prot := 2.U(3)
      axiR.ready      := debugReadAcceptsResponse
    }

    when(debugArFire) {
      debugMemOutstanding := true.B
    }
    when(debugReadResponseFire) {
      debugMemOutstanding := false.B
      debugMemBusy        := false.B
      debugAbstractDone   := true.B
      when(debugReadResponseError) {
        debugAbstractError := AbstractCommandError.BUS.U(3)
      }.otherwise {
        debugAbstractRdata := debugLoadData
      }
    }
    when(debugAwFire) {
      debugMemAwDone := true.B
    }
    when(debugWFire) {
      debugMemWDone := true.B
    }
    when(debugWriteResponseFire) {
      debugMemAwDone    := false.B
      debugMemWDone     := false.B
      debugMemBusy      := false.B
      debugAbstractDone := true.B
      when(debugWriteResponseError) {
        debugAbstractError := AbstractCommandError.BUS.U(3)
      }
    }

    when(dm.abstractValid) {
      when(dm.abstractCmdType === AbstractCommandType.ACCESS_REGISTER.U(2)) {
        debugAbstractDone := true.B
        when(!debugRegisterSupported) {
          debugAbstractError := AbstractCommandError.EXCEPTION.U(3)
        }.otherwise {
          when(dm.abstractWrite) {
            when(debugIsGpr) {
              gprIo.we    := true.B
              gprIo.waddr := dm.abstractRegno.asBits.bits(4, 0).asUInt
              gprIo.wdata := dm.abstractData
            }
            when(debugIsDcsr) {
              debugDcsr := (
                (dm.abstractData.asBits & 0x00008004.B(parameter.xlen)) |
                  (debugDcsr.asBits & 0x000001c0.B(parameter.xlen)) |
                  0x40000003.B(parameter.xlen)
              ).asUInt
            }
            when(debugIsDpc) {
              debugDpc := (dm.abstractData.asBits.bits(parameter.xlen - 1, 1) ## 0.B(1)).asUInt
            }
            when(debugIsCsr) {
              when(debugCsrReadOnly) {
                debugAbstractError := AbstractCommandError.EXCEPTION.U(3)
              }.otherwise {
                when(debugCsrAddr === CsrAddr.MSTATUS.B(12)) {
                  csrMstatus := writableMstatus(dm.abstractData.asBits)
                }
                when(debugCsrAddr === CsrAddr.MIE.B(12)) {
                  csrMie := (dm.abstractData.asBits & 0x888.B(parameter.xlen)).bits(parameter.xlen - 1, 0)
                }
                when(debugCsrAddr === CsrAddr.MTVEC.B(12)) {
                  csrMtvec := (dm.abstractData.asBits.bits(parameter.xlen - 1, 2) ## 0.B(2)).asUInt
                }
                when(debugCsrAddr === CsrAddr.MSCRATCH.B(12)) {
                  csrMscratch := dm.abstractData.asBits
                }
                when(debugCsrAddr === CsrAddr.MEPC.B(12)) {
                  csrMepc := (dm.abstractData.asBits.bits(parameter.xlen - 1, 1) ## 0.B(1)).asUInt
                }
                when(debugCsrAddr === CsrAddr.MCAUSE.B(12)) {
                  csrMcause := dm.abstractData.asBits
                }
                when(debugCsrAddr === CsrAddr.MTVAL.B(12)) {
                  csrMtval := dm.abstractData
                }
              }
            }
          }.otherwise {
            debugAbstractRdata := debugRegisterReadData
          }
        }
      }

      when(dm.abstractCmdType === AbstractCommandType.ACCESS_MEMORY.U(2)) {
        val debugMemoryMisaligned =
          ((dm.abstractSize === 1.U(3)) & dm.abstractAddress.asBits.bit(0)) |
            ((dm.abstractSize === 2.U(3)) & (dm.abstractAddress.asBits.bits(1, 0) =/= 0.B(2)))
        when(debugMemoryMisaligned) {
          debugAbstractDone  := true.B
          debugAbstractError := AbstractCommandError.BUS.U(3)
        }.otherwise {
          debugMemBusy        := true.B
          debugMemWrite       := dm.abstractWrite
          debugMemAddr        := dm.abstractAddress
          debugMemSize        := dm.abstractSize
          debugMemData        := dm.abstractData
          debugMemOutstanding := false.B
          debugMemAwDone      := false.B
          debugMemWDone       := false.B
        }
      }
    }

    when(debugStepActive & commitNonMem) {
      val stepNextPc = execTrap.?(trapVector, execNextPc)
      state           := CoreState.DEBUG.U(3)
      pc              := stepNextPc
      debugDpc        := stepNextPc
      debugDcsr       := (
        debugDcsr.asBits.bits(31, 9) ##
          DebugCause.STEP.B(3) ##
          debugDcsr.asBits.bits(5, 0)
      ).asUInt
      debugStepActive := false.B
    }
    when(debugStepActive & loadResponseOk) {
      state           := CoreState.DEBUG.U(3)
      pc              := memNextPcReg
      debugDpc        := memNextPcReg
      debugDcsr       := (
        debugDcsr.asBits.bits(31, 9) ##
          DebugCause.STEP.B(3) ##
          debugDcsr.asBits.bits(5, 0)
      ).asUInt
      debugStepActive := false.B
    }
    when(debugStepActive & storeResponseOk) {
      state           := CoreState.DEBUG.U(3)
      pc              := memNextPcReg
      debugDpc        := memNextPcReg
      debugDcsr       := (
        debugDcsr.asBits.bits(31, 9) ##
          DebugCause.STEP.B(3) ##
          debugDcsr.asBits.bits(5, 0)
      ).asUInt
      debugStepActive := false.B
    }
    when(debugStepActive & (loadResponseError | storeResponseError)) {
      state           := CoreState.DEBUG.U(3)
      pc              := trapVector
      debugDpc        := trapVector
      debugDcsr       := (
        debugDcsr.asBits.bits(31, 9) ##
          DebugCause.STEP.B(3) ##
          debugDcsr.asBits.bits(5, 0)
      ).asUInt
      debugStepActive := false.B
    }

    when(commitNow & debugEbreak) {
      state     := CoreState.DEBUG.U(3)
      debugDpc  := pc
      debugDcsr := (
        debugDcsr.asBits.bits(31, 9) ##
          DebugCause.EBREAK.B(3) ##
          debugDcsr.asBits.bits(5, 0)
      ).asUInt
    }

    val debugFetchDrained = !fetchOutstanding | fetchResponseFire
    when(dm.haltReq & (stateRun | stateStraddle) & debugFetchDrained) {
      state     := CoreState.DEBUG.U(3)
      debugDpc  := pc
      debugDcsr := (
        debugDcsr.asBits.bits(31, 9) ##
          DebugCause.HALT_REQUEST.B(3) ##
          debugDcsr.asBits.bits(5, 0)
      ).asUInt
    }
    when(dm.haltReq & stateSleep) {
      state     := CoreState.DEBUG.U(3)
      debugDpc  := pc
      debugDcsr := (
        debugDcsr.asBits.bits(31, 9) ##
          DebugCause.HALT_REQUEST.B(3) ##
          debugDcsr.asBits.bits(5, 0)
      ).asUInt
    }
    when(dm.haltReq & stateIrq) {
      state     := CoreState.DEBUG.U(3)
      pc        := trapVector
      debugDpc  := trapVector
      debugDcsr := (
        debugDcsr.asBits.bits(31, 9) ##
          DebugCause.HALT_REQUEST.B(3) ##
          debugDcsr.asBits.bits(5, 0)
      ).asUInt
    }
    when(dm.haltReq & stateLoad & (loadResponseOk | loadResponseError)) {
      val haltPc = loadResponseError.?(trapVector, memNextPcReg)
      state     := CoreState.DEBUG.U(3)
      pc        := haltPc
      debugDpc  := haltPc
      debugDcsr := (
        debugDcsr.asBits.bits(31, 9) ##
          DebugCause.HALT_REQUEST.B(3) ##
          debugDcsr.asBits.bits(5, 0)
      ).asUInt
    }
    when(dm.haltReq & stateStore & (storeResponseOk | storeResponseError)) {
      val haltPc = storeResponseError.?(trapVector, memNextPcReg)
      state     := CoreState.DEBUG.U(3)
      pc        := haltPc
      debugDpc  := haltPc
      debugDcsr := (
        debugDcsr.asBits.bits(31, 9) ##
          DebugCause.HALT_REQUEST.B(3) ##
          debugDcsr.asBits.bits(5, 0)
      ).asUInt
    }

    when(stateDebug & dm.resumeReq & !debugMemBusy) {
      state            := CoreState.RUN.U(3)
      pc               := debugDpc
      fetchOutstanding := false.B
      debugStepActive  := debugDcsr.asBits.bit(2)
      debugResumeAck   := true.B
    }

    when(dm.resetReq) {
      state               := CoreState.RESET.U(3)
      pc                  := parameter.resetVector.U(parameter.xlen)
      instrReg            := 0.B(parameter.xlen)
      fetched             := false.B
      fetchOutstanding    := false.B
      memOutstanding      := false.B
      storeAwDone         := false.B
      storeWDone          := false.B
      straddleLowHalfword := 0.B(16)
      memPcReg            := 0.U(parameter.xlen)
      memInstrReg         := 0.B(parameter.xlen)
      memLenReg           := 0.U(3)
      memNextPcReg        := 0.U(parameter.xlen)
      memAddrReg          := 0.U(parameter.xlen)
      memRdReg            := 0.B(5)
      memFunct3Reg        := 0.U(3)
      memStoreDataReg     := 0.B(parameter.xlen)
      memStoreBeReg       := 0.U(4)
      irqCauseReg         := 0.B(parameter.xlen)
      csrMstatus          := 0.B(parameter.xlen)
      csrMie              := 0.B(parameter.xlen)
      csrMtvec            := 0.U(parameter.xlen)
      csrMscratch         := 0.B(parameter.xlen)
      csrMepc             := 0.U(parameter.xlen)
      csrMcause           := 0.B(parameter.xlen)
      csrMtval            := 0.U(parameter.xlen)
      trapEventReg        := false.B
      gprIo.clearAll      := true.B
      debugDcsr           := 0x40000003.U(parameter.xlen)
      debugDpc            := parameter.resetVector.U(parameter.xlen)
      debugStepActive     := false.B
      debugMemBusy        := false.B
      debugMemOutstanding := false.B
      debugMemAwDone      := false.B
      debugMemWDone       := false.B
      debugResetActive    := true.B
    }

    when(debugResetActive & !dm.resetReq) {
      debugResetActive := false.B
      debugResetAck    := true.B
      when(dm.haltOnResetReq | dm.haltReq) {
        state     := CoreState.DEBUG.U(3)
        debugDpc  := parameter.resetVector.U(parameter.xlen)
        debugDcsr := (
          debugDcsr.asBits.bits(31, 9) ##
            dm.haltOnResetReq.?(DebugCause.RESET_HALT.B(3), DebugCause.HALT_REQUEST.B(3)) ##
            debugDcsr.asBits.bits(5, 0)
        ).asUInt
      }.otherwise {
        state := CoreState.RUN.U(3)
      }
    }
