// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.stdlib.mmio.examples

import me.jiuyang.stdlib.*
import me.jiuyang.stdlib.mmio.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class MinimalRegMapParameter() extends Parameter:
  val enable       = RegField("enable", 8).readValue.writeValue
  val busy         = RegField("busy", 1).readValue
  val busyReserved = RegField.reserved("busyReserved", 7)
  val command      = RegField("command", 8).writeReadyValid
  val snapshot     = RegField("snapshot", 1).readRequestResponse.writeValue
  val verification = Layer("Verification")
  val regMap       = RegMapDefinition(
    indexWidth = 6,
    dataWidth = 32,
    queueEntries = 1,
    reportError = true,
    assertionLayer = verification
  )(
    0x00 -> Seq(enable),
    0x04 -> Seq(busy, busyReserved),
    0x08 -> Seq(command),
    0x0c -> Seq(snapshot)
  )

given upickle.default.ReadWriter[MinimalRegMapParameter] = upickle.default.macroRW

class MinimalRegMapLayers(parameter: MinimalRegMapParameter) extends LayerInterface(parameter):
  def layers = Seq(parameter.regMap.assertionLayer)

class MinimalRegMapIO(parameter: MinimalRegMapParameter) extends HWBundle(parameter):
  val clock        = Flipped(Clock())
  val reset        = Flipped(Reset())
  val req          = Flipped(Decoupled(new RegMapRequest(parameter.regMap.indexWidth, parameter.regMap.dataWidth)))
  val rsp          = Aligned(Decoupled(new RegMapResponse(parameter.regMap.dataWidth, parameter.regMap.reportError)))
  val busy         = Flipped(Bool())
  val enable       = Aligned(Bits(parameter.enable.width))
  val commandReady = Flipped(Bool())
  val commandValid = Aligned(Bool())
  val commandData  = Aligned(Bits(parameter.command.width))

class MinimalRegMapProbe(parameter: MinimalRegMapParameter)
    extends DVBundle[MinimalRegMapParameter, MinimalRegMapLayers](parameter)

@generator
object MinimalRegMap
    extends Generator[
      MinimalRegMapParameter,
      MinimalRegMapLayers,
      MinimalRegMapIO,
      MinimalRegMapProbe
    ]:
  def architecture(parameter: MinimalRegMapParameter) =
    val io                    = summon[Interface[MinimalRegMapIO]]
    given ClockScope          = ClockScope.posedge(io.clock)
    given ResetScope          = ResetScope.syncActiveHigh(io.reset)
    val enable                = RegInit(BigInt(0).U(parameter.enable.width).asBits)
    val snapshotWritable      = RegInit(BigInt(0).U(parameter.snapshot.width).asBits)
    val snapshotPending       = RegInit(false.B)
    val snapshotData          = Reg(Bits(parameter.snapshot.width))
    val snapshotRequestReady  = Wire(Bool())
    val snapshotRequestValid  = Wire(Bool())
    val snapshotResponseReady = Wire(Bool())
    val snapshotResponseValid = Wire(Bool())
    val snapshotInputFire     = snapshotRequestValid & snapshotRequestReady
    val snapshotOutputFire    = snapshotResponseValid & snapshotResponseReady

    snapshotPending       := snapshotInputFire ? (true.B, snapshotOutputFire ? (false.B, snapshotPending))
    snapshotData          := snapshotInputFire ? (io.busy.asBits, snapshotData)
    snapshotRequestReady  := !snapshotPending
    snapshotResponseValid := snapshotPending

    io.enable := enable
    parameter.regMap(io.req, io.rsp)(
      parameter.enable.read(enable),
      parameter.enable.write(enable),
      parameter.busy.read(io.busy.asBits),
      parameter.command.write(io.commandReady, io.commandValid, io.commandData),
      parameter.snapshot.read(
        snapshotRequestReady,
        snapshotRequestValid,
        snapshotResponseReady,
        snapshotResponseValid,
        snapshotData
      ),
      parameter.snapshot.write(snapshotWritable)
    )
