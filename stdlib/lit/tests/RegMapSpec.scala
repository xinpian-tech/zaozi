// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{read} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class RegMapRead %s --
// DEFINE: %{write} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class RegMapWrite %s --

// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{read} config %t.dir/read.json
// RUN: cd %t.dir && %{read} design %t.dir/read.json
// RUN: firtool %t.dir/RegMapRead.mlirbc --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t.dir/read.hw.mlir --disable-output
// RUN: sed -E -e '/hw.instance "verification"/d' -e '/sv.bind <@RegMapRead::@verification>/d' %t.dir/read.hw.mlir > %t.dir/read.nobind.hw.mlir
// RUN: circt-opt %t.dir/read.nobind.hw.mlir --strip-emit --strip-om --symbol-dce -o %t.dir/read.nomacro.hw.mlir
// RUN: sed -E -e '/sv.macro.decl/d' -e 's/ sym @[A-Za-z0-9_.$-]+//' %t.dir/read.nomacro.hw.mlir > %t.dir/read.noinner.hw.mlir
// RUN: circt-opt %t.dir/read.noinner.hw.mlir --canonicalize --cse -o %t.dir/read.clean.hw.mlir
// RUN: circt-bmc %t.dir/read.clean.hw.mlir --module=RegMapRead_CheckContract_0 -b 10 --ignore-asserts-until=2 --shared-libs=%Z3LIB --run | FileCheck %s --check-prefix=BMC
// RUN: firtool %t.dir/RegMapRead.mlirbc | FileCheck %s --check-prefix=LAYERS
// RUN: %{write} config %t.dir/write.json
// RUN: cd %t.dir && %{write} design %t.dir/write.json
// RUN: firtool %t.dir/RegMapWrite.mlirbc --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t.dir/write.hw.mlir --disable-output
// RUN: sed -E -e '/hw.instance "verification"/d' -e '/sv.bind <@RegMapWrite::@verification>/d' %t.dir/write.hw.mlir > %t.dir/write.nobind.hw.mlir
// RUN: circt-opt %t.dir/write.nobind.hw.mlir --strip-emit --strip-om --symbol-dce -o %t.dir/write.nomacro.hw.mlir
// RUN: sed -E -e '/sv.macro.decl/d' -e 's/ sym @[A-Za-z0-9_.$-]+//' %t.dir/write.nomacro.hw.mlir > %t.dir/write.noinner.hw.mlir
// RUN: circt-opt %t.dir/write.noinner.hw.mlir --canonicalize --cse -o %t.dir/write.clean.hw.mlir
// RUN: circt-bmc %t.dir/write.clean.hw.mlir --module=RegMapWrite_CheckContract_0 -b 10 --ignore-asserts-until=2 --shared-libs=%Z3LIB --run | FileCheck %s --check-prefix=BMC
// RUN: rm -rf %t.dir

// LAYERS-LABEL: module RegMapRead_Verification(
// LAYERS:         assert property
// LAYERS:         cover property
// LAYERS-LABEL: module RegMapRead(
// LAYERS-NOT:     assert
// LAYERS-NOT:     cover

// BMC: Bound reached with no violations!

import me.jiuyang.stdlib.*
import me.jiuyang.stdlib.mmio.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.magic.macros.generator
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

// queueEntries = 1: the response lands the cycle after its request is accepted.
// Properties relate this cycle to the previous one, so they hold from any initial state without a reset assumption.
// The RegMap assertion layer is stripped: the contracts are an independent oracle.
// Every property here fails on the RegMap as it stands before this series.
// Clauses are contract operands because an operand-free `Contract:` crashes lower-contracts.

case class RegMapReadParameter() extends Parameter:
  val status     = RegField("status", 8).readValue
  val sample     = RegField("sample", 8).readReadyValid
  val verification = Layer("Verification")
  val regMap     = RegMapDefinition(
    indexWidth = 6,
    dataWidth = 32,
    queueEntries = 1,
    reportError = true,
    assertionLayer = verification
  )(
    0x00 -> Seq(status),
    0x04 -> Seq(sample)
  )

given upickle.default.ReadWriter[RegMapReadParameter] = upickle.default.macroRW

class RegMapReadLayers(parameter: RegMapReadParameter) extends LayerInterface(parameter):
  def layers = Seq(parameter.verification)

class RegMapReadIO(parameter: RegMapReadParameter) extends HWBundle(parameter):
  val clock        = Flipped(Clock())
  val reset        = Flipped(Reset())
  val req          = Flipped(Decoupled(new RegMapRequest(parameter.regMap.indexWidth, parameter.regMap.dataWidth)))
  val rsp          = Aligned(Decoupled(new RegMapResponse(parameter.regMap.dataWidth, parameter.regMap.reportError)))
  val status       = Flipped(Bits(parameter.status.width))
  val sampleReady  = Aligned(Bool())
  val sampleValid  = Flipped(Bool())
  val sampleData   = Flipped(Bits(parameter.sample.width))

class RegMapReadProbe(parameter: RegMapReadParameter) extends DVBundle[RegMapReadParameter, RegMapReadLayers](parameter)

@generator
object RegMapRead extends Generator[RegMapReadParameter, RegMapReadLayers, RegMapReadIO, RegMapReadProbe]:
  override def moduleName(parameter: RegMapReadParameter): String = "RegMapRead"

  def architecture(parameter: RegMapReadParameter) =
    val io           = summon[Interface[RegMapReadIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)

    parameter.regMap(io.req, io.rsp)(
      parameter.status.read(io.status),
      parameter.sample.read(io.sampleReady, io.sampleValid, io.sampleData)
    )

    def word(field: Referable[Bits]): Referable[Bits] =
      BigInt(0).U(parameter.regMap.dataWidth - field.width).asBits ## field
    def acceptedRead(wordIndex: Int): Referable[Bool] =
      io.req.valid & io.req.ready & io.req.bits.read &
        (io.req.bits.index === BigInt(wordIndex).U(parameter.regMap.indexWidth))
    val lane0         = io.req.bits.mask.bit(0)
    val sampleFire    = io.sampleReady & io.sampleValid
    val responseError = io.rsp.bits.error.get
    val responseRead  = io.rsp.valid & io.rsp.bits.read

    // Previous cycle.
    val wasStalled     = RegInit(false.B)
    val heldRead       = Reg(Bool())
    val heldData       = Reg(Bits(parameter.regMap.dataWidth))
    val heldError      = Reg(Bool())
    val statusAccepted = RegInit(false.B)
    val heldStatus     = Reg(Bits(parameter.status.width))
    val sampleAccepted = RegInit(false.B)
    val heldSample     = Reg(Bits(parameter.sample.width))
    wasStalled     := io.rsp.valid & !io.rsp.ready
    heldRead       := io.rsp.bits.read
    heldData       := io.rsp.bits.data
    heldError      := responseError
    statusAccepted := acceptedRead(0)
    heldStatus     := io.status
    sampleAccepted := sampleFire
    heldSample     := io.sampleData

    val responseStable  = !wasStalled | (
      io.rsp.valid & (io.rsp.bits.read === heldRead) & (io.rsp.bits.data === heldData) & (responseError === heldError)
    )
    val statusDelivered = !statusAccepted | (responseRead & (io.rsp.bits.data === word(heldStatus)))
    val sampleExact     = sampleFire === (acceptedRead(1) & lane0)
    val sampleDelivered = !sampleAccepted | (responseRead & (io.rsp.bits.data === word(heldSample)))

    Contract((responseStable, statusDelivered, sampleExact, sampleDelivered)):
      case (responseStable, statusDelivered, sampleExact, sampleDelivered) =>
        Ensure(responseStable.I)
        Ensure(statusDelivered.I)
        Ensure(sampleExact.I)
        Ensure(sampleDelivered.I)

case class RegMapWriteParameter() extends Parameter:
  val command    = RegField("command", 8).writeReadyValid
  val ctrl       = RegField("ctrl", 8).writeValue
  val verification = Layer("Verification")
  val regMap     = RegMapDefinition(
    indexWidth = 6,
    dataWidth = 32,
    queueEntries = 1,
    reportError = true,
    assertionLayer = verification
  )(
    0x00 -> Seq(command),
    0x04 -> Seq(ctrl)
  )

given upickle.default.ReadWriter[RegMapWriteParameter] = upickle.default.macroRW

class RegMapWriteLayers(parameter: RegMapWriteParameter) extends LayerInterface(parameter):
  def layers = Seq(parameter.verification)

class RegMapWriteIO(parameter: RegMapWriteParameter) extends HWBundle(parameter):
  val clock        = Flipped(Clock())
  val reset        = Flipped(Reset())
  val req          = Flipped(Decoupled(new RegMapRequest(parameter.regMap.indexWidth, parameter.regMap.dataWidth)))
  val rsp          = Aligned(Decoupled(new RegMapResponse(parameter.regMap.dataWidth, parameter.regMap.reportError)))
  val commandReady = Flipped(Bool())
  val commandValid = Aligned(Bool())
  val commandData  = Aligned(Bits(parameter.command.width))

class RegMapWriteProbe(parameter: RegMapWriteParameter)
    extends DVBundle[RegMapWriteParameter, RegMapWriteLayers](parameter)

@generator
object RegMapWrite extends Generator[RegMapWriteParameter, RegMapWriteLayers, RegMapWriteIO, RegMapWriteProbe]:
  override def moduleName(parameter: RegMapWriteParameter): String = "RegMapWrite"

  def architecture(parameter: RegMapWriteParameter) =
    val io           = summon[Interface[RegMapWriteIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)

    val ctrl = RegInit(BigInt(0).U(parameter.ctrl.width).asBits)

    parameter.regMap(io.req, io.rsp)(
      parameter.command.write(io.commandReady, io.commandValid, io.commandData),
      parameter.ctrl.write(ctrl)
    )

    def acceptedWrite(wordIndex: Int): Referable[Bool] =
      io.req.valid & io.req.ready & !io.req.bits.read &
        (io.req.bits.index === BigInt(wordIndex).U(parameter.regMap.indexWidth))
    val lane0       = io.req.bits.mask.bit(0)
    val writeData   = io.req.bits.data.bits(parameter.ctrl.width - 1, 0)
    val commandFire = io.commandValid & io.commandReady

    // Previous cycle.
    val previousReset     = Reg(Bool())
    val requestWasStalled = RegInit(false.B)
    val heldRequestRead   = Reg(Bool())
    val heldRequestIndex  = Reg(UInt(parameter.regMap.indexWidth))
    val heldRequestData   = Reg(Bits(parameter.regMap.dataWidth))
    val heldRequestMask   = Reg(Bits(parameter.regMap.dataWidth / 8))
    val commandWasStalled = RegInit(false.B)
    val heldCommandData   = Reg(Bits(parameter.command.width))
    val writeAccepted     = RegInit(false.B)
    val ctrlWritten       = RegInit(false.B)
    val heldWriteData     = Reg(Bits(parameter.ctrl.width))
    val heldCtrl          = Reg(Bits(parameter.ctrl.width))
    previousReset     := io.reset.asBool
    requestWasStalled := io.req.valid & !io.req.ready
    heldRequestRead   := io.req.bits.read
    heldRequestIndex  := io.req.bits.index
    heldRequestData   := io.req.bits.data
    heldRequestMask   := io.req.bits.mask
    commandWasStalled := io.commandValid & !io.commandReady
    heldCommandData   := io.commandData
    writeAccepted     := io.req.valid & io.req.ready & !io.req.bits.read
    ctrlWritten       := acceptedWrite(1) & lane0
    heldWriteData     := writeData
    heldCtrl          := ctrl

    val requestStable  = !requestWasStalled | (
      io.req.valid & (io.req.bits.read === heldRequestRead) & (io.req.bits.index === heldRequestIndex) &
        (io.req.bits.data === heldRequestData) & (io.req.bits.mask === heldRequestMask)
    )
    val commandStable  = !commandWasStalled | (io.commandValid & (io.commandData === heldCommandData))
    val commandExact   =
      (commandFire === (acceptedWrite(0) & lane0)) & (!commandFire | (io.commandData === writeData))
    val writeAnswered  = !writeAccepted | (io.rsp.valid & !io.rsp.bits.read)
    val ctrlUpdated    = !ctrlWritten | (ctrl === heldWriteData)
    val ctrlHeld       = previousReset | ctrlWritten | (ctrl === heldCtrl)

    Contract((requestStable, commandStable, commandExact, writeAnswered, ctrlUpdated, ctrlHeld)):
      case (requestStable, commandStable, commandExact, writeAnswered, ctrlUpdated, ctrlHeld) =>
        Require(requestStable.I)
        Ensure(commandStable.I)
        Ensure(commandExact.I)
        Ensure(writeAnswered.I)
        Ensure(ctrlUpdated.I)
        Ensure(ctrlHeld.I)
