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
  lsPools:      Seq[IOMuxLsPool] = Seq.empty,
  version:      Long = 0)
    extends Parameter:
  require(pinCount > 0, "pinCount must be positive")
  require(hsSlots > 0, "hsSlots must be positive")
  require(version >= 0 && version <= 0xffffffffL, "version must fit an unsigned 32-bit word")
  require(dataWidth == 32 || dataWidth == 64, "dataWidth must be 32 or 64")
  require(
    routes.forall(r => r.pin >= 0 && r.pin < pinCount && r.slot >= 0 && r.slot < hsSlots),
    "route pin or slot is out of range"
  )
  require(routes.distinct.size == routes.size, "a pin and slot must not be routed twice")

  private val lsPins     = lsPools.flatMap(_.pins)
  private val lsChannels = lsPools.flatMap(_.channels.map(_.channel))
  require(lsPools.forall(p => p.pins.nonEmpty && p.channels.nonEmpty), "LS pools need pins and channels")
  require(lsPins.forall(p => p >= 0 && p < pinCount), "LS pin is out of range")
  require(lsPins.distinct.size == lsPins.size, "an LS pin must belong to one pool")
  require(lsChannels.forall(_ >= 0), "LS channel must be nonnegative")
  require(lsChannels.distinct.size == lsChannels.size, "an LS channel must belong to one pool")
  require(
    lsPools.forall(p => p.reset.forall(c => p.channels.exists(_.channel == c))),
    "LS reset must name a pool channel"
  )
  require(!routes.exists(r => r.slot == 0 && lsPins.contains(r.pin)), "LS pins reserve HS slot 0")

  private val lsSpan = lsChannels.maxOption.fold(BigInt(0))(c => BigInt(c) + 1)
  require(lsSpan.isValidInt, "LS channel span must fit the vector size type")

  val lsChannelCount   = lsSpan.toInt
  val lsPoolByPin      = lsPools.flatMap(pool => pool.pins.map(_ -> pool)).toMap
  val lsPoolByReceiver = lsPools.flatMap(pool => pool.channels.filter(_.receive).map(_.channel -> pool)).toMap
  val byteBits         = Integer.numberOfTrailingZeros(dataWidth / 8)
  val selectorWidth    = (BigInt(hsSlots) - 1).bitLength.max(1)
  val lsSelectorWidth  = (lsSpan - 1).max(0).bitLength.max(8)
  val lsRxWidth        = (BigInt(pinCount) - 1).bitLength.max(8)

  private def laneWidth(width:  Int, minimum: Int): Int    = 1 << BigInt(width.max(minimum) - 1).bitLength
  private def arrayBytes(count: Int, lane:    Int): BigInt = ((BigInt(count) * lane + 63) / 64) * 8

  val selectorLaneWidth   = laneWidth(selectorWidth, 4)
  val lsSelectorLaneWidth = laneWidth(lsSelectorWidth, 8)
  val lsRxLaneWidth       = laneWidth(lsRxWidth, 8)
  val selectorBase        = BigInt(0x100)
  val lsSelectorBase      = selectorBase + arrayBytes(pinCount, selectorLaneWidth)
  val lsRxBase            = lsSelectorBase + (if lsPools.nonEmpty then arrayBytes(pinCount, lsSelectorLaneWidth) else 0)
  val windowBytes         = lsRxBase + arrayBytes(lsChannelCount, lsRxLaneWidth)
  val verification        = Layer("Verification")

  val identity = Seq(
    (0x00, "version", BigInt(version)),
    (0x04, "type", BigInt(0x494f4d58)),
    (0x08, "pin_count", BigInt(pinCount)),
    (0x0c, "feature", BigInt(if lsPools.nonEmpty then 0x20 else 0)),
    (0x10, "hs_slots", BigInt(hsSlots)),
    (0x14, "ls_channel_count", lsSpan),
    (0x18, "ls_pool_count", BigInt(lsPools.size))
  ).map((offset, name, value) => (BigInt(offset), RegField(name, 32).readValue, value))

  // Byte fields let a wide selector retain bytes that a write does not select.
  private def selectorFields(name: String, width: Int)
    : Seq[ReadWriteFieldDefinition[ValueReadDefinition, ValueWriteDefinition]] =
    (0 until width by 8).map(bit => RegField(s"${name}_${bit / 8}", (width - bit).min(8)).readValue.writeValue)

  val selectors             = (0 until pinCount).map(p => selectorFields(s"pin_${p}_select", selectorWidth))
  val lsSelectors           = lsPins.sorted.map(p => p -> selectorFields(s"pin_${p}_ls_select", lsSelectorWidth))
  val lsRxPins              = lsPoolByReceiver.keys.toSeq.sorted.map(c => c -> selectorFields(s"ls_${c}_rx_pin", lsRxWidth))
  private val selectorWords = selectors.zipWithIndex
    .grouped(dataWidth / selectorLaneWidth)
    .zipWithIndex
    .map: (word, index) =>
      RegMapRegister(
        selectorBase + BigInt(index) * (dataWidth / 8),
        word.flatMap: (fields, pin) =>
          fields ++ Option.when(selectorWidth < selectorLaneWidth)(
            RegField.reserved(s"pin_${pin}_reserved", selectorLaneWidth - selectorWidth)
          )
      )
  val regMap                = RegMapDefinition(
    indexWidth = addressWidth - byteBits,
    dataWidth = dataWidth,
    assertionLayer = verification,
    queueEntries = 1,
    reportError = true,
    registers = identity.map((offset, field, _) => RegMapRegister(offset, Seq(field))) ++ selectorWords ++
      lsSelectors.map((p, fields) => RegMapRegister(lsSelectorBase + BigInt(p) * lsSelectorLaneWidth / 8, fields)) ++
      lsRxPins.map((c, fields) => RegMapRegister(lsRxBase + BigInt(c) * lsRxLaneWidth / 8, fields))
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
  // header <config> writes software constants from the same layout used by design.
  def main(args: Array[String]): Unit = args.toList match
    case "header" :: config :: Nil =>
      val p         = upickle.default.read[IOMuxParameter](os.read(os.Path(config, os.pwd)))
      val constants = p.identity.flatMap: (offset, field, value) =>
        Seq(
          s"${field.name.toUpperCase(java.util.Locale.ROOT)}_OFFSET" -> offset,
          s"${field.name.toUpperCase(java.util.Locale.ROOT)}_VALUE"  -> value
        )
      val arrays    = Seq(
        "HS_SELECT_OFFSET"    -> p.selectorBase,
        "HS_SELECT_LANE_BITS" -> BigInt(p.selectorLaneWidth),
        "LS_SELECT_OFFSET"    -> p.lsSelectorBase,
        "LS_SELECT_LANE_BITS" -> BigInt(p.lsSelectorLaneWidth),
        "LS_RX_PIN_OFFSET"    -> p.lsRxBase,
        "LS_RX_PIN_LANE_BITS" -> BigInt(p.lsRxLaneWidth),
        "APERTURE"            -> p.windowBytes
      )
      (constants ++ arrays).foreach: (name, value) =>
        println(s"#define IOMUX_$name 0x${value.toString(16)}ULL")
    case _                         => this.mainImpl(args)

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

    def selector(
      fields: Seq[ReadWriteFieldDefinition[ValueReadDefinition, ValueWriteDefinition]],
      reset:  BigInt
    ): (Referable[Bits], Seq[AppliedRegAccess]) =
      val bytes    = fields.zipWithIndex.map: (field, index) =>
        RegInit(((reset >> (index * 8)) & ((BigInt(1) << field.width) - 1)).B(field.width))
      val value    = bytes.reverse.map(byte => byte: Referable[Bits]).reduce(_ ## _)
      val accesses = fields.zip(bytes).flatMap((field, byte) => Seq(field.read(byte), field.write(byte)))
      (value, accesses)

    val hsRegisters      = parameter.selectors.map(fields => selector(fields, 0))
    val lsRegisters      = parameter.lsSelectors.map: (pin, fields) =>
      pin -> selector(fields, BigInt(parameter.lsPoolByPin(pin).reset.getOrElse(0)))
    val lsRxRegisters    = parameter.lsRxPins.map((channel, fields) => channel -> selector(fields, 0))
    val selectors        = hsRegisters.map(_._1)
    val lsSelectByPin    = lsRegisters.map((pin, register) => pin -> register._1).toMap
    val lsRxPinByChannel = lsRxRegisters.map((channel, register) => channel -> register._1).toMap
    val accesses         = parameter.identity.map((_, field, value) => field.read(value.B(32))) ++
      hsRegisters.flatMap(_._2) ++ (lsRegisters ++ lsRxRegisters).flatMap(_._2._2)
    parameter.regMap(req, io.rsp, zeroFillBytes = parameter.windowBytes)(accesses*)

    def select(pin: Int, values: Referable[Bits], lsValues: Option[Referable[Bits]]): Referable[Bool] =
      val slow = parameter.lsPoolByPin
        .get(pin)
        .fold(false.B: Referable[Bool]): pool =>
          val selected = pool.channels.foldLeft(false.B: Referable[Bool]): (result, channel) =>
            (lsSelectByPin(pin) === BigInt(channel.channel)
              .B(parameter.lsSelectorWidth)) ? (lsValues.get.bit(channel.channel), result)
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
                (lsRxPinByChannel(channel) === BigInt(pin).B(parameter.lsRxWidth)) ? (io.padInputValue.bit(pin), result)
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
        val selection = pool.pins.map: pin =>
          (selectors(pin) === BigInt(0).B(parameter.selectorWidth)) &
            pool.channels.map(c => lsSelectByPin(pin) === BigInt(c.channel).B(parameter.lsSelectorWidth)).reduce(_ | _)
        Cover(selection.reduce(_ | _).S, io.resetN.asBool, s"iomux_ls_pool_${index}_selection")
        pool.channels
          .filter(_.receive)
          .foreach: channel =>
            val receiveWithHs = pool.pins.map: pin =>
              (lsRxPinByChannel(channel.channel) === BigInt(pin).B(parameter.lsRxWidth)) &
                (selectors(pin) =/= BigInt(0).B(parameter.selectorWidth))
            Cover(
              (receiveWithHs.reduce(_ | _) & io.lsInputValue.get.bit(channel.channel)).S,
              io.resetN.asBool,
              s"iomux_ls_channel_${channel.channel}_independent_receive"
            )
      Cover((io.req.fire & !aligned & inWindow).S, io.resetN.asBool, "iomux_unaligned_access")
