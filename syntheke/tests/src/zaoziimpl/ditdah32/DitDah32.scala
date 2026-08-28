// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
// SPDX-License-Identifier: MIT
package com.vowstar.ditdah32

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

@generator
object DitDah32Module
    extends Generator[DitDah32Parameter, DitDah32Layers, DitDah32IO, DitDah32Probe]
    with DitDah32Csr
    with DitDah32DebugHart
    with DitDah32Rvc:

  override def moduleName(parameter: DitDah32Parameter): String = s"DitDah32_${parameter.hashCode.toHexString}"

  def architecture(parameter: DitDah32Parameter) =
    val io    = summon[Interface[DitDah32IO]]
    val probe = summon[ProbeInterface[DitDah32Probe]]

    // Channel locals keep the architecture method under the JVM 64 KB cap.
    val axiAw = io.axi.aw
    val axiW  = io.axi.w
    val axiB  = io.axi.b
    val axiAr = io.axi.ar
    val axiR  = io.axi.r

    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)

    val debugDtm    = Option.when(parameter.enableJtag)(DitDah32JtagDtm.instantiate(parameter))
    val debugModule = Option.when(parameter.enableJtag)(DitDah32DebugModule.instantiate(parameter))

    io.jtag.foreach { jtag =>
      val dtm = debugDtm.get
      val dm  = debugModule.get
      dtm.io.tck   := jtag.tck
      dtm.io.reset := io.reset
      dtm.io.tms   := jtag.tms
      dtm.io.tdi   := jtag.tdi
      dtm.io.trstN := jtag.trstN
      jtag.tdo     := dtm.io.tdo

      dm.io.clock           := io.clock
      dm.io.reset           := io.reset
      dm.io.requestToggle   := dtm.io.requestToggle
      dm.io.requestAddr     := dtm.io.requestAddr
      dm.io.requestData     := dtm.io.requestData
      dm.io.requestOp       := dtm.io.requestOp
      dtm.io.responseToggle := dm.io.responseToggle
      dtm.io.responseAddr   := dm.io.responseAddr
      dtm.io.responseData   := dm.io.responseData
      dtm.io.responseOp     := dm.io.responseOp
    }

    val pc                  = RegInit(parameter.resetVector.U(parameter.xlen))
    val instrReg            = RegInit(0.B(parameter.xlen))
    val fetched             = RegInit(false.B)
    val fetchOutstanding    = RegInit(false.B)
    val memOutstanding      = RegInit(false.B)
    val storeAwDone         = RegInit(false.B)
    val storeWDone          = RegInit(false.B)
    val state               = RegInit(CoreState.RESET.U(3))
    val straddleLowHalfword = RegInit(0.B(16))
    val memPcReg            = RegInit(0.U(parameter.xlen))
    val memInstrReg         = RegInit(0.B(parameter.xlen))
    val memLenReg           = RegInit(0.U(3))
    val memNextPcReg        = RegInit(0.U(parameter.xlen))
    val memAddrReg          = RegInit(0.U(parameter.xlen))
    val memRdReg            = RegInit(0.B(5))
    val memFunct3Reg        = RegInit(0.U(3))
    val memStoreDataReg     = RegInit(0.B(parameter.xlen))
    val memStoreBeReg       = RegInit(0.U(4))
    val irqCauseReg         = RegInit(0.B(parameter.xlen))
    val csrMstatus          = RegInit(0.B(parameter.xlen))
    val csrMie              = RegInit(0.B(parameter.xlen))
    val csrMtvec            = RegInit(0.U(parameter.xlen))
    val csrMscratch         = RegInit(0.B(parameter.xlen))
    val csrMepc             = RegInit(0.U(parameter.xlen))
    val csrMcause           = RegInit(0.B(parameter.xlen))
    val csrMtval            = RegInit(0.U(parameter.xlen))
    val trapEventReg        = RegInit(false.B)
    val gpr                 = DitDah32Gpr.instantiate(parameter)
    gpr.io.clock    := io.clock
    gpr.io.reset    := io.reset
    gpr.io.raddr1   := 0.U(5)
    gpr.io.raddr2   := 0.U(5)
    gpr.io.raddr3   := 0.U(5)
    gpr.io.we       := false.B
    gpr.io.waddr    := 0.U(5)
    gpr.io.wdata    := 0.U(parameter.xlen)
    gpr.io.clearAll := false.B

    val debugDcsr           = Option.when(parameter.enableJtag)(RegInit(0x40000003.U(parameter.xlen)))
    val debugDpc            = Option.when(parameter.enableJtag)(RegInit(parameter.resetVector.U(parameter.xlen)))
    val debugStepActive     = Option.when(parameter.enableJtag)(RegInit(false.B))
    val debugResumeAck      = Option.when(parameter.enableJtag)(RegInit(false.B))
    val debugResetAck       = Option.when(parameter.enableJtag)(RegInit(false.B))
    val debugResetActive    = Option.when(parameter.enableJtag)(RegInit(false.B))
    val debugAbstractDone   = Option.when(parameter.enableJtag)(RegInit(false.B))
    val debugAbstractError  = Option.when(parameter.enableJtag)(RegInit(AbstractCommandError.NONE.U(3)))
    val debugAbstractRdata  = Option.when(parameter.enableJtag)(RegInit(0.U(parameter.xlen)))
    val debugMemBusy        = Option.when(parameter.enableJtag)(RegInit(false.B))
    val debugMemWrite       = Option.when(parameter.enableJtag)(RegInit(false.B))
    val debugMemAddr        = Option.when(parameter.enableJtag)(RegInit(0.U(parameter.xlen)))
    val debugMemSize        = Option.when(parameter.enableJtag)(RegInit(0.U(3)))
    val debugMemData        = Option.when(parameter.enableJtag)(RegInit(0.U(parameter.xlen)))
    val debugMemOutstanding = Option.when(parameter.enableJtag)(RegInit(false.B))
    val debugMemAwDone      = Option.when(parameter.enableJtag)(RegInit(false.B))
    val debugMemWDone       = Option.when(parameter.enableJtag)(RegInit(false.B))

    val stateReset                   = Wire(Bool())
    val stateRun                     = Wire(Bool())
    val stateTrap                    = Wire(Bool())
    val stateDebug                   = Wire(Bool())
    val stateStraddle                = Wire(Bool())
    val stateLoad                    = Wire(Bool())
    val stateStore                   = Wire(Bool())
    val stateSleep                   = Wire(Bool())
    val stateIrq                     = Wire(Bool())
    val pcHalfwordHigh               = Wire(Bool())
    val pcFetchAddr                  = Wire(UInt(parameter.xlen))
    val instrAddr                    = Wire(UInt(parameter.xlen))
    val lowerHalfwordBits            = Wire(Bits(2))
    val upperHalfwordBits            = Wire(Bits(2))
    val instrLowBits                 = Wire(Bits(2))
    val instrCompressed              = Wire(Bool())
    val straddled32                  = Wire(Bool())
    val pcPlus2                      = Wire(UInt(parameter.xlen))
    val pcPlus4                      = Wire(UInt(parameter.xlen))
    val sequentialPc                 = Wire(UInt(parameter.xlen))
    val execNextPc                   = Wire(UInt(parameter.xlen))
    val selectedHalfword             = Wire(Bits(16))
    val selectedInstr                = Wire(Bits(parameter.xlen))
    val straddledInstr               = Wire(Bits(parameter.xlen))
    val decodedInstr                 = Wire(Bits(parameter.xlen))
    val cDecodedInstr                = Wire(Bits(parameter.xlen))
    val cNoWriteHint                 = Wire(Bool())
    val cInsn                        = Wire(Bits(16))
    val cQuadrant                    = Wire(Bits(2))
    val cFunct3                      = Wire(Bits(3))
    val cRdRs1                       = Wire(Bits(5))
    val cRs2                         = Wire(Bits(5))
    val cRdPrime                     = Wire(Bits(5))
    val cRs1Prime                    = Wire(Bits(5))
    val cRs2Prime                    = Wire(Bits(5))
    val cShamt                       = Wire(Bits(5))
    val cImm6                        = Wire(Bits(parameter.xlen))
    val cAddi4spnImm                 = Wire(Bits(parameter.xlen))
    val cLwImm                       = Wire(Bits(parameter.xlen))
    val cLwspImm                     = Wire(Bits(parameter.xlen))
    val cSwspImm                     = Wire(Bits(parameter.xlen))
    val cAddi16spImm                 = Wire(Bits(parameter.xlen))
    val cBranchImm                   = Wire(Bits(parameter.xlen))
    val cJumpImm                     = Wire(Bits(parameter.xlen))
    val commitNow                    = Wire(Bool())
    val commitInstr                  = Wire(Bits(parameter.xlen))
    val commitLen                    = Wire(UInt(3))
    val commitCompressed             = Wire(Bool())
    val fetchRequest                 = Wire(Bool())
    val fetchArValid                 = Wire(Bool())
    val fetchArFire                  = Wire(Bool())
    val fetchAcceptsResponse         = Wire(Bool())
    val fetchResponseFire            = Wire(Bool())
    val fetchResponseOk              = Wire(Bool())
    val fetchResponseError           = Wire(Bool())
    val instrReady                   = Wire(Bool())
    val instrRdata                   = Wire(Bits(parameter.xlen))
    val loadArValid                  = Wire(Bool())
    val loadArFire                   = Wire(Bool())
    val loadAcceptsResponse          = Wire(Bool())
    val loadResponseFire             = Wire(Bool())
    val loadResponseOk               = Wire(Bool())
    val loadResponseError            = Wire(Bool())
    val storeAwValid                 = Wire(Bool())
    val storeWValid                  = Wire(Bool())
    val storeAwFire                  = Wire(Bool())
    val storeWFire                   = Wire(Bool())
    val storeBothDone                = Wire(Bool())
    val storeResponseFire            = Wire(Bool())
    val storeResponseOk              = Wire(Bool())
    val storeResponseError           = Wire(Bool())
    val opcode                       = Wire(Bits(7))
    val rdIndex                      = Wire(Bits(5))
    val rs1Index                     = Wire(Bits(5))
    val rs2Index                     = Wire(Bits(5))
    val funct3                       = Wire(Bits(3))
    val funct7                       = Wire(Bits(7))
    val shamt                        = Wire(UInt(5))
    val rs1Data                      = Wire(UInt(parameter.xlen))
    val rs2Data                      = Wire(UInt(parameter.xlen))
    val rs2Shamt                     = Wire(UInt(5))
    val imm12                        = Wire(UInt(parameter.xlen))
    val immS                         = Wire(UInt(parameter.xlen))
    val immB                         = Wire(UInt(parameter.xlen))
    val immJ                         = Wire(UInt(parameter.xlen))
    val upperImm                     = Wire(UInt(parameter.xlen))
    val jalrTarget                   = Wire(UInt(parameter.xlen))
    val memAddr                      = Wire(UInt(parameter.xlen))
    val memAlignedAddr               = Wire(UInt(parameter.xlen))
    val storeData                    = Wire(Bits(parameter.xlen))
    val storeBe                      = Wire(UInt(4))
    val loadByte                     = Wire(Bits(8))
    val loadHalf                     = Wire(Bits(16))
    val loadWdata                    = Wire(Bits(parameter.xlen))
    val loadMemMask                  = Wire(UInt(4))
    val rs1SignedOrder               = Wire(UInt(parameter.xlen))
    val rs2SignedOrder               = Wire(UInt(parameter.xlen))
    val imm12SignedOrder             = Wire(UInt(parameter.xlen))
    val sraImmWdata                  = Wire(UInt(parameter.xlen))
    val sraRegWdata                  = Wire(UInt(parameter.xlen))
    val isLui                        = Wire(Bool())
    val isAuipc                      = Wire(Bool())
    val isJal                        = Wire(Bool())
    val isJalr                       = Wire(Bool())
    val isBranch                     = Wire(Bool())
    val isLoad                       = Wire(Bool())
    val isStore                      = Wire(Bool())
    val isOpImm                      = Wire(Bool())
    val isAluImm                     = Wire(Bool())
    val isOpReg                      = Wire(Bool())
    val isAluReg                     = Wire(Bool())
    val isFence                      = Wire(Bool())
    val isEcall                      = Wire(Bool())
    val isEbreak                     = Wire(Bool())
    val isWfi                        = Wire(Bool())
    val isMret                       = Wire(Bool())
    val isCsr                        = Wire(Bool())
    val isCsrImm                     = Wire(Bool())
    val csrAddr                      = Wire(Bits(12))
    val csrOperand                   = Wire(Bits(parameter.xlen))
    val csrWriteData                 = Wire(Bits(parameter.xlen))
    val csrTraceWriteData            = Wire(Bits(parameter.xlen))
    val csrWriteEnable               = Wire(Bool())
    val csrUsesRs1                   = Wire(Bool())
    val csrIllegal                   = Wire(Bool())
    val postCommitMstatus            = Wire(Bits(parameter.xlen))
    val postCommitMie                = Wire(Bits(parameter.xlen))
    val irqMip                       = Wire(Bits(parameter.xlen))
    val irqEnabledMask               = Wire(Bits(parameter.xlen))
    val irqSoftwareEnabled           = Wire(Bool())
    val irqTimerEnabled              = Wire(Bool())
    val irqExternalEnabled           = Wire(Bool())
    val irqIndividuallyPending       = Wire(Bool())
    val irqTrapPending               = Wire(Bool())
    val irqCause                     = Wire(Bits(parameter.xlen))
    val postCommitIrqEnabledMask     = Wire(Bits(parameter.xlen))
    val postCommitIrqSoftwareEnabled = Wire(Bool())
    val postCommitIrqTimerEnabled    = Wire(Bool())
    val postCommitIrqExternalEnabled = Wire(Bool())
    val postCommitIrqTrapPending     = Wire(Bool())
    val postCommitIrqCause           = Wire(Bits(parameter.xlen))
    val trapVector                   = Wire(UInt(parameter.xlen))
    val standardTrapCause            = Wire(Bits(parameter.xlen))
    val standardTrapValue            = Wire(UInt(parameter.xlen))
    val isCNop                       = Wire(Bool())
    val rdIllegal                    = Wire(Bool())
    val rs1Illegal                   = Wire(Bool())
    val rs2Illegal                   = Wire(Bool())
    val execUsesRd                   = Wire(Bool())
    val execUsesRs1                  = Wire(Bool())
    val execUsesRs2                  = Wire(Bool())
    val execKnown                    = Wire(Bool())
    val execTrap                     = Wire(Bool())
    val execTrapCause                = Wire(UInt(4))
    val execWdata                    = Wire(UInt(parameter.xlen))
    val execWriteRd                  = Wire(Bool())
    val branchTaken                  = Wire(Bool())
    val loadMisaligned               = Wire(Bool())
    val storeMisaligned              = Wire(Bool())
    val execWaitsForMem              = Wire(Bool())
    val debugHaltReq                 = Wire(Bool())
    val debugResumeReq               = Wire(Bool())
    val debugResetReq                = Wire(Bool())
    val debugHaltOnResetReq          = Wire(Bool())
    val debugEbreak                  = Wire(Bool())

    stateReset          := state === CoreState.RESET.U(3)
    stateRun            := state === CoreState.RUN.U(3)
    stateTrap           := state === CoreState.TRAP.U(3)
    stateDebug          := false.B
    stateStraddle       := state === CoreState.STRADDLE.U(3)
    stateLoad           := state === CoreState.LOAD.U(3)
    stateStore          := state === CoreState.STORE.U(3)
    stateSleep          := state === CoreState.SLEEP.U(3)
    stateIrq            := state === CoreState.IRQ.U(3)
    debugHaltReq        := false.B
    debugResumeReq      := false.B
    debugResetReq       := false.B
    debugHaltOnResetReq := false.B
    debugEbreak         := false.B
    if parameter.enableJtag then
      val dm = debugModule.get
      stateTrap              := false.B
      stateDebug             := state === CoreState.DEBUG.U(3)
      debugHaltReq           := dm.io.haltReq
      debugResumeReq         := dm.io.resumeReq
      debugResetReq          := dm.io.resetReq
      debugHaltOnResetReq    := dm.io.haltOnResetReq
      debugResumeAck.get     := false.B
      debugResetAck.get      := false.B
      debugAbstractDone.get  := false.B
      debugAbstractError.get := AbstractCommandError.NONE.U(3)
      dm.io.hartHalted       := stateDebug
      dm.io.hartRunning      := !stateDebug & !debugResetReq
      dm.io.hartResumeAck    := debugResumeAck.get
      dm.io.hartResetAck     := debugResetAck.get
      dm.io.abstractDone     := debugAbstractDone.get
      dm.io.abstractError    := debugAbstractError.get
      dm.io.abstractRdata    := debugAbstractRdata.get
    pcHalfwordHigh      := pc.asBits.bit(1)
    pcFetchAddr         := (pc.asBits.bits(parameter.xlen - 1, 2) ## 0.B(2)).asUInt

    fetchRequest                              := stateRun | stateStraddle
    if parameter.enableJtag then fetchRequest := (stateRun | stateStraddle) & !debugHaltReq
    fetchArValid                              := fetchRequest & !fetchOutstanding
    fetchArFire                               := fetchArValid & axiAr.ready
    fetchAcceptsResponse                      := fetchOutstanding | fetchArFire
    fetchResponseFire                         := fetchAcceptsResponse & axiR.valid
    fetchResponseError                        := fetchResponseFire & (axiR.bits.resp =/= 0.U(2))
    fetchResponseOk                           := fetchResponseFire & !fetchResponseError
    instrReady                                := fetchResponseOk
    instrRdata                                := axiR.bits.data.asBits

    loadArValid         := stateLoad & !memOutstanding
    loadArFire          := loadArValid & axiAr.ready
    loadAcceptsResponse := stateLoad & (memOutstanding | loadArFire)
    loadResponseFire    := loadAcceptsResponse & axiR.valid
    loadResponseError   := loadResponseFire & (axiR.bits.resp =/= 0.U(2))
    loadResponseOk      := loadResponseFire & !loadResponseError
    storeAwValid        := stateStore & !storeAwDone
    storeWValid         := stateStore & !storeWDone
    storeAwFire         := storeAwValid & axiAw.ready
    storeWFire          := storeWValid & axiW.ready
    storeBothDone       := (storeAwDone | storeAwFire) & (storeWDone | storeWFire)
    val storeComplete = stateStore & storeBothDone
    storeResponseFire  := storeComplete & axiB.valid
    storeResponseError := storeResponseFire & (axiB.bits.resp =/= 0.U(2))
    storeResponseOk    := storeResponseFire & !storeResponseError

    lowerHalfwordBits := instrRdata.bits(1, 0)
    upperHalfwordBits := instrRdata.bits(17, 16)
    instrLowBits      := pcHalfwordHigh.?(upperHalfwordBits, lowerHalfwordBits)
    instrCompressed   := instrLowBits =/= 3.B(2)
    straddled32       := pcHalfwordHigh & !instrCompressed
    selectedHalfword  := pcHalfwordHigh.?(instrRdata.bits(31, 16), instrRdata.bits(15, 0))

    pcPlus2        := (pc + 2.U(parameter.xlen)).asBits.bits(parameter.xlen - 1, 0).asUInt
    pcPlus4        := (pc + 4.U(parameter.xlen)).asBits.bits(parameter.xlen - 1, 0).asUInt
    sequentialPc   := commitCompressed.?(pcPlus2, pcPlus4)
    instrAddr      := stateStraddle.?(pcPlus2, pcFetchAddr)
    selectedInstr  := instrCompressed.?(0.B(16) ## selectedHalfword, instrRdata)
    straddledInstr := instrRdata.bits(15, 0) ## straddleLowHalfword

    commitNow        := (stateStraddle & instrReady) | (stateRun & instrReady & !straddled32)
    if parameter.enableJtag then
      commitNow      := ((stateStraddle & instrReady) | (stateRun & instrReady & !straddled32)) & !debugHaltReq
    commitInstr      := stateStraddle.?(straddledInstr, selectedInstr)
    commitLen        := stateStraddle.?(4.U(3), instrCompressed.?(2.U(3), 4.U(3)))
    commitCompressed := commitLen === 2.U(3)

    decodeRvc(
      parameter,
      commitInstr,
      commitCompressed,
      cInsn,
      cQuadrant,
      cFunct3,
      cRdRs1,
      cRs2,
      cRdPrime,
      cRs1Prime,
      cRs2Prime,
      cShamt,
      cImm6,
      cAddi4spnImm,
      cLwImm,
      cLwspImm,
      cSwspImm,
      cAddi16spImm,
      cBranchImm,
      cJumpImm,
      cDecodedInstr,
      cNoWriteHint,
      decodedInstr
    )

    opcode   := decodedInstr.bits(6, 0)
    rdIndex  := decodedInstr.bits(11, 7)
    funct3   := decodedInstr.bits(14, 12)
    rs1Index := decodedInstr.bits(19, 15)
    rs2Index := decodedInstr.bits(24, 20)
    shamt    := decodedInstr.bits(24, 20).asUInt
    funct7   := decodedInstr.bits(31, 25)

    gpr.io.raddr1 := rs1Index.asUInt
    gpr.io.raddr2 := rs2Index.asUInt
    rs1Data       := gpr.io.rdata1
    rs2Data       := gpr.io.rdata2

    csrAddr        := decodedInstr.bits(31, 20)
    isCsr          := (opcode === 0x73.B(7)) & (funct3 =/= 0.B(3))
    isCsrImm       := isCsr & funct3.bit(2)
    csrOperand     := isCsrImm.?(0.B(27) ## rs1Index, rs1Data.asBits)
    // CSR write attempt per Priv Spec v1.12 §9.4: CSRRW/CSRRWI always write;
    // CSRRS/CSRRC/CSRRSI/CSRRCI do NOT write iff rs1/uimm field (insn[19:15])
    // is 0. Using rs1Index (architectural), not runtime data, keeps the
    // illegal-instruction trap on read-only CSRs spec-conformant.
    csrWriteEnable := isCsr & (
      (funct3 === 1.B(3)) |
        (funct3 === 5.B(3)) |
        ((funct3 =/= 0.B(3)) & (funct3 =/= 4.B(3)) & (rs1Index =/= 0.B(5)))
    )
    csrUsesRs1     := isCsr & !isCsrImm & (
      (funct3 === 1.B(3)) |
        (((funct3 === 2.B(3)) | (funct3 === 3.B(3))) & (rs1Index =/= 0.B(5)))
    )

    irqMip                 := (
      io.irq.software.?(8.U(parameter.xlen), 0.U(parameter.xlen)) +
        io.irq.timer.?(0x80.U(parameter.xlen), 0.U(parameter.xlen)) +
        io.irq.external.?(0x800.U(parameter.xlen), 0.U(parameter.xlen))
    ).asBits.bits(parameter.xlen - 1, 0)
    irqEnabledMask         := (csrMie & irqMip).bits(parameter.xlen - 1, 0)
    irqSoftwareEnabled     := irqEnabledMask.bit(CsrBits.IRQ_SOFTWARE)
    irqTimerEnabled        := irqEnabledMask.bit(CsrBits.IRQ_TIMER)
    irqExternalEnabled     := irqEnabledMask.bit(CsrBits.IRQ_EXTERNAL)
    irqIndividuallyPending := irqEnabledMask =/= 0.B(parameter.xlen)
    irqTrapPending         := irqIndividuallyPending & csrMstatus.bit(CsrBits.MSTATUS_MIE)
    if parameter.enableJtag then
      irqTrapPending       := irqIndividuallyPending & csrMstatus.bit(CsrBits.MSTATUS_MIE) & !debugStepActive.get
    irqCause               := BigInt("80000007", 16).B(parameter.xlen)
    when(irqSoftwareEnabled) {
      irqCause := BigInt("80000003", 16).B(parameter.xlen)
    }
    when(irqExternalEnabled) {
      irqCause := BigInt("8000000b", 16).B(parameter.xlen)
    }
    trapVector             := (csrMtvec.asBits.bits(parameter.xlen - 1, 2) ## 0.B(2)).asUInt

    val (csrReadData, csrValid, csrReadOnly) =
      csrReadSignals(
        csrAddr,
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

    csrWriteData      := csrReadData
    when((funct3 === 1.B(3)) | (funct3 === 5.B(3))) {
      csrWriteData := csrOperand
    }
    when((funct3 === 2.B(3)) | (funct3 === 6.B(3))) {
      csrWriteData := (csrReadData | csrOperand).bits(parameter.xlen - 1, 0)
    }
    when((funct3 === 3.B(3)) | (funct3 === 7.B(3))) {
      csrWriteData := (csrReadData & (csrOperand ^ BigInt("ffffffff", 16).B(parameter.xlen)))
        .bits(parameter.xlen - 1, 0)
    }
    // RVFI csr_<name>_wdata reports the user-intended next value before
    // any WARL legalization, matching the RVFI convention
    // used by rvfi_csrw_check. Hardware-level WARL legalization is applied
    // separately on postCommit* and the CSR register input below.
    csrTraceWriteData := csrWriteData

    postCommitMstatus            := csrMstatus
    when(csrWriteEnable & (csrAddr === CsrAddr.MSTATUS.B(12)) & !execTrap) {
      postCommitMstatus := writableMstatus(csrWriteData)
    }
    when(isMret & !execTrap) {
      postCommitMstatus := mretMstatus(csrMstatus)
    }
    postCommitMie                := csrMie
    when(csrWriteEnable & (csrAddr === CsrAddr.MIE.B(12)) & !execTrap) {
      postCommitMie := (csrWriteData & 0x888.B(parameter.xlen)).bits(parameter.xlen - 1, 0)
    }
    postCommitIrqEnabledMask     := (postCommitMie & irqMip).bits(parameter.xlen - 1, 0)
    postCommitIrqSoftwareEnabled := postCommitIrqEnabledMask.bit(CsrBits.IRQ_SOFTWARE)
    postCommitIrqTimerEnabled    := postCommitIrqEnabledMask.bit(CsrBits.IRQ_TIMER)
    postCommitIrqExternalEnabled := postCommitIrqEnabledMask.bit(CsrBits.IRQ_EXTERNAL)
    postCommitIrqTrapPending     := (postCommitIrqEnabledMask =/= 0.B(parameter.xlen)) & postCommitMstatus.bit(
      CsrBits.MSTATUS_MIE
    )
    if parameter.enableJtag then
      postCommitIrqTrapPending   :=
        (postCommitIrqEnabledMask =/= 0.B(parameter.xlen)) &
          postCommitMstatus.bit(CsrBits.MSTATUS_MIE) &
          !debugStepActive.get
    postCommitIrqCause           := BigInt("80000007", 16).B(parameter.xlen)
    when(postCommitIrqSoftwareEnabled) {
      postCommitIrqCause := BigInt("80000003", 16).B(parameter.xlen)
    }
    when(postCommitIrqExternalEnabled) {
      postCommitIrqCause := BigInt("8000000b", 16).B(parameter.xlen)
    }
    csrIllegal                   := isCsr & (!csrValid | (funct3 === 4.B(3)) | (csrWriteEnable & csrReadOnly))

    imm12            := (decodedInstr.bit(31).?(0xfffff.B(20), 0.B(20)) ## decodedInstr.bits(31, 20)).asUInt
    immS             := (
      decodedInstr.bit(31).?(0xfffff.B(20), 0.B(20)) ##
        decodedInstr.bits(31, 25) ##
        decodedInstr.bits(11, 7)
    ).asUInt
    immB             := (
      decodedInstr.bit(31).?(0x7ffff.B(19), 0.B(19)) ##
        decodedInstr.bits(31, 31) ##
        decodedInstr.bits(7, 7) ##
        decodedInstr.bits(30, 25) ##
        decodedInstr.bits(11, 8) ##
        0.B(1)
    ).asUInt
    immJ             := (
      decodedInstr.bit(31).?(0x7ff.B(11), 0.B(11)) ##
        decodedInstr.bits(31, 31) ##
        decodedInstr.bits(19, 12) ##
        decodedInstr.bits(20, 20) ##
        decodedInstr.bits(30, 21) ##
        0.B(1)
    ).asUInt
    upperImm         := (decodedInstr.bits(31, 12) ## 0.B(12)).asUInt
    jalrTarget       := ((rs1Data + imm12).asBits.bits(parameter.xlen - 1, 1) ## 0.B(1)).asUInt
    memAddr          := (isStore).?(
      (rs1Data + immS).asBits.bits(parameter.xlen - 1, 0).asUInt,
      (rs1Data + imm12).asBits.bits(parameter.xlen - 1, 0).asUInt
    )
    memAlignedAddr   := (memAddr.asBits.bits(parameter.xlen - 1, 2) ## 0.B(2)).asUInt
    storeData        := rs2Data.asBits
    storeBe          := 0.U(4)
    when(isStore) {
      when(funct3 === 0.B(3)) {
        when(memAddr.asBits.bits(1, 0) === 0.B(2)) {
          storeData := 0.B(24) ## rs2Data.asBits.bits(7, 0)
          storeBe   := 1.U(4)
        }
        when(memAddr.asBits.bits(1, 0) === 1.B(2)) {
          storeData := 0.B(16) ## rs2Data.asBits.bits(7, 0) ## 0.B(8)
          storeBe   := 2.U(4)
        }
        when(memAddr.asBits.bits(1, 0) === 2.B(2)) {
          storeData := 0.B(8) ## rs2Data.asBits.bits(7, 0) ## 0.B(16)
          storeBe   := 4.U(4)
        }
        when(memAddr.asBits.bits(1, 0) === 3.B(2)) {
          storeData := rs2Data.asBits.bits(7, 0) ## 0.B(24)
          storeBe   := 8.U(4)
        }
      }
      when(funct3 === 1.B(3)) {
        when(!memAddr.asBits.bit(1)) {
          storeData := 0.B(16) ## rs2Data.asBits.bits(15, 0)
          storeBe   := 3.U(4)
        }
        when(memAddr.asBits.bit(1)) {
          storeData := rs2Data.asBits.bits(15, 0) ## 0.B(16)
          storeBe   := 0xc.U(4)
        }
      }
      when(funct3 === 2.B(3)) {
        storeData := rs2Data.asBits
        storeBe   := 0xf.U(4)
      }
    }
    loadByte         := axiR.bits.data.asBits.bits(7, 0)
    when(memAddrReg.asBits.bits(1, 0) === 1.B(2)) {
      loadByte := axiR.bits.data.asBits.bits(15, 8)
    }
    when(memAddrReg.asBits.bits(1, 0) === 2.B(2)) {
      loadByte := axiR.bits.data.asBits.bits(23, 16)
    }
    when(memAddrReg.asBits.bits(1, 0) === 3.B(2)) {
      loadByte := axiR.bits.data.asBits.bits(31, 24)
    }
    loadHalf         := memAddrReg.asBits.bit(1).?(axiR.bits.data.asBits.bits(31, 16), axiR.bits.data.asBits.bits(15, 0))
    loadWdata        := axiR.bits.data.asBits
    when(memFunct3Reg === 0.U(3)) {
      loadWdata := loadByte.bit(7).?(0xffffff.B(24), 0.B(24)) ## loadByte
    }
    when(memFunct3Reg === 1.U(3)) {
      loadWdata := loadHalf.bit(15).?(0xffff.B(16), 0.B(16)) ## loadHalf
    }
    when(memFunct3Reg === 4.U(3)) {
      loadWdata := 0.B(24) ## loadByte
    }
    when(memFunct3Reg === 5.U(3)) {
      loadWdata := 0.B(16) ## loadHalf
    }
    loadMemMask      := 0.U(4)
    when((memFunct3Reg === 0.U(3)) | (memFunct3Reg === 4.U(3))) {
      when(memAddrReg.asBits.bits(1, 0) === 0.B(2)) { loadMemMask := 1.U(4) }
      when(memAddrReg.asBits.bits(1, 0) === 1.B(2)) { loadMemMask := 2.U(4) }
      when(memAddrReg.asBits.bits(1, 0) === 2.B(2)) { loadMemMask := 4.U(4) }
      when(memAddrReg.asBits.bits(1, 0) === 3.B(2)) { loadMemMask := 8.U(4) }
    }
    when((memFunct3Reg === 1.U(3)) | (memFunct3Reg === 5.U(3))) {
      loadMemMask := memAddrReg.asBits.bit(1).?(0xc.U(4), 3.U(4))
    }
    when(memFunct3Reg === 2.U(3)) {
      loadMemMask := 0xf.U(4)
    }
    rs2Shamt         := rs2Data.asBits.bits(4, 0).asUInt
    rs1SignedOrder   := ((~(rs1Data.asBits.bits(parameter.xlen - 1, parameter.xlen - 1))) ## rs1Data.asBits.bits(
      parameter.xlen - 2,
      0
    )).asUInt
    rs2SignedOrder   := ((~(rs2Data.asBits.bits(parameter.xlen - 1, parameter.xlen - 1))) ## rs2Data.asBits.bits(
      parameter.xlen - 2,
      0
    )).asUInt
    imm12SignedOrder := ((~(imm12.asBits.bits(parameter.xlen - 1, parameter.xlen - 1))) ## imm12.asBits.bits(
      parameter.xlen - 2,
      0
    )).asUInt
    sraImmWdata      := ((rs1Data.asBits.bits(
      parameter.xlen - 1,
      parameter.xlen - 1
    ) ## rs1Data.asBits).asSInt >> shamt).asBits.bits(parameter.xlen - 1, 0).asUInt
    sraRegWdata      := ((rs1Data.asBits.bits(
      parameter.xlen - 1,
      parameter.xlen - 1
    ) ## rs1Data.asBits).asSInt >> rs2Shamt).asBits.bits(parameter.xlen - 1, 0).asUInt

    isLui                                    := opcode === 0x37.B(7)
    isAuipc                                  := opcode === 0x17.B(7)
    isJal                                    := opcode === 0x6f.B(7)
    isJalr                                   := (opcode === 0x67.B(7)) & (funct3 === 0.B(3))
    isBranch                                 := (opcode === 0x63.B(7)) & (
      (funct3 === 0.B(3)) |
        (funct3 === 1.B(3)) |
        (funct3 === 4.B(3)) |
        (funct3 === 5.B(3)) |
        (funct3 === 6.B(3)) |
        (funct3 === 7.B(3))
    )
    isLoad                                   := (opcode === 0x03.B(7)) & (
      (funct3 === 0.B(3)) |
        (funct3 === 1.B(3)) |
        (funct3 === 2.B(3)) |
        (funct3 === 4.B(3)) |
        (funct3 === 5.B(3))
    )
    isStore                                  := (opcode === 0x23.B(7)) & (
      (funct3 === 0.B(3)) |
        (funct3 === 1.B(3)) |
        (funct3 === 2.B(3))
    )
    isOpImm                                  := opcode === 0x13.B(7)
    isAluImm                                 := isOpImm & (
      (funct3 === 0.B(3)) |
        (funct3 === 2.B(3)) |
        (funct3 === 3.B(3)) |
        (funct3 === 4.B(3)) |
        (funct3 === 6.B(3)) |
        (funct3 === 7.B(3)) |
        ((funct3 === 1.B(3)) & (funct7 === 0.B(7))) |
        ((funct3 === 5.B(3)) & ((funct7 === 0.B(7)) | (funct7 === 0x20.B(7))))
    )
    isOpReg                                  := opcode === 0x33.B(7)
    isAluReg                                 := isOpReg & (
      ((funct7 === 0.B(7)) & (
        (funct3 === 0.B(3)) |
          (funct3 === 1.B(3)) |
          (funct3 === 2.B(3)) |
          (funct3 === 3.B(3)) |
          (funct3 === 4.B(3)) |
          (funct3 === 5.B(3)) |
          (funct3 === 6.B(3)) |
          (funct3 === 7.B(3))
      )) |
        ((funct7 === 0x20.B(7)) & ((funct3 === 0.B(3)) | (funct3 === 5.B(3))))
    )
    isFence                                  := (opcode === 0x0f.B(7)) & (funct3 === 0.B(3))
    isEcall                                  := decodedInstr === 0x00000073.B(parameter.xlen)
    isEbreak                                 := decodedInstr === 0x00100073.B(parameter.xlen)
    isWfi                                    := decodedInstr === 0x10500073.B(parameter.xlen)
    isMret                                   := decodedInstr === 0x30200073.B(parameter.xlen)
    isCNop                                   := commitCompressed & (commitInstr === 1.B(parameter.xlen))
    if parameter.enableJtag then debugEbreak := isEbreak & debugDcsr.get.asBits.bit(15)

    rdIllegal       := rdIndex.bit(4)
    rs1Illegal      := rs1Index.bit(4)
    rs2Illegal      := rs2Index.bit(4)
    loadMisaligned  := isLoad & (
      (((funct3 === 1.B(3)) | (funct3 === 5.B(3))) & memAddr.asBits.bit(0)) |
        ((funct3 === 2.B(3)) & (memAddr.asBits.bits(1, 0) =/= 0.B(2)))
    )
    storeMisaligned := isStore & (
      ((funct3 === 1.B(3)) & memAddr.asBits.bit(0)) |
        ((funct3 === 2.B(3)) & (memAddr.asBits.bits(1, 0) =/= 0.B(2)))
    )
    execUsesRd      := isLui | isAuipc | isJal | isJalr | isLoad | isAluImm | isAluReg | isCsr
    execUsesRs1     := isJalr | isBranch | isLoad | isStore | isAluImm | isAluReg | csrUsesRs1
    execUsesRs2     := isBranch | isStore | isAluReg
    execKnown       := isLui | isAuipc | isJal | isJalr | isBranch | isLoad | isStore | isAluImm | isAluReg | isFence | isEcall | isEbreak | isWfi | isMret | isCsr | isCNop

    val regAccessIllegal = (execUsesRd & rdIllegal) | (execUsesRs1 & rs1Illegal) | (execUsesRs2 & rs2Illegal)
    execTrap      := (!execKnown) | csrIllegal | regAccessIllegal | loadMisaligned | storeMisaligned | isEcall | isEbreak
    if parameter.enableJtag then
      execTrap    :=
        (!execKnown) |
          csrIllegal |
          regAccessIllegal |
          loadMisaligned |
          storeMisaligned |
          isEcall |
          (isEbreak & !debugDcsr.get.asBits.bit(15))
    execTrapCause := TrapCause.NONE.U(4)
    when(!execKnown | csrIllegal) {
      execTrapCause := TrapCause.ILLEGAL.U(4)
    }
    when(regAccessIllegal) {
      execTrapCause := TrapCause.RV32E_REGISTER.U(4)
    }
    when(loadMisaligned) {
      execTrapCause := TrapCause.LOAD_MISALIGN.U(4)
    }
    when(storeMisaligned) {
      execTrapCause := TrapCause.STORE_MISALIGN.U(4)
    }
    when(isEcall) {
      execTrapCause := TrapCause.ECALL.U(4)
    }
    when(isEbreak) {
      execTrapCause := TrapCause.EBREAK.U(4)
    }

    standardTrapCause := StandardCause.ILLEGAL_INSTRUCTION.B(parameter.xlen)
    when(loadMisaligned) {
      standardTrapCause := StandardCause.LOAD_MISALIGNED.B(parameter.xlen)
    }
    when(storeMisaligned) {
      standardTrapCause := StandardCause.STORE_MISALIGNED.B(parameter.xlen)
    }
    when(isEcall) {
      standardTrapCause := StandardCause.ECALL_M.B(parameter.xlen)
    }
    when(isEbreak) {
      standardTrapCause := StandardCause.BREAKPOINT.B(parameter.xlen)
    }
    standardTrapValue := 0.U(parameter.xlen)
    when(!execKnown | csrIllegal) {
      standardTrapValue := commitInstr.asUInt
    }
    when(loadMisaligned | storeMisaligned) {
      standardTrapValue := memAddr
    }

    execWdata       := 0.U(parameter.xlen)
    when(isLui) {
      execWdata := upperImm
    }
    when(isAuipc) {
      execWdata := (pc + upperImm).asBits.bits(parameter.xlen - 1, 0).asUInt
    }
    when(isJal | isJalr) {
      execWdata := sequentialPc
    }
    when(isAluImm) {
      when(funct3 === 0.B(3)) {
        execWdata := (rs1Data + imm12).asBits.bits(parameter.xlen - 1, 0).asUInt
      }
      when(funct3 === 2.B(3)) {
        execWdata := (rs1SignedOrder < imm12SignedOrder).?(1.U(parameter.xlen), 0.U(parameter.xlen))
      }
      when(funct3 === 3.B(3)) {
        execWdata := (rs1Data < imm12).?(1.U(parameter.xlen), 0.U(parameter.xlen))
      }
      when(funct3 === 4.B(3)) {
        execWdata := (rs1Data.asBits ^ imm12.asBits).bits(parameter.xlen - 1, 0).asUInt
      }
      when(funct3 === 6.B(3)) {
        execWdata := (rs1Data.asBits | imm12.asBits).bits(parameter.xlen - 1, 0).asUInt
      }
      when(funct3 === 7.B(3)) {
        execWdata := (rs1Data.asBits & imm12.asBits).bits(parameter.xlen - 1, 0).asUInt
      }
      when(funct3 === 1.B(3)) {
        execWdata := (rs1Data << shamt).asBits.bits(parameter.xlen - 1, 0).asUInt
      }
      when((funct3 === 5.B(3)) & (funct7 === 0.B(7))) {
        execWdata := (rs1Data >> shamt).asBits.bits(parameter.xlen - 1, 0).asUInt
      }
      when((funct3 === 5.B(3)) & (funct7 === 0x20.B(7))) {
        execWdata := sraImmWdata
      }
    }
    when(isAluReg) {
      when((funct3 === 0.B(3)) & (funct7 === 0.B(7))) {
        execWdata := (rs1Data + rs2Data).asBits.bits(parameter.xlen - 1, 0).asUInt
      }
      when((funct3 === 0.B(3)) & (funct7 === 0x20.B(7))) {
        execWdata := (rs1Data - rs2Data).asBits.bits(parameter.xlen - 1, 0).asUInt
      }
      when(funct3 === 1.B(3)) {
        execWdata := (rs1Data << rs2Shamt).asBits.bits(parameter.xlen - 1, 0).asUInt
      }
      when(funct3 === 2.B(3)) {
        execWdata := (rs1SignedOrder < rs2SignedOrder).?(1.U(parameter.xlen), 0.U(parameter.xlen))
      }
      when(funct3 === 3.B(3)) {
        execWdata := (rs1Data < rs2Data).?(1.U(parameter.xlen), 0.U(parameter.xlen))
      }
      when(funct3 === 4.B(3)) {
        execWdata := (rs1Data.asBits ^ rs2Data.asBits).bits(parameter.xlen - 1, 0).asUInt
      }
      when((funct3 === 5.B(3)) & (funct7 === 0.B(7))) {
        execWdata := (rs1Data >> rs2Shamt).asBits.bits(parameter.xlen - 1, 0).asUInt
      }
      when((funct3 === 5.B(3)) & (funct7 === 0x20.B(7))) {
        execWdata := sraRegWdata
      }
      when(funct3 === 6.B(3)) {
        execWdata := (rs1Data.asBits | rs2Data.asBits).bits(parameter.xlen - 1, 0).asUInt
      }
      when(funct3 === 7.B(3)) {
        execWdata := (rs1Data.asBits & rs2Data.asBits).bits(parameter.xlen - 1, 0).asUInt
      }
    }
    when(isCsr) {
      execWdata := csrReadData.asUInt
    }
    execWriteRd     := execUsesRd & !execTrap & (rdIndex =/= 0.B(5)) & !cNoWriteHint
    execWaitsForMem := (isLoad | isStore) & !execTrap

    // Commit-cycle outcomes: a committing instruction either enters the
    // memory phase (load/store) or retires immediately (everything else).
    val commitEntersMem = commitNow & execWaitsForMem
    val commitNonMem    =
      if parameter.enableJtag then commitNow & !execWaitsForMem & !debugEbreak
      else commitNow & !execWaitsForMem

    branchTaken := false.B
    when(isBranch) {
      when(funct3 === 0.B(3)) {
        branchTaken := rs1Data === rs2Data
      }
      when(funct3 === 1.B(3)) {
        branchTaken := rs1Data =/= rs2Data
      }
      when(funct3 === 4.B(3)) {
        branchTaken := rs1SignedOrder < rs2SignedOrder
      }
      when(funct3 === 5.B(3)) {
        branchTaken := !(rs1SignedOrder < rs2SignedOrder)
      }
      when(funct3 === 6.B(3)) {
        branchTaken := rs1Data < rs2Data
      }
      when(funct3 === 7.B(3)) {
        branchTaken := !(rs1Data < rs2Data)
      }
    }

    execNextPc := sequentialPc
    when(isBranch & branchTaken) {
      execNextPc := (pc + immB).asBits.bits(parameter.xlen - 1, 0).asUInt
    }
    when(isJal) {
      execNextPc := (pc + immJ).asBits.bits(parameter.xlen - 1, 0).asUInt
    }
    when(isJalr) {
      execNextPc := jalrTarget
    }
    when(isMret) {
      execNextPc := csrMepc
    }

    axiAw.valid     := storeAwValid
    axiAw.bits.addr := (memAddrReg.asBits.bits(parameter.xlen - 1, 2) ## 0.B(2)).asUInt
    axiAw.bits.prot := 2.U(3)
    axiW.valid      := storeWValid
    axiW.bits.data  := memStoreDataReg.asUInt
    axiW.bits.strb  := memStoreBeReg
    axiB.ready      := storeComplete
    axiAr.valid     := loadArValid | fetchArValid
    axiAr.bits.addr := stateLoad.?((memAddrReg.asBits.bits(parameter.xlen - 1, 2) ## 0.B(2)).asUInt, instrAddr)
    axiAr.bits.prot := stateLoad.?(2.U(3), 6.U(3))
    axiR.ready      := loadAcceptsResponse | fetchAcceptsResponse

    io.irq.pending  := irqIndividuallyPending
    io.status.trap  := stateTrap | trapEventReg
    io.status.busy  := stateRun | stateStraddle | stateLoad | stateStore | stateIrq
    io.status.sleep := stateSleep

    when(stateReset) {
      state            := CoreState.RUN.U(3)
      fetchOutstanding := false.B
      memOutstanding   := false.B
      storeAwDone      := false.B
      storeWDone       := false.B
    }.otherwise {
      trapEventReg := false.B

      when(fetchArFire) {
        fetchOutstanding := true.B
      }
      when(fetchResponseFire) {
        fetchOutstanding := false.B
      }
      when(loadArFire) {
        memOutstanding := true.B
      }
      when(loadResponseFire) {
        memOutstanding := false.B
      }
      when(storeAwFire) {
        storeAwDone := true.B
      }
      when(storeWFire) {
        storeWDone := true.B
      }

      when(stateIrq) {
        state        := CoreState.RUN.U(3)
        pc           := trapVector
        trapEventReg := true.B
      }

      when(stateSleep) {
        when(irqIndividuallyPending) {
          state := CoreState.RUN.U(3)
          when(irqTrapPending) {
            state       := CoreState.IRQ.U(3)
            irqCauseReg := irqCause
            csrMepc     := pc
            csrMcause   := irqCause
            csrMtval    := 0.U(parameter.xlen)
            csrMstatus  := trapMstatus(csrMstatus)
          }
        }
      }

      when(fetchResponseError) {
        // Recoverable instruction access fault per doc/memory_fault_contract.md.
        state        := CoreState.IRQ.U(3)
        csrMcause    := 1.B(parameter.xlen)
        csrMtval     := pc
        csrMepc      := pc
        csrMstatus   := trapMstatus(csrMstatus)
        trapEventReg := true.B
      }

      when(loadResponseError) {
        // Recoverable load access fault per doc/memory_fault_contract.md.
        state        := CoreState.IRQ.U(3)
        csrMcause    := 5.B(parameter.xlen)
        csrMtval     := memAddrReg
        csrMepc      := memPcReg
        csrMstatus   := trapMstatus(csrMstatus)
        trapEventReg := true.B
      }

      when(storeResponseError) {
        // Recoverable store access fault per doc/memory_fault_contract.md.
        storeAwDone  := false.B
        storeWDone   := false.B
        state        := CoreState.IRQ.U(3)
        csrMcause    := 7.B(parameter.xlen)
        csrMtval     := memAddrReg
        csrMepc      := memPcReg
        csrMstatus   := trapMstatus(csrMstatus)
        trapEventReg := true.B
      }

      when(loadResponseOk) {
        instrReg := memInstrReg
        fetched  := true.B
        pc       := memNextPcReg
        state    := CoreState.RUN.U(3)

        gpr.io.we    := memRdReg =/= 0.B(5)
        gpr.io.waddr := memRdReg.asUInt
        gpr.io.wdata := loadWdata.asUInt

        when(irqTrapPending) {
          state       := CoreState.IRQ.U(3)
          irqCauseReg := irqCause
          csrMepc     := memNextPcReg
          csrMcause   := irqCause
          csrMtval    := 0.U(parameter.xlen)
          csrMstatus  := trapMstatus(csrMstatus)
        }
      }

      when(storeResponseOk) {
        storeAwDone := false.B
        storeWDone  := false.B
        instrReg    := memInstrReg
        fetched     := true.B
        pc          := memNextPcReg
        state       := CoreState.RUN.U(3)

        when(irqTrapPending) {
          state       := CoreState.IRQ.U(3)
          irqCauseReg := irqCause
          csrMepc     := memNextPcReg
          csrMcause   := irqCause
          csrMtval    := 0.U(parameter.xlen)
          csrMstatus  := trapMstatus(csrMstatus)
        }
      }

      when(stateStraddle) {
        when(instrReady) {
          instrReg := straddledInstr
          fetched  := true.B
          when(!execWaitsForMem) {
            pc    := execNextPc
            state := CoreState.RUN.U(3)
          }
        }
      }.otherwise {
        when(stateRun & instrReady) {
          when(straddled32) {
            straddleLowHalfword := instrRdata.bits(31, 16)
            state               := CoreState.STRADDLE.U(3)
          }.otherwise {
            instrReg := instrRdata
            fetched  := true.B
            when(!execWaitsForMem) {
              pc := execNextPc
            }
          }
        }
      }

      when(commitEntersMem) {
        memPcReg        := pc
        memInstrReg     := commitInstr
        memLenReg       := commitLen
        memNextPcReg    := sequentialPc
        memAddrReg      := memAddr
        memRdReg        := rdIndex
        memFunct3Reg    := funct3.asUInt
        memStoreDataReg := storeData
        memStoreBeReg   := storeBe
        memOutstanding  := false.B
        storeAwDone     := false.B
        storeWDone      := false.B
        when(isLoad) {
          state := CoreState.LOAD.U(3)
        }
        when(isStore) {
          state := CoreState.STORE.U(3)
        }
      }

      when(commitNonMem) {
        trapEventReg := execTrap

        gpr.io.we    := execWriteRd
        gpr.io.waddr := rdIndex.asUInt
        gpr.io.wdata := execWdata

        when(csrWriteEnable & !execTrap) {
          when(csrAddr === CsrAddr.MSTATUS.B(12)) {
            csrMstatus := writableMstatus(csrWriteData)
          }
          when(csrAddr === CsrAddr.MIE.B(12)) {
            csrMie := (csrWriteData & 0x888.B(parameter.xlen)).bits(parameter.xlen - 1, 0)
          }
          when(csrAddr === CsrAddr.MTVEC.B(12)) {
            csrMtvec := (csrWriteData.bits(parameter.xlen - 1, 2) ## 0.B(2)).asUInt
          }
          when(csrAddr === CsrAddr.MSCRATCH.B(12)) {
            csrMscratch := csrWriteData
          }
          when(csrAddr === CsrAddr.MEPC.B(12)) {
            csrMepc := (csrWriteData.bits(parameter.xlen - 1, 1) ## 0.B(1)).asUInt
          }
          when(csrAddr === CsrAddr.MCAUSE.B(12)) {
            csrMcause := csrWriteData
          }
          when(csrAddr === CsrAddr.MTVAL.B(12)) {
            csrMtval := csrWriteData.asUInt
          }
        }

        when(isMret & !execTrap) {
          pc         := csrMepc
          csrMstatus := mretMstatus(csrMstatus)
        }

        when(execTrap) {
          pc         := trapVector
          state      := CoreState.RUN.U(3)
          csrMepc    := pc
          csrMcause  := standardTrapCause
          csrMtval   := standardTrapValue
          csrMstatus := trapMstatus(csrMstatus)
        }.otherwise {
          when(isWfi) {
            state := CoreState.SLEEP.U(3)
          }
          when(postCommitIrqTrapPending) {
            state       := CoreState.IRQ.U(3)
            irqCauseReg := postCommitIrqCause
            csrMepc     := isMret.?(csrMepc, execNextPc)
            csrMcause   := postCommitIrqCause
            csrMtval    := 0.U(parameter.xlen)
            csrMstatus  := trapMstatus(postCommitMstatus)
          }
        }
      }
    }

    if parameter.enableJtag then
      connectDebugHart(
        parameter,
        io,
        debugModule.get.io,
        pc,
        instrReg,
        fetched,
        fetchOutstanding,
        memOutstanding,
        storeAwDone,
        storeWDone,
        state,
        straddleLowHalfword,
        memPcReg,
        memInstrReg,
        memLenReg,
        memNextPcReg,
        memAddrReg,
        memRdReg,
        memFunct3Reg,
        memStoreDataReg,
        memStoreBeReg,
        irqCauseReg,
        csrMstatus,
        csrMie,
        csrMtvec,
        csrMscratch,
        csrMepc,
        csrMcause,
        csrMtval,
        trapEventReg,
        gpr.io,
        debugDcsr.get,
        debugDpc.get,
        debugStepActive.get,
        debugResumeAck.get,
        debugResetAck.get,
        debugResetActive.get,
        debugAbstractDone.get,
        debugAbstractError.get,
        debugAbstractRdata.get,
        debugMemBusy.get,
        debugMemWrite.get,
        debugMemAddr.get,
        debugMemSize.get,
        debugMemData.get,
        debugMemOutstanding.get,
        debugMemAwDone.get,
        debugMemWDone.get,
        stateRun,
        stateStraddle,
        stateLoad,
        stateStore,
        stateSleep,
        stateIrq,
        stateDebug,
        fetchResponseFire,
        loadResponseOk,
        loadResponseError,
        storeResponseOk,
        storeResponseError,
        commitNow,
        commitNonMem,
        debugEbreak,
        execTrap,
        execNextPc,
        trapVector,
        irqMip
      )

    // Architectural trace shadow. Mirrors the core retire/trap timing as a
    // pure observer; lowered into the layer("DV") bind collateral so the
    // production main module is trace-free. Probes are read by the formal
    // wrapper and cocotb harness via the generated XMR macros.
    if parameter.enableTrace then
      layer("DV"):
        val traceValidReg             = RegInit(false.B)
        val tracePcReg                = RegInit(0.U(parameter.xlen))
        val traceNextPcReg            = RegInit(0.U(parameter.xlen))
        val traceInstrReg             = RegInit(0.U(parameter.xlen))
        val traceLenReg               = RegInit(0.U(3))
        val traceRdWeReg              = RegInit(false.B)
        val traceRdReg                = RegInit(0.U(parameter.registerIndexBits))
        val traceRdWdataReg           = RegInit(0.U(parameter.xlen))
        val traceRs1AddrReg           = RegInit(0.U(5))
        val traceRs1RdataReg          = RegInit(0.U(parameter.xlen))
        val traceRs2AddrReg           = RegInit(0.U(5))
        val traceRs2RdataReg          = RegInit(0.U(parameter.xlen))
        val traceMemAddrReg           = RegInit(0.U(parameter.xlen))
        val traceMemRmaskReg          = RegInit(0.U(4))
        val traceMemWmaskReg          = RegInit(0.U(4))
        val traceMemRdataReg          = RegInit(0.U(parameter.xlen))
        val traceMemWdataReg          = RegInit(0.U(parameter.xlen))
        val traceMemFaultReg          = RegInit(false.B)
        val traceMemFaultRmaskReg     = RegInit(0.U(4))
        val traceMemFaultWmaskReg     = RegInit(0.U(4))
        val traceCsrAddrReg           = RegInit(0.U(12))
        val traceCsrRmaskReg          = RegInit(0.U(parameter.xlen))
        val traceCsrWmaskReg          = RegInit(0.U(parameter.xlen))
        val traceCsrRdataReg          = RegInit(0.U(parameter.xlen))
        val traceCsrWdataReg          = RegInit(0.U(parameter.xlen))
        val traceTrapReg              = RegInit(false.B)
        val traceTrapCauseReg         = RegInit(0.U(4))
        val tracePreTrapMstatusReg    = RegInit(0.U(parameter.xlen))
        val tracePostCommitMstatusReg = RegInit(0.U(parameter.xlen))
        val traceIrqPreTrapMstatusReg = RegInit(0.U(parameter.xlen))
        val traceIrqPendingMaskReg    = RegInit(0.U(parameter.xlen))
        val memTraceRs1AddrReg        = RegInit(0.U(5))
        val memTraceRs1RdataReg       = RegInit(0.U(parameter.xlen))
        val memTraceRs2AddrReg        = RegInit(0.U(5))
        val memTraceRs2RdataReg       = RegInit(0.U(parameter.xlen))

        tracePreTrapMstatusReg    := csrMstatus.asUInt
        tracePostCommitMstatusReg := csrMstatus.asUInt

        // Preserve the exact trapMstatus input across the IRQ redirect cycle.
        when(
          (stateSleep & irqTrapPending) |
            (loadResponseOk & irqTrapPending) |
            (storeResponseOk & irqTrapPending)
        ) {
          traceIrqPreTrapMstatusReg := csrMstatus.asUInt
          traceIrqPendingMaskReg    := irqEnabledMask.asUInt
        }
        when(commitNonMem & !execTrap & postCommitIrqTrapPending) {
          traceIrqPreTrapMstatusReg := postCommitMstatus.asUInt
          traceIrqPendingMaskReg    := postCommitIrqEnabledMask.asUInt
        }

        when(!stateReset) {
          traceValidReg         := false.B
          traceRdWeReg          := false.B
          traceRdReg            := 0.U(parameter.registerIndexBits)
          traceRdWdataReg       := 0.U(parameter.xlen)
          traceRs1AddrReg       := 0.U(5)
          traceRs1RdataReg      := 0.U(parameter.xlen)
          traceRs2AddrReg       := 0.U(5)
          traceRs2RdataReg      := 0.U(parameter.xlen)
          traceMemAddrReg       := 0.U(parameter.xlen)
          traceMemRmaskReg      := 0.U(4)
          traceMemWmaskReg      := 0.U(4)
          traceMemRdataReg      := 0.U(parameter.xlen)
          traceMemWdataReg      := 0.U(parameter.xlen)
          traceMemFaultReg      := false.B
          traceMemFaultRmaskReg := 0.U(4)
          traceMemFaultWmaskReg := 0.U(4)
          traceCsrAddrReg       := 0.U(12)
          traceCsrRmaskReg      := 0.U(parameter.xlen)
          traceCsrWmaskReg      := 0.U(parameter.xlen)
          traceCsrRdataReg      := 0.U(parameter.xlen)
          traceCsrWdataReg      := 0.U(parameter.xlen)
          traceTrapReg          := false.B
          traceTrapCauseReg     := 0.U(4)

          when(stateIrq & csrMcause.bit(parameter.xlen - 1)) {
            traceValidReg          := true.B
            tracePcReg             := csrMepc
            traceNextPcReg         := trapVector
            traceInstrReg          := 0.U(parameter.xlen)
            traceLenReg            := 0.U(3)
            traceRdWeReg           := false.B
            traceRdReg             := 0.U(parameter.registerIndexBits)
            traceRdWdataReg        := 0.U(parameter.xlen)
            traceRs1AddrReg        := 0.U(5)
            traceRs1RdataReg       := 0.U(parameter.xlen)
            traceRs2AddrReg        := 0.U(5)
            traceRs2RdataReg       := 0.U(parameter.xlen)
            traceTrapReg           := true.B
            traceTrapCauseReg      := TrapCause.INTERRUPT.U(4)
            tracePreTrapMstatusReg := traceIrqPreTrapMstatusReg
          }

          when(fetchResponseError) {
            traceValidReg         := true.B
            tracePcReg            := pc
            traceNextPcReg        := trapVector
            traceInstrReg         := 0.U(parameter.xlen)
            traceLenReg           := 4.U(3)
            traceRdWeReg          := false.B
            traceRdReg            := 0.U(parameter.registerIndexBits)
            traceRdWdataReg       := 0.U(parameter.xlen)
            traceRs1AddrReg       := 0.U(5)
            traceRs1RdataReg      := 0.U(parameter.xlen)
            traceRs2AddrReg       := 0.U(5)
            traceRs2RdataReg      := 0.U(parameter.xlen)
            traceMemAddrReg       := (pc.asBits.bits(parameter.xlen - 1, 2) ## 0.B(2)).asUInt
            traceMemFaultReg      := true.B
            traceMemFaultRmaskReg := 0.U(4)
            traceMemFaultWmaskReg := 0.U(4)
            traceCsrAddrReg       := CsrAddr.MCAUSE.U(12)
            traceCsrRmaskReg      := BigInt("ffffffff", 16).U(parameter.xlen)
            traceCsrWmaskReg      := BigInt("ffffffff", 16).U(parameter.xlen)
            traceCsrRdataReg      := csrMcause.asUInt
            traceCsrWdataReg      := 1.U(parameter.xlen)
            traceTrapReg          := true.B
            traceTrapCauseReg     := TrapCause.AXI_ERROR.U(4)
          }

          when(loadResponseError) {
            traceValidReg         := true.B
            tracePcReg            := memPcReg
            traceNextPcReg        := trapVector
            traceInstrReg         := memInstrReg.asUInt
            traceLenReg           := memLenReg
            traceRdWeReg          := false.B
            traceRdReg            := 0.U(parameter.registerIndexBits)
            traceRdWdataReg       := 0.U(parameter.xlen)
            traceRs1AddrReg       := 0.U(5)
            traceRs1RdataReg      := 0.U(parameter.xlen)
            traceRs2AddrReg       := 0.U(5)
            traceRs2RdataReg      := 0.U(parameter.xlen)
            traceMemAddrReg       := (memAddrReg.asBits.bits(parameter.xlen - 1, 2) ## 0.B(2)).asUInt
            traceMemFaultReg      := true.B
            traceMemFaultRmaskReg := loadMemMask
            traceMemFaultWmaskReg := 0.U(4)
            traceCsrAddrReg       := CsrAddr.MCAUSE.U(12)
            traceCsrRmaskReg      := BigInt("ffffffff", 16).U(parameter.xlen)
            traceCsrWmaskReg      := BigInt("ffffffff", 16).U(parameter.xlen)
            traceCsrRdataReg      := csrMcause.asUInt
            traceCsrWdataReg      := 5.U(parameter.xlen)
            traceTrapReg          := true.B
            traceTrapCauseReg     := TrapCause.AXI_ERROR.U(4)
          }

          when(storeResponseError) {
            traceValidReg         := true.B
            tracePcReg            := memPcReg
            traceNextPcReg        := trapVector
            traceInstrReg         := memInstrReg.asUInt
            traceLenReg           := memLenReg
            traceRdWeReg          := false.B
            traceRdReg            := 0.U(parameter.registerIndexBits)
            traceRdWdataReg       := 0.U(parameter.xlen)
            traceRs1AddrReg       := 0.U(5)
            traceRs1RdataReg      := 0.U(parameter.xlen)
            traceRs2AddrReg       := 0.U(5)
            traceRs2RdataReg      := 0.U(parameter.xlen)
            traceMemAddrReg       := (memAddrReg.asBits.bits(parameter.xlen - 1, 2) ## 0.B(2)).asUInt
            traceMemFaultReg      := true.B
            traceMemFaultRmaskReg := 0.U(4)
            traceMemFaultWmaskReg := memStoreBeReg
            traceCsrAddrReg       := CsrAddr.MCAUSE.U(12)
            traceCsrRmaskReg      := BigInt("ffffffff", 16).U(parameter.xlen)
            traceCsrWmaskReg      := BigInt("ffffffff", 16).U(parameter.xlen)
            traceCsrRdataReg      := csrMcause.asUInt
            traceCsrWdataReg      := 7.U(parameter.xlen)
            traceTrapReg          := true.B
            traceTrapCauseReg     := TrapCause.AXI_ERROR.U(4)
          }

          when(loadResponseOk) {
            traceValidReg     := true.B
            tracePcReg        := memPcReg
            traceNextPcReg    := memNextPcReg
            traceInstrReg     := memInstrReg.asUInt
            traceLenReg       := memLenReg
            traceRdWeReg      := memRdReg =/= 0.B(5)
            traceRdReg        := memRdReg.bits(3, 0).asUInt
            traceRdWdataReg   := (memRdReg =/= 0.B(5)).?(loadWdata.asUInt, 0.U(parameter.xlen))
            traceRs1AddrReg   := memTraceRs1AddrReg
            traceRs1RdataReg  := memTraceRs1RdataReg
            traceRs2AddrReg   := memTraceRs2AddrReg
            traceRs2RdataReg  := memTraceRs2RdataReg
            traceMemAddrReg   := (memAddrReg.asBits.bits(parameter.xlen - 1, 2) ## 0.B(2)).asUInt
            traceMemRmaskReg  := loadMemMask
            traceMemWmaskReg  := 0.U(4)
            traceMemRdataReg  := axiR.bits.data
            traceMemWdataReg  := 0.U(parameter.xlen)
            traceTrapReg      := false.B
            traceTrapCauseReg := 0.U(4)
          }

          when(storeResponseOk) {
            traceValidReg     := true.B
            tracePcReg        := memPcReg
            traceNextPcReg    := memNextPcReg
            traceInstrReg     := memInstrReg.asUInt
            traceLenReg       := memLenReg
            traceRdWeReg      := false.B
            traceRdReg        := 0.U(parameter.registerIndexBits)
            traceRdWdataReg   := 0.U(parameter.xlen)
            traceRs1AddrReg   := memTraceRs1AddrReg
            traceRs1RdataReg  := memTraceRs1RdataReg
            traceRs2AddrReg   := memTraceRs2AddrReg
            traceRs2RdataReg  := memTraceRs2RdataReg
            traceMemAddrReg   := (memAddrReg.asBits.bits(parameter.xlen - 1, 2) ## 0.B(2)).asUInt
            traceMemRmaskReg  := 0.U(4)
            traceMemWmaskReg  := memStoreBeReg
            traceMemRdataReg  := 0.U(parameter.xlen)
            traceMemWdataReg  := memStoreDataReg.asUInt
            traceTrapReg      := false.B
            traceTrapCauseReg := 0.U(4)
          }

          when(stateStraddle) {
            when(instrReady) {
              when(!execWaitsForMem) {
                traceValidReg  := true.B
                tracePcReg     := pc
                traceNextPcReg := execNextPc
                traceInstrReg  := straddledInstr.asUInt
                traceLenReg    := 4.U(3)
              }
            }
          }.otherwise {
            when(stateRun & instrReady) {
              when(!straddled32) {
                when(!execWaitsForMem) {
                  traceValidReg  := true.B
                  tracePcReg     := pc
                  traceNextPcReg := execNextPc
                  traceInstrReg  := selectedInstr.asUInt
                  traceLenReg    := instrCompressed.?(2.U(3), 4.U(3))
                }
              }
            }
          }

          when(commitEntersMem) {
            memTraceRs1AddrReg  := execUsesRs1.?(rs1Index.asUInt, 0.U(5))
            memTraceRs1RdataReg := execUsesRs1.?(rs1Data, 0.U(parameter.xlen))
            memTraceRs2AddrReg  := execUsesRs2.?(rs2Index.asUInt, 0.U(5))
            memTraceRs2RdataReg := execUsesRs2.?(rs2Data, 0.U(parameter.xlen))
          }

          when(commitNonMem) {
            tracePostCommitMstatusReg := postCommitMstatus.asUInt
            traceTrapReg              := execTrap
            traceTrapCauseReg         := execTrapCause
            traceRdWeReg              := execWriteRd
            traceRdReg                := rdIndex.bits(3, 0).asUInt
            traceRdWdataReg           := execWriteRd.?(execWdata, 0.U(parameter.xlen))
            traceRs1AddrReg           := (execUsesRs1 & !execTrap).?(rs1Index.asUInt, 0.U(5))
            traceRs1RdataReg          := (execUsesRs1 & !execTrap).?(rs1Data, 0.U(parameter.xlen))
            traceRs2AddrReg           := (execUsesRs2 & !execTrap).?(rs2Index.asUInt, 0.U(5))
            traceRs2RdataReg          := (execUsesRs2 & !execTrap).?(rs2Data, 0.U(parameter.xlen))
            traceNextPcReg            := execTrap.?(trapVector, execNextPc)
            when(isCsr & !execTrap) {
              traceCsrAddrReg  := csrAddr.asUInt
              traceCsrRmaskReg := BigInt("ffffffff", 16).U(parameter.xlen)
              traceCsrRdataReg := csrReadData.asUInt
              when(csrWriteEnable) {
                traceCsrWmaskReg := BigInt("ffffffff", 16).U(parameter.xlen)
                traceCsrWdataReg := csrTraceWriteData.asUInt
              }
            }
          }
        }

        probe.trace_valid.foreach(_ <== traceValidReg)
        probe.trace_pc.foreach(_ <== tracePcReg)
        probe.trace_next_pc.foreach(_ <== traceNextPcReg)
        probe.trace_instr.foreach(_ <== traceInstrReg)
        probe.trace_len.foreach(_ <== traceLenReg)
        probe.trace_rd_we.foreach(_ <== traceRdWeReg)
        probe.trace_rd.foreach(_ <== traceRdReg)
        probe.trace_rd_wdata.foreach(_ <== traceRdWdataReg)
        probe.trace_rs1_addr.foreach(_ <== traceRs1AddrReg)
        probe.trace_rs1_rdata.foreach(_ <== traceRs1RdataReg)
        probe.trace_rs2_addr.foreach(_ <== traceRs2AddrReg)
        probe.trace_rs2_rdata.foreach(_ <== traceRs2RdataReg)
        probe.trace_mem_addr.foreach(_ <== traceMemAddrReg)
        probe.trace_mem_rmask.foreach(_ <== traceMemRmaskReg)
        probe.trace_mem_wmask.foreach(_ <== traceMemWmaskReg)
        probe.trace_mem_rdata.foreach(_ <== traceMemRdataReg)
        probe.trace_mem_wdata.foreach(_ <== traceMemWdataReg)
        probe.trace_mem_fault.foreach(_ <== traceMemFaultReg)
        probe.trace_mem_fault_rmask.foreach(_ <== traceMemFaultRmaskReg)
        probe.trace_mem_fault_wmask.foreach(_ <== traceMemFaultWmaskReg)
        probe.trace_csr_addr.foreach(_ <== traceCsrAddrReg)
        probe.trace_csr_rmask.foreach(_ <== traceCsrRmaskReg)
        probe.trace_csr_wmask.foreach(_ <== traceCsrWmaskReg)
        probe.trace_csr_rdata.foreach(_ <== traceCsrRdataReg)
        probe.trace_csr_wdata.foreach(_ <== traceCsrWdataReg)
        probe.trace_trap.foreach(_ <== traceTrapReg)
        probe.trace_trap_cause.foreach(_ <== traceTrapCauseReg)
        probe.trace_mstatus_post_commit.foreach(_ <== tracePostCommitMstatusReg)
        probe.trace_mstatus_pre_trap.foreach(_ <== tracePreTrapMstatusReg)
        probe.trace_irq_pending_mask.foreach(_ <== traceIrqPendingMaskReg)

        val traceMstatusWire = Wire(UInt(parameter.xlen))
        val traceMieWire     = Wire(UInt(parameter.xlen))
        val traceMtvecWire   = Wire(UInt(parameter.xlen))
        val traceMepcWire    = Wire(UInt(parameter.xlen))
        val traceMtvalWire   = Wire(UInt(parameter.xlen))
        val traceMipWire     = Wire(UInt(parameter.xlen))
        val traceMcauseWire  = Wire(UInt(parameter.xlen))
        traceMstatusWire := csrMstatus.asUInt
        traceMieWire     := csrMie.asUInt
        traceMtvecWire   := csrMtvec
        traceMepcWire    := csrMepc
        traceMtvalWire   := csrMtval
        traceMipWire     := irqMip.asUInt
        traceMcauseWire  := csrMcause.asUInt
        probe.trace_mstatus.foreach(_ <== traceMstatusWire)
        probe.trace_mie.foreach(_ <== traceMieWire)
        probe.trace_mtvec.foreach(_ <== traceMtvecWire)
        probe.trace_mepc.foreach(_ <== traceMepcWire)
        probe.trace_mtval.foreach(_ <== traceMtvalWire)
        probe.trace_mip.foreach(_ <== traceMipWire)
        probe.trace_mcause.foreach(_ <== traceMcauseWire)
