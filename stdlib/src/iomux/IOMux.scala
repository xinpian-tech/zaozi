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

import scala.collection.mutable.ArrayBuffer

case class IOMuxRoute(pin: Int, slot: Int, tie: Option[Boolean] = None, pad: IOMuxPadRoute = IOMuxPadRoute())

given upickle.default.ReadWriter[IOMuxRoute] = upickle.default.macroRW

given mainargs.TokensReader.Simple[IOMuxRoute]:
  def shortName = "route"
  def read(strs: Seq[String]): Right[Nothing, IOMuxRoute] = Right(upickle.default.read[IOMuxRoute](strs.head))

case class IOMuxLsChannel(channel: Int, receive: Boolean = false, tie: Option[Boolean] = None)

given upickle.default.ReadWriter[IOMuxLsChannel] = upickle.default.macroRW

case class IOMuxLsPool(pins: Seq[Int], channels: Seq[IOMuxLsChannel], reset: Option[Int] = None)

given upickle.default.ReadWriter[IOMuxLsPool] = upickle.default.macroRW

given mainargs.TokensReader.Simple[IOMuxLsPool]:
  def shortName = "lsPool"
  def read(strs: Seq[String]): Right[Nothing, IOMuxLsPool] = Right(upickle.default.read[IOMuxLsPool](strs.head))

case class IOMuxOption(
  gpio:       Boolean = false,
  interrupt:  Boolean = false,
  padControl: Boolean = false,
  invert:     Boolean = false,
  rxOverride: Boolean = false)

given upickle.default.ReadWriter[IOMuxOption] = upickle.default.macroRW

given mainargs.TokensReader.Simple[IOMuxOption]:
  def shortName = "option"
  def read(strs: Seq[String]): Right[Nothing, IOMuxOption] = Right(upickle.default.read[IOMuxOption](strs.head))

case class IOMuxParameter(
  pinCount:     Int,
  routes:       Seq[IOMuxRoute] = Seq.empty,
  hsSlots:      Int,
  dataWidth:    Int,
  addressWidth: Int,
  lsPools:      Seq[IOMuxLsPool] = Seq.empty,
  version:      Long = 0,
  option:       IOMuxOption = IOMuxOption(),
  pad:          Option[IOMuxPadParameter] = None)
    extends Parameter:
  require(pinCount > 0, "pinCount must be positive")
  require(hsSlots > 0, "hsSlots must be positive")
  require(version >= 0 && version <= 0xffffffffL, "version must fit an unsigned 32-bit word")
  require(dataWidth == 32 || dataWidth == 64, "dataWidth must be 32 or 64")
  require(
    routes.forall(r => r.pin >= 0 && r.pin < pinCount && r.slot >= 0 && r.slot < hsSlots),
    "route pin or slot is out of range"
  )
  require(routes.map(r => (r.pin, r.slot)).distinct.size == routes.size, "a pin and slot must not be routed twice")

  require(pad.nonEmpty || routes.forall(_.pad == IOMuxPadRoute()), "pad requests require a pad model")
  pad.foreach: model =>
    require(model.pinClass.size == pinCount, "pad pinClass must describe every pin")
    routes.foreach(r => model.validateRoute(r.pin, r.pad))
  require(
    !option.padControl || pad.exists(model => model.hasPull || model.controlWidths.exists(_ > 0)),
    "padControl requires a selectable pad model"
  )
  val hasPull        = pad.exists(_.hasPull)
  val controlWidths  = pad.toSeq.flatMap(_.controlWidths)
  val padSourceNames = Option.when(hasPull)("pull").toSeq ++
    controlWidths.zipWithIndex.collect { case (width, index) if width > 0 => s"control_$index" }

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

  require(
    option.rxOverride || (routes.forall(_.tie.isEmpty) && lsPools.forall(_.channels.forall(_.tie.isEmpty))),
    "receive ties require rxOverride"
  )
  require(lsPools.forall(_.channels.forall(c => c.tie.isEmpty || c.receive)), "an LS tie requires a receiver")
  val hsTies = routes.flatMap(r => r.tie.map(value => (r.pin, r.slot) -> value)).toMap
  val lsTies = lsPools.flatMap(_.channels.flatMap(c => c.tie.map(c.channel -> _))).toMap

  private val lsSpan = lsChannels.maxOption.fold(BigInt(0))(c => BigInt(c) + 1)
  require(lsSpan.isValidInt, "LS channel span must fit the vector size type")

  val lsChannelCount    = lsSpan.toInt
  val lsPoolByPin       = lsPools.flatMap(pool => pool.pins.map(_ -> pool)).toMap
  val lsPoolByReceiver  = lsPools.flatMap(pool => pool.channels.filter(_.receive).map(_.channel -> pool)).toMap
  val byteBits          = Integer.numberOfTrailingZeros(dataWidth / 8)
  val selectorWidth     = (BigInt(hsSlots) - 1).bitLength.max(1)
  val lsSelectorWidth   = (lsSpan - 1).max(0).bitLength.max(8)
  val lsRxWidth         = (BigInt(pinCount) - 1).bitLength.max(8)
  val verification      = Layer("Verification")
  val roles             = Seq("input_enable", "output_value", "output_enable")
  val irqKinds          = Seq("high", "low", "rise", "fall")
  val interruptCount    = ((BigInt(pinCount) + dataWidth - 1) / dataWidth).toInt
  private val pins      = 0 until pinCount
  private val receivers = lsPoolByReceiver.keys.toSeq.sorted

  private def laneWidth(width:  Int, minimum: Int): Int    = 1 << BigInt(width.max(minimum) - 1).bitLength
  private def arrayBytes(count: Int, lane:    Int): BigInt = ((BigInt(count) * lane + 63) / 64) * 8
  private val fields = ArrayBuffer.empty[(BigInt, RegFieldDefinition)]
  private val blockBuffer = ArrayBuffer.empty[(String, BigInt, BigInt, BigInt)]
  private var cursor = BigInt(0x100)

  private def allocate(name: String, bytes: BigInt, stride: BigInt = 0): BigInt =
    val base = cursor
    blockBuffer += ((name, base, bytes, stride))
    cursor += bytes
    base

  private def place[F <: RegFieldDefinition](bit: BigInt, field: F): F =
    fields += ((bit, field))
    field

  private def bank[F <: RegFieldDefinition](name: String, count: Int, indices: Seq[Int])(field: Int => F): Map[Int, F] =
    val base = allocate(name, arrayBytes(count, 1))
    indices.map(i => i -> place(base * 8 + i, field(i))).toMap

  val identity = Seq(
    (0x00, "version", BigInt(version)),
    (0x04, "type", BigInt(0x494f4d58)),
    (0x08, "pin_count", BigInt(pinCount)),
    (
      0x0c,
      "feature",
      BigInt(
        (if option.gpio then 1 else 0) | (if option.interrupt then 2 else 0) |
          (if option.padControl then 4 else 0) | (if option.invert then 8
                                                  else 0) | (if option.rxOverride then 16
                                                             else 0) | (if lsPools.nonEmpty then 32 else 0)
      )
    ),
    (0x10, "hs_slots", BigInt(hsSlots)),
    (0x14, "ls_channel_count", lsSpan),
    (0x18, "ls_pool_count", BigInt(lsPools.size))
  ).map((offset, name, value) => (BigInt(offset), place(BigInt(offset) * 8, RegField(name, 32).readValue), value))

  // Byte fields let a wide selector retain bytes that a write does not select.
  private def selectorFields(name: String, width: Int, bit: BigInt)
    : Seq[ReadWriteFieldDefinition[ValueReadDefinition, ValueWriteDefinition]] =
    (0 until width by 8).map(i =>
      place(bit + i, RegField(s"${name}_${i / 8}", (width - i).min(8)).readValue.writeValue)
    )

  val selectorLaneWidth   = laneWidth(selectorWidth, 4)
  val lsSelectorLaneWidth = laneWidth(lsSelectorWidth, 8)
  val lsRxLaneWidth       = laneWidth(lsRxWidth, 8)
  val selectorBase        = allocate("hs_select", arrayBytes(pinCount, selectorLaneWidth))
  val selectors           =
    pins.map(p => selectorFields(s"pin_${p}_select", selectorWidth, selectorBase * 8 + BigInt(p) * selectorLaneWidth))

  val gpioInputValue =
    if option.gpio then bank("input_value", pinCount, pins)(p => RegField(s"pin_${p}_input_value", 1).readValue)
    else Map.empty[Int, ReadFieldDefinition[ValueReadDefinition]]
  val gpio           = roles
    .flatMap(name =>
      Option.when(option.gpio)(
        name -> bank(name, pinCount, pins)(p => RegField(s"pin_${p}_$name", 1).readValue.writeValue)
      )
    )
    .toMap
  val rxValues       =
    if option.rxOverride then
      (0 until hsSlots).map(slot =>
        bank(s"rx_value_s$slot", pinCount, pins)(p => RegField(s"pin_${p}_rx_value_s$slot", 1).readValue.writeValue)
      )
    else Seq.empty
  val irqEnables     = irqKinds
    .flatMap(kind =>
      Option.when(option.interrupt)(
        kind -> bank(s"${kind}_int_en", pinCount, pins)(p =>
          RegField(s"pin_${p}_${kind}_int_en", 1).readValue.writeValue
        )
      )
    )
    .toMap
  val irqPending     = irqKinds
    .flatMap(kind =>
      Option.when(option.interrupt)(
        kind -> bank(s"${kind}_int_pend", pinCount, pins)(p =>
          RegField(s"pin_${p}_${kind}_int_pend", 1).readValue.writeReadyValid
        )
      )
    )
    .toMap
  val roleInverts    = roles
    .flatMap(name =>
      Option.when(option.invert)(
        name -> bank(s"${name}_inv", pinCount, pins)(p => RegField(s"pin_${p}_${name}_inv", 1).readValue.writeValue)
      )
    )
    .toMap
  val rxInverts      =
    if option.invert then
      (0 until hsSlots).map(slot =>
        bank(s"rx_inv_s$slot", pinCount, pins)(p => RegField(s"pin_${p}_rx_inv_s$slot", 1).readValue.writeValue)
      )
    else Seq.empty

  val padInverts =
    if option.invert then
      padSourceNames
        .map(name =>
          name -> bank(s"${name}_inv", pinCount, pins)(p => RegField(s"pin_${p}_${name}_inv", 1).readValue.writeValue)
        )
        .toMap
    else Map.empty[String, Map[Int, ReadWriteFieldDefinition[ValueReadDefinition, ValueWriteDefinition]]]

  val lsSelectorBase    = cursor
  private val lsTxBytes = if lsPools.nonEmpty then arrayBytes(pinCount, lsSelectorLaneWidth) else BigInt(0)
  allocate("ls_select", lsTxBytes)
  val lsSelectors       = lsPins.sorted.map(p =>
    p -> selectorFields(s"pin_${p}_ls_select", lsSelectorWidth, lsSelectorBase * 8 + BigInt(p) * lsSelectorLaneWidth)
  )
  val lsRxBase          = allocate("ls_rx_pin", arrayBytes(lsChannelCount, lsRxLaneWidth))
  val lsRxPins          =
    receivers.map(c => c -> selectorFields(s"ls_${c}_rx_pin", lsRxWidth, lsRxBase * 8 + BigInt(c) * lsRxLaneWidth))
  val lsRxSources       =
    if option.rxOverride && lsPools.nonEmpty then
      bank("ls_rx_src", lsChannelCount, receivers)(c => RegField(s"ls_c${c}_rx_src", 1).readValue.writeValue)
    else Map.empty[Int, ReadWriteFieldDefinition[ValueReadDefinition, ValueWriteDefinition]]
  val lsRxValues        =
    if option.rxOverride && lsPools.nonEmpty then
      bank("ls_rx_value", lsChannelCount, receivers)(c => RegField(s"ls_c${c}_rx_value", 1).readValue.writeValue)
    else Map.empty[Int, ReadWriteFieldDefinition[ValueReadDefinition, ValueWriteDefinition]]
  val lsRxInverts       =
    if option.invert && lsPools.nonEmpty then
      bank("ls_rx_inv", lsChannelCount, receivers)(c => RegField(s"ls_c${c}_rx_inv", 1).readValue.writeValue)
    else Map.empty[Int, ReadWriteFieldDefinition[ValueReadDefinition, ValueWriteDefinition]]

  private val sourceShape =
    (if option.gpio then
       Seq(("input_enable_src", BigInt(0), 1), ("output_value_src", BigInt(2), 2), ("output_enable_src", BigInt(4), 2))
     else Seq.empty) ++
      (if option.padControl && hasPull then Seq(("pull_src", BigInt(6), 1)) else Seq.empty) ++
      (if option.rxOverride then (0 until hsSlots).map(s => (s"rx_src_s$s", BigInt(8) + s, 1)) else Seq.empty) ++
      (if option.padControl then
         val start = ((BigInt(8) + (if option.rxOverride then hsSlots else 0)).max(16) + 7) / 8 * 8
         controlWidths.zipWithIndex.collect {
           case (width, index) if width > 0 => (s"control_${index}_src", start + index, 1)
         }
       else Seq.empty)
  val sourceStride        = ((sourceShape.map((_, bit, width) => bit + width).maxOption.getOrElse(BigInt(0)) + 63) / 64) * 8
  val sourceBase          = allocate("pin_src_ctrl", BigInt(pinCount) * sourceStride, sourceStride)
  val sourceControls      = pins.map(p =>
    sourceShape
      .map((name, bit, width) =>
        name -> place(
          (sourceBase + BigInt(p) * sourceStride) * 8 + bit,
          RegField(s"pin_${p}_$name", width).readValue.writeValue
        )
      )
      .toMap
  )
  private val padShape    =
    if option.padControl then
      pad.toSeq.flatMap: model =>
        Seq(
          ("pull_mode", BigInt(0), model.modeWidth),
          ("up_sel", BigInt(4), model.upWidth),
          ("down_sel", BigInt(8), model.downWidth)
        ).filter(_._3 > 0) ++
          controlWidths.zipWithIndex.collect {
            case (width, index) if width > 0 =>
              (s"control_$index", BigInt(16) + BigInt(4) * index, width)
          }
    else Seq.empty
  val padStride           = ((padShape.map((_, bit, width) => bit + width).maxOption.getOrElse(BigInt(0)) + 63) / 64) * 8
  val padBase             = allocate("pin_pad_ctrl", BigInt(pinCount) * padStride, padStride)
  val padControls         = pins.map(p =>
    padShape
      .map((name, bit, width) =>
        name -> place(
          (padBase + BigInt(p) * padStride) * 8 + bit,
          RegField(s"pin_${p}_$name", width).readValue.writeValue
        )
      )
      .toMap
  )
  val windowBytes         = cursor
  val blocks              = blockBuffer.toSeq
  val fieldLayout         = fields.toSeq

  private val words = fieldLayout
    .groupBy(_._1 / dataWidth)
    .toVector
    .sortBy(_._1)
    .map: (word, entries) =>
      var end         = word * dataWidth
      val definitions = entries
        .sortBy(_._1)
        .flatMap: (bit, field) =>
          require(bit >= end, s"field ${field.name} overlaps the preceding field")
          val gap = Option.when(bit > end)(RegField.reserved(s"reserved_${word}_$end", (bit - end).toInt))
          end = bit + field.width
          gap.toSeq :+ field
      RegMapRegister(word * (dataWidth / 8), definitions)
  val regMap        = RegMapDefinition(
    indexWidth = addressWidth - byteBits,
    dataWidth = dataWidth,
    assertionLayer = verification,
    queueEntries = 1,
    reportError = true,
    registers = words
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

class IOMuxPadIO(parameter: IOMuxParameter) extends Record:
  val model = parameter.pad.get
  if model.hasSafe then Flipped("force", Bool())
  parameter.routes.zipWithIndex.foreach:    (route, lane) =>
    if route.pad.pull.on.nonEmpty then Flipped(s"route_${lane}_pull", Bool())
    route.pad.control.foreach: (name, select) =>
      if select.on.nonEmpty then Flipped(s"route_${lane}_control_${model.controlNames.indexOf(name)}", Bool())
  if model.hasPull then Aligned("pullMode", Bits(parameter.pinCount * 4))
  if model.upWidth > 0 then Aligned("upSelect", Bits(parameter.pinCount * 4))
  if model.downWidth > 0 then Aligned("downSelect", Bits(parameter.pinCount * 4))
  model.controlWidths.zipWithIndex.foreach: (width, index) =>
    if width > 0 then Aligned(s"control_$index", Bits(parameter.pinCount * 4))
  model.pinClass.zipWithIndex.foreach:      (classIndex, pin) =>
    val padClass = model.classes(classIndex)
    padClass.pull.foreach(pull => Aligned(s"pin_${pin}_pull", Bits(pull.width)))
    padClass.control.foreach: control =>
      Aligned(s"pin_${pin}_control_${model.controlNames.indexOf(control.name)}", Bits(control.table.width))

class IOMuxIO(parameter: IOMuxParameter) extends HWBundle(parameter):
  val clock           = Flipped(Clock())
  // Reset selects HS slot 0, including the LS pool on member pins.
  val resetN          = Flipped(Reset())
  val req             = Flipped(Decoupled(new IOMuxRequest(parameter.addressWidth, parameter.dataWidth)))
  val rsp             = Aligned(Decoupled(new RegMapResponse(parameter.dataWidth, true)))
  val pad             = Option.when(parameter.pad.nonEmpty)(Aligned(new IOMuxPadIO(parameter)))
  val interrupt       = Option.when(parameter.option.interrupt)(Aligned(Bits(parameter.interruptCount)))
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
      def encode(name: String): String = name.flatMap:
        case c if c.isLetterOrDigit => c.toString
        case c                      => s"_${c.toInt.toHexString}"
      p.blocks.foreach: (name, base, bytes, stride) =>
        Seq("OFFSET" -> base, "BYTES" -> bytes, "STRIDE" -> stride).foreach: (suffix, value) =>
          println(s"#define IOMUX_BLOCK_${encode(name)}_$suffix 0x${value.toString(16)}ULL")
      p.fieldLayout.foreach: (bit, field) =>
        Seq("OFFSET" -> (bit / 8), "BIT" -> (bit % 8), "WIDTH" -> BigInt(field.width)).foreach: (suffix, value) =>
          println(s"#define IOMUX_FIELD_${encode(field.name)}_$suffix 0x${value.toString(16)}ULL")
    case _                         => this.mainImpl(args)

  def architecture(parameter: IOMuxParameter) =
    val io           = summon[Interface[IOMuxIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.asyncActiveLow(io.resetN)

    val padInput = parameter.pad.fold(io.padInputValue: Referable[Bits]): model =>
      model.pinClass.zipWithIndex
        .map((classIndex, pin) => if model.classes(classIndex).hasReceiver then io.padInputValue.bit(pin) else false.B)
        .toVec
        .asBits

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
    val extraAccesses    = ArrayBuffer.empty[AppliedRegAccess]
    def register(field: ReadWriteFieldDefinition[ValueReadDefinition, ValueWriteDefinition], reset: BigInt = 0)
      : Referable[Bits] =
      val value = RegInit(reset.B(field.width))
      extraAccesses ++= Seq(field.read(value), field.write(value))
      value

    def registers[K](fields: Map[K, ReadWriteFieldDefinition[ValueReadDefinition, ValueWriteDefinition]])
      : Map[K, Referable[Bits]] = fields.map((key, field) => key -> register(field))

    val gpio           = parameter.gpio.map((role, fields) => role -> registers(fields))
    val padRegisters   = parameter.padControls.map(registers)
    val padInverts     = parameter.padInverts.map((name, fields) => name -> registers(fields))
    val roleInverts    = parameter.roleInverts.map((role, fields) => role -> registers(fields))
    val rxValues       = parameter.rxValues.zipWithIndex.map: (fields, slot) =>
      fields.map((pin, field) =>
        pin -> register(field, if parameter.hsTies.getOrElse((pin, slot), false) then 1 else 0)
      )
    val rxInverts      = parameter.rxInverts.map(registers)
    val sourceControls = parameter.sourceControls.zipWithIndex.map: (fields, pin) =>
      val tiedSources = parameter.hsTies.keys.collect { case (`pin`, slot) => s"rx_src_s$slot" }.toSet
      fields.map((name, field) => name -> register(field, if tiedSources(name) then 1 else 0))
    val lsRxSources    = parameter.lsRxSources.map((channel, field) =>
      channel -> register(field, if parameter.lsTies.contains(channel) then 1 else 0)
    )
    val lsRxValues     = parameter.lsRxValues.map((channel, field) =>
      channel -> register(field, if parameter.lsTies.getOrElse(channel, false) then 1 else 0)
    )
    val lsRxInverts    = registers(parameter.lsRxInverts)
    val irqEnables     = parameter.irqEnables.map((kind, fields) => kind -> registers(fields))
    val sampled        = Option.when(parameter.option.gpio || parameter.option.interrupt):
      val meta = RegInit(BigInt(0).B(parameter.pinCount))
      val sync = RegInit(BigInt(0).B(parameter.pinCount))
      meta := padInput
      sync := meta
      sync
    parameter.gpioInputValue.foreach: (pin, field) =>
      extraAccesses += field.read(sampled.get.bit(pin).asBits)
    val irqPending     = if parameter.option.interrupt then
      val previous = RegInit(BigInt(0).B(parameter.pinCount))
      previous := sampled.get
      val events = Map(
        "high" -> sampled.get,
        "low"  -> ~sampled.get,
        "rise" -> (sampled.get & ~previous),
        "fall" -> (~sampled.get & previous)
      )
      parameter.irqPending.map: (kind, fields) =>
        kind -> fields.map: (pin, field) =>
          val pending    = RegInit(BigInt(0).B(1))
          val clearValid = Wire(Bool())
          val clearData  = Wire(Bits(1))
          extraAccesses ++= Seq(field.read(pending), field.write(true.B, clearValid, clearData))
          pending := ((pending.asBool & !(clearValid & clearData.asBool)) | events(kind).bit(pin)).asBits
          pin     -> (pending: Referable[Bits])
    else Map.empty[String, Map[Int, Referable[Bits]]]
    io.interrupt.foreach: interrupt =>
      interrupt := (0 until parameter.pinCount)
        .grouped(parameter.dataWidth)
        .map: pins =>
          pins
            .flatMap(pin => parameter.irqKinds.map(kind => irqPending(kind)(pin).asBool & irqEnables(kind)(pin).asBool))
            .reduce(_ | _)
        .toSeq
        .toVec
        .asBits

    val accesses = parameter.identity.map((_, field, value) => field.read(value.B(32))) ++
      hsRegisters.flatMap(_._2) ++ (lsRegisters ++ lsRxRegisters).flatMap(_._2._2) ++ extraAccesses
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

    val slotValues = Map(
      "input_enable"  -> (io.inputEnable, io.lsInputEnable),
      "output_value"  -> (io.outputValue, io.lsOutputValue),
      "output_enable" -> (io.outputEnable, io.lsOutputEnable)
    ).map((role, inputs) => role -> (0 until parameter.pinCount).map(p => select(p, inputs._1, inputs._2)))
    def transmit(role: String): Referable[Bits] =
      (0 until parameter.pinCount)
        .map: pin =>
          val slot     = slotValues(role)(pin)
          val sourced  = if parameter.option.gpio then
            val source  = sourceControls(pin)(s"${role}_src")
            val choices = role match
              case "input_enable"  => Seq(slot, gpio(role)(pin).asBool)
              case "output_value"  =>
                Seq(slot, gpio(role)(pin).asBool, slotValues("input_enable")(pin), slotValues("output_enable")(pin))
              case "output_enable" => Seq(slot, gpio(role)(pin).asBool, slotValues("output_value")(pin), false.B)
            choices.zipWithIndex.foldLeft(false.B: Referable[Bool]): (result, choice) =>
              (source === BigInt(choice._2).B(parameter.sourceControls(pin)(s"${role}_src").width)) ? (
                choice._1,
                result
              )
          else slot
          val inverted = if parameter.option.invert then sourced ^ roleInverts(role)(pin).asBool else sourced
          parameter.pad
            .filter(_.hasSafe)
            .fold(inverted): model =>
              val safe  = model.classes(model.pinClass(pin)).safe.get
              val value = role match
                case "input_enable"  => safe.inputEnable
                case "output_value"  => safe.outputValue
                case "output_enable" => safe.outputEnable
              io.pad.get.field[Bool]("force") ? (value.B, inverted)
        .toVec
        .asBits
    io.padInputEnable := transmit("input_enable")
    io.padOutputValue  := transmit("output_value")
    io.padOutputEnable := transmit("output_enable")
    val received = parameter.routes.map: route =>
      val raw     = padInput.bit(route.pin)
      val sourced =
        if parameter.option.rxOverride then
          sourceControls(route.pin)(s"rx_src_s${route.slot}").asBool ? (rxValues(route.slot)(route.pin).asBool, raw)
        else raw
      if parameter.option.invert then sourced ^ rxInverts(route.slot)(route.pin).asBool else sourced
    io.inputValue := received.reverse.map(_.asBits: Referable[Bits]).reduceOption(_ ## _).getOrElse(BigInt(0).B(0))
    io.lsInputValue.foreach: value =>
      value := (0 until parameter.lsChannelCount)
        .map: channel =>
          parameter.lsPoolByReceiver
            .get(channel)
            .fold(false.B: Referable[Bool]): pool =>
              val raw     = pool.pins.foldLeft(false.B: Referable[Bool]): (result, pin) =>
                (lsRxPinByChannel(channel) === BigInt(pin).B(parameter.lsRxWidth)) ? (padInput.bit(pin), result)
              val sourced =
                if parameter.option.rxOverride then lsRxSources(channel).asBool ? (lsRxValues(channel).asBool, raw)
                else raw
              if parameter.option.invert then sourced ^ lsRxInverts(channel).asBool else sourced
        .toVec
        .asBits

    parameter.pad.foreach: model =>
      val padIo = io.pad.get
      def tableValue(table: IOMuxPadTable, code: Referable[Bits]): Referable[Bits] =
        table.rows.zipWithIndex.foldLeft(table.rows(table.default).value.B(table.width): Referable[Bits]):
          (result, row) => (code === BigInt(row._2).B(4)) ? (row._1.value.B(table.width), result)

      val codes = (0 until parameter.pinCount).map: pin =>
        val classIndex = model.pinClass(pin)
        val padClass   = model.classes(classIndex)
        def code(
          group:   String,
          field:   String,
          width:   Int,
          default: Int,
          safe:    Int,
          present: Boolean
        )(request: IOMuxPadRoute => IOMuxPadSelect[Int]
        ): Referable[Bits] =
          if !present then BigInt(0).B(4)
          else
            val routed  = parameter.routes.zipWithIndex
              .filter(_._1.pin == pin)
              .foldLeft(BigInt(default).B(width): Referable[Bits]): (result, item) =>
                val (route, lane) = item
                val select        = request(route.pad)
                val value         = select.on.fold(BigInt(select.off).B(width): Referable[Bits]): on =>
                  val linked   = padIo.field[Bool](s"route_${lane}_$group") ^ select.invert.B
                  val inverted = if parameter.option.invert then linked ^ padInverts(group)(pin).asBool else linked
                  inverted ? (BigInt(on).B(width), BigInt(select.off).B(width))
                (selectors(pin) === BigInt(route.slot).B(parameter.selectorWidth)) ? (value, result)
            val sourced =
              if parameter.option.padControl then
                sourceControls(pin)(s"${group}_src").asBool ? (padRegisters(pin)(field), routed)
              else routed
            val forced  =
              if model.hasSafe then padIo.field[Bool]("force") ? (BigInt(safe).B(width), sourced) else sourced
            if width < 4 then BigInt(0).B(4 - width) ## forced else forced

        val safePull     = padClass.safe.fold((0, 0, 0))(s => model.pullCodes(classIndex, s.pull))
        val pullFields   = Seq(
          ("pull_mode", model.modeWidth, 0, safePull._1),
          ("up_sel", model.upWidth, 1, safePull._2),
          ("down_sel", model.downWidth, 2, safePull._3)
        )
        val pullCodes    = pullFields
          .filter(_._2 > 0)
          .map: (field, width, component, safe) =>
            field -> code("pull", field, width, 0, safe, padClass.pull.nonEmpty): route =>
              def encode(request: IOMuxPullRequest): Int =
                val values = model.pullCodes(classIndex, request)
                Seq(values._1, values._2, values._3)(component)
              IOMuxPadSelect(encode(route.pull.off), route.pull.on.map(encode), route.pull.invert)
        val controlCodes = model.controlNames.zipWithIndex
          .filter((_, index) => model.controlWidths(index) > 0)
          .map: (name, index) =>
            val table   = model.controlTable(classIndex, name)
            val default = table.map(_.default).getOrElse(0)
            val safe    =
              padClass.safe.fold(default)(s => model.controlCode(classIndex, name, s.control.getOrElse(name, "")))
            val group   = s"control_$index"
            group -> code(group, group, model.controlWidths(index), default, safe, table.nonEmpty): route =>
              val select = route.control.getOrElse(name, IOMuxPadSelect(""))
              IOMuxPadSelect(
                model.controlCode(classIndex, name, select.off),
                select.on.map(row => model.controlCode(classIndex, name, row)),
                select.invert
              )
        (pullCodes ++ controlCodes).toMap

      def codeBus(field: String): Referable[Bits] = codes.reverse.map(_(field)).reduce(_ ## _)
      if model.hasPull then padIo.field[Bits]("pullMode") := codeBus("pull_mode")
      if model.upWidth > 0 then padIo.field[Bits]("upSelect") := codeBus("up_sel")
      if model.downWidth > 0 then padIo.field[Bits]("downSelect") := codeBus("down_sel")
      model.controlWidths.zipWithIndex.foreach: (width, index) =>
        if width > 0 then padIo.field[Bits](s"control_$index") := codeBus(s"control_$index")

      model.pinClass.zipWithIndex.foreach: (classIndex, pin) =>
        val padClass = model.classes(classIndex)
        padClass.pull.foreach:    pull =>
          def direction(name: String): Referable[Bits] =
            val selector = codes(pin).getOrElse(s"${name}_sel", BigInt(0).B(4))
            tableValue(pull.tables(name), selector)
          val none = pull.tables("none").rows.head.value.B(pull.width)
          val mode   = codes(pin)("pull_mode")
          val native = pull.tables.toSeq.foldLeft(none: Referable[Bits]): (result, item) =>
            val (name, table) = item
            val value         = if name == "up" || name == "down" then direction(name) else table.rows.head.value.B(pull.width)
            (mode === BigInt(model.modeNames.indexOf(name)).B(4)) ? (value, result)
          val value  = if model.weaves(classIndex) then
            val keeper     = padInput.bit(pin) ? (direction("up"), direction("down"))
            val oscillator = padInput.bit(pin) ? (direction("down"), direction("up"))
            (mode === BigInt(3).B(4)) ? (keeper, (mode === BigInt(4).B(4)) ? (oscillator, native))
          else native
          padIo.field[Bits](s"pin_${pin}_pull") := value
        padClass.control.foreach: control =>
          val index = model.controlNames.indexOf(control.name)
          val value =
            if control.table.rows.size == 1 then control.table.rows.head.value.B(control.table.width)
            else tableValue(control.table, codes(pin)(s"control_$index"))
          padIo.field[Bits](s"pin_${pin}_control_$index") := value

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
      if parameter.option.gpio then
        parameter.roles.foreach: role =>
          val width = parameter.sourceControls.head(s"${role}_src").width
          (1 until (1 << width)).foreach: code =>
            val selected = sourceControls.map(_(s"${role}_src") === BigInt(code).B(width)).reduce(_ | _)
            Cover(selected.S, io.resetN.asBool, s"iomux_${role}_source_$code")
      if parameter.option.interrupt then
        parameter.irqKinds.foreach: kind =>
          val masked = (0 until parameter.pinCount)
            .map(pin => irqPending(kind)(pin).asBool & !irqEnables(kind)(pin).asBool)
            .reduce(_ | _)
          Cover(masked.S, io.resetN.asBool, s"iomux_${kind}_pending_disabled")
        Cover(io.interrupt.get.orR.S, io.resetN.asBool, "iomux_interrupt_delivery")
      if parameter.option.invert then
        parameter.roles.foreach: role =>
          Cover(
            roleInverts(role).values.map(value => value.asBool: Referable[Bool]).reduce(_ | _).S,
            io.resetN.asBool,
            s"iomux_${role}_inversion"
          )
      if parameter.option.rxOverride then
        parameter.routes.foreach: route =>
          Cover(
            sourceControls(route.pin)(s"rx_src_s${route.slot}").asBool.S,
            io.resetN.asBool,
            s"iomux_hs_pin_${route.pin}_slot_${route.slot}_override"
          )
        lsRxSources.foreach:      (channel, source) =>
          Cover(source.asBool.S, io.resetN.asBool, s"iomux_ls_channel_${channel}_override")
      parameter.pad.foreach:                  model =>
        if model.hasSafe then Cover(io.pad.get.field[Bool]("force").S, io.resetN.asBool, "iomux_pad_force")
        if parameter.option.padControl then
          parameter.padSourceNames.foreach: group =>
            val takeover = sourceControls.map(_(s"${group}_src").asBool: Referable[Bool]).reduce(_ | _)
            Cover(takeover.S, io.resetN.asBool, s"iomux_pad_${group}_register")
        parameter.routes.zipWithIndex.foreach: (route, lane) =>
          val linked = Option.when(route.pad.pull.on.nonEmpty)("pull").toSeq ++
            route.pad.control.toSeq.collect {
              case (name, select) if select.on.nonEmpty =>
                s"control_${model.controlNames.indexOf(name)}"
            }
          linked.foreach: group =>
            val active = selectors(route.pin) === BigInt(route.slot).B(parameter.selectorWidth)
            Cover(
              (active & io.pad.get.field[Bool](s"route_${lane}_$group")).S,
              io.resetN.asBool,
              s"iomux_pad_route_${lane}_${group}_input_high"
            )
      Cover((io.req.fire & !aligned & inWindow).S, io.resetN.asBool, "iomux_unaligned_access")
