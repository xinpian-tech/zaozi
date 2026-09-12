// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.iomux

import me.jiuyang.stdlib.*
import me.jiuyang.stdlib.default.{*, given}
import me.jiuyang.stdlib.mmio.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class IOMuxRoute(pin: Int, slot: Int)

given upickle.default.ReadWriter[IOMuxRoute] = upickle.default.macroRW

given mainargs.TokensReader.Simple[IOMuxRoute]:
  def shortName = "route"
  def read(strs: Seq[String]): Right[Nothing, IOMuxRoute] = Right(upickle.default.read[IOMuxRoute](strs.head))

case class IOMuxLsChannel(channel: Int, receive: Boolean = false)

given upickle.default.ReadWriter[IOMuxLsChannel] = upickle.default.macroRW

case class IOMuxLsPool(pins: Seq[Int], channels: Seq[IOMuxLsChannel], reset: Option[Int] = None)

given upickle.default.ReadWriter[IOMuxLsPool] = upickle.default.macroRW

given mainargs.TokensReader.Simple[IOMuxLsPool]:
  def shortName = "lsPool"
  def read(strs: Seq[String]): Right[Nothing, IOMuxLsPool] = Right(upickle.default.read[IOMuxLsPool](strs.head))

case class IOMuxParameter(
  pinCount:     Int,
  routes:       Seq[IOMuxRoute] = Seq.empty,
  hsSlots:      Int,
  dataWidth:    Int,
  addressWidth: Int,
  lsPools:      Seq[IOMuxLsPool] = Seq.empty)
    extends Parameter:
  require(pinCount >= 1 && pinCount <= 256, "pin indices must fit the 8-bit LS pin selector")
  require(hsSlots >= 1 && hsSlots <= 16, "HS slots must fit the four-bit selector lane")
  require(dataWidth == 32 || dataWidth == 64, "dataWidth must be 32 or 64")
  require(
    routes.forall(r => r.pin >= 0 && r.pin < pinCount && r.slot >= 0 && r.slot < hsSlots),
    "route pin or slot is out of range"
  )
  require(routes.distinct.size == routes.size, "a pin and slot must not be routed twice")

  private val lsPins     = lsPools.flatMap(_.pins)
  private val lsChannels = lsPools.flatMap(_.channels.map(_.channel))
  require(lsPools.size <= 255, "LS pool count must fit one byte")
  require(lsPools.forall(p => p.pins.nonEmpty && p.channels.nonEmpty), "LS pools need pins and channels")
  require(lsPins.forall(p => p >= 0 && p < pinCount), "LS pin is out of range")
  require(lsPins.distinct.size == lsPins.size, "an LS pin must belong to one pool")
  require(lsChannels.forall(c => c >= 0 && c <= 255), "LS channel must fit one byte")
  require(lsChannels.distinct.size == lsChannels.size, "an LS channel must belong to one pool")
  require(
    lsPools.forall(p => p.reset.forall(c => p.channels.exists(_.channel == c))),
    "LS reset must name a pool channel"
  )
  require(!routes.exists(r => r.slot == 0 && lsPins.contains(r.pin)), "LS pins reserve HS slot 0")

  val lsChannelCount        = lsChannels.maxOption.fold(0)(_ + 1)
  val lsPoolByPin           = lsPools.flatMap(pool => pool.pins.map(_ -> pool)).toMap
  val lsPoolByReceiver      = lsPools.flatMap(pool => pool.channels.filter(_.receive).map(_.channel -> pool)).toMap
  val byteBits              = Integer.numberOfTrailingZeros(dataWidth / 8)
  val windowBytes           = (BigInt(1) << addressWidth).min(0x4000)
  val selectorWidth         = (Integer.SIZE - Integer.numberOfLeadingZeros(hsSlots - 1)).max(1)
  val verification          = Layer("Verification")
  val kind                  = RegField("type", 32).readValue
  val capability            = RegField("capability", 32).readValue
  val feature               = RegField("feature", 32).readValue
  val lsCapability          = Option.when(lsPools.nonEmpty)(RegField("ls_capability", 32).readValue)
  val lsSelectors           = lsPins.sorted.map(p => p -> RegField(s"pin_${p}_ls_select", 8).readValue.writeValue)
  val lsRxPins              = lsPoolByReceiver.keys.toSeq.sorted.map(c => c -> RegField(s"ls_${c}_rx_pin", 8).readValue.writeValue)
  val selectors             = (0 until pinCount).map(p => RegField(s"pin_${p}_select", selectorWidth).readValue.writeValue)
  private val selectorWords = selectors.zipWithIndex
    .grouped(dataWidth / 4)
    .zipWithIndex
    .map: (word, index) =>
      (0x100 + index * (dataWidth / 8)) -> word.flatMap: (field, pin) =>
        Seq(field) ++ Option.when(selectorWidth < 4)(RegField.reserved(s"pin_${pin}_reserved", 4 - selectorWidth))
  val regMap                = RegMapDefinition(
    indexWidth = addressWidth - byteBits,
    dataWidth = dataWidth,
    assertionLayer = verification,
    queueEntries = 1,
    reportError = true
  )(
    (Seq(0x4 -> Seq(kind), 0x8 -> Seq(capability), 0xc -> Seq(feature)) ++ selectorWords ++
      lsCapability.toSeq.map(f => 0x10 -> Seq(f)) ++
      lsSelectors.map((p, f) => (0xc00 + p) -> Seq(f)) ++
      lsRxPins.map((c, f) => (0xd00 + c) -> Seq(f)))*
  )

given upickle.default.ReadWriter[IOMuxParameter] = upickle.default.macroRW

// The address is a local byte offset; mask selects write bytes.
class IOMuxRequest(addressWidth: Int, dataWidth: Int) extends Bundle:
  val read    = Aligned(Bool())
  val address = Aligned(Bits(addressWidth))
  val data    = Aligned(Bits(dataWidth))
  val mask    = Aligned(Bits(dataWidth / 8))

class IOMuxLayers(parameter: IOMuxParameter) extends LayerInterface(parameter):
  def layers = Seq(parameter.verification)

class IOMuxIO(parameter: IOMuxParameter) extends HWBundle(parameter):
  val clock           = Flipped(Clock())
  // Reset selects HS slot 0, including the LS pool on member pins.
  val resetN          = Flipped(Reset())
  val req             = Flipped(Decoupled(new IOMuxRequest(parameter.addressWidth, parameter.dataWidth)))
  val rsp             = Aligned(Decoupled(new RegMapResponse(parameter.dataWidth, true)))
  val padInputValue   = Flipped(Bits(parameter.pinCount))
  val padInputEnable  = Aligned(Bits(parameter.pinCount))
  val padOutputValue  = Aligned(Bits(parameter.pinCount))
  val padOutputEnable = Aligned(Bits(parameter.pinCount))
  // Bit i uses routes(i). Each pad input reaches its declared routes, independent of selection.
  val inputEnable     = Flipped(Bits(parameter.routes.size))
  val outputValue     = Flipped(Bits(parameter.routes.size))
  val outputEnable    = Flipped(Bits(parameter.routes.size))
  val inputValue      = Aligned(Bits(parameter.routes.size))
  // LS bit indices are global channel numbers. Receive selection is independent of HS and LS transmit selection.
  val lsInputEnable   = Option.when(parameter.lsPools.nonEmpty)(Flipped(Bits(parameter.lsChannelCount)))
  val lsOutputValue   = Option.when(parameter.lsPools.nonEmpty)(Flipped(Bits(parameter.lsChannelCount)))
  val lsOutputEnable  = Option.when(parameter.lsPools.nonEmpty)(Flipped(Bits(parameter.lsChannelCount)))
  val lsInputValue    = Option.when(parameter.lsPools.nonEmpty)(Aligned(Bits(parameter.lsChannelCount)))

class IOMuxProbe(parameter: IOMuxParameter) extends DVBundle[IOMuxParameter, IOMuxLayers](parameter)

@generator
object IOMux extends Generator[IOMuxParameter, IOMuxLayers, IOMuxIO, IOMuxProbe]:
  def architecture(parameter: IOMuxParameter) =
    val io           = summon[Interface[IOMuxIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.asyncActiveLow(io.resetN)

    val req       = Wire(Decoupled(new RegMapRequest(parameter.regMap.indexWidth, parameter.dataWidth)))
    val wordIndex = io.req.bits.address.bits(parameter.addressWidth - 1, parameter.byteBits).asUInt
    val aligned   = !io.req.bits.address.bits(parameter.byteBits - 1, 0).orR
    val inWindow  = io.req.bits.address.asUInt <= (parameter.windowBytes - 1).U(parameter.addressWidth)
    // Non-word addresses inside the window use an empty word instead of aliasing a register.
    req.bits.index := (!aligned & inWindow) ? (
      BigInt(0x80 / (parameter.dataWidth / 8)).U(parameter.regMap.indexWidth),
      wordIndex
    )
    req.bits.read  := io.req.bits.read
    req.bits.data  := io.req.bits.data
    req.bits.mask  := io.req.bits.read ? (
      BigInt((1 << (parameter.dataWidth / 8)) - 1).B(parameter.dataWidth / 8),
      io.req.bits.mask
    )
    req.valid      := io.req.valid
    io.req.ready   := req.ready

    val selectors        = parameter.selectors.map(field => RegInit(BigInt(0).B(field.width)))
    val lsSelectors      = parameter.lsSelectors.map: (pin, field) =>
      pin -> RegInit(BigInt(parameter.lsPoolByPin(pin).reset.getOrElse(0)).B(field.width))
    val lsRxPins         = parameter.lsRxPins.map((channel, field) => channel -> RegInit(BigInt(0).B(field.width)))
    val lsSelectByPin    = lsSelectors.toMap
    val lsRxPinByChannel = lsRxPins.toMap
    val accesses         = Seq(
      parameter.kind.read(BigInt(0x494f4d58).B(32)),
      parameter.capability.read(BigInt(parameter.pinCount | (parameter.hsSlots << 16)).B(32)),
      parameter.feature.read(BigInt(if parameter.lsPools.nonEmpty then 0x20 else 0).B(32))
    ) ++ parameter.lsCapability.toSeq.map(
      _.read(BigInt(parameter.lsChannelCount | (parameter.lsPools.size << 16)).B(32))
    ) ++ parameter.selectors
      .zip(selectors)
      .flatMap: (field, value) =>
        Seq(field.read(value), field.write(value))
    val lsAccesses       = (parameter.lsSelectors ++ parameter.lsRxPins)
      .zip(lsSelectors ++ lsRxPins)
      .flatMap:
        case ((_, field), (_, value)) => Seq(field.read(value), field.write(value))
    parameter.regMap(req, io.rsp, zeroFillBytes = parameter.windowBytes)((accesses ++ lsAccesses)*)

    def select(pin: Int, values: Referable[Bits], lsValues: Option[Referable[Bits]]): Referable[Bool] =
      val slow = parameter.lsPoolByPin
        .get(pin)
        .fold(false.B: Referable[Bool]): pool =>
          val selected = pool.channels.foldLeft(false.B: Referable[Bool]): (result, channel) =>
            (lsSelectByPin(pin) === BigInt(channel.channel).B(8)) ? (lsValues.get.bit(channel.channel), result)
          (selectors(pin) === BigInt(0).B(parameter.selectorWidth)) & selected
      parameter.routes.zipWithIndex
        .filter(_._1.pin == pin)
        .foldLeft(slow):
          case (result, (route, lane)) =>
            (selectors(pin) === BigInt(route.slot).B(parameter.selectorWidth)) ? (values.bit(lane), result)

    io.padInputEnable  := (0 until parameter.pinCount).map(p => select(p, io.inputEnable, io.lsInputEnable)).toVec.asBits
    io.padOutputValue  := (0 until parameter.pinCount).map(p => select(p, io.outputValue, io.lsOutputValue)).toVec.asBits
    io.padOutputEnable := (0 until parameter.pinCount)
      .map(p => select(p, io.outputEnable, io.lsOutputEnable))
      .toVec
      .asBits
    io.inputValue      := parameter.routes.reverse
      .map(r => io.padInputValue.bit(r.pin).asBits: Referable[Bits])
      .reduceOption(_ ## _)
      .getOrElse(BigInt(0).B(0))
    io.lsInputValue.foreach: value =>
      value := (0 until parameter.lsChannelCount)
        .map: channel =>
          parameter.lsPoolByReceiver
            .get(channel)
            .fold(false.B: Referable[Bool]): pool =>
              pool.pins.foldLeft(false.B: Referable[Bool]): (result, pin) =>
                (lsRxPinByChannel(channel) === BigInt(pin).B(8)) ? (io.padInputValue.bit(pin), result)
        .toVec
        .asBits

    layer("Verification"):
      given ClockEvent = posedge(io.clock)

      parameter.routes.foreach:               route =>
        Cover(
          (selectors(route.pin) === BigInt(route.slot).B(parameter.selectorWidth)).S,
          io.resetN.asBool,
          s"iomux_hs_pin_${route.pin}_slot_${route.slot}"
        )
      parameter.lsPools.zipWithIndex.foreach: (pool, index) =>
        val transmit = pool.pins.map: pin =>
          (selectors(pin) === BigInt(0).B(parameter.selectorWidth)) &
            pool.channels.map(c => lsSelectByPin(pin) === BigInt(c.channel).B(8)).reduce(_ | _)
        Cover(transmit.reduce(_ | _).S, io.resetN.asBool, s"iomux_ls_pool_${index}_transmit")
        pool.channels
          .filter(_.receive)
          .foreach: channel =>
            val receiveWithoutTransmit = pool.pins.map: pin =>
              (lsRxPinByChannel(channel.channel) === BigInt(pin).B(8)) &
                (selectors(pin) =/= BigInt(0).B(parameter.selectorWidth))
            Cover(
              (receiveWithoutTransmit.reduce(_ | _) & io.lsInputValue.get.bit(channel.channel)).S,
              io.resetN.asBool,
              s"iomux_ls_channel_${channel.channel}_independent_receive"
            )
      Cover((io.req.fire & !aligned & inWindow).S, io.resetN.asBool, "iomux_unaligned_access")
