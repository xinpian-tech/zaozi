// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.stdlib.mmio

import me.jiuyang.stdlib.*
import me.jiuyang.stdlib.queue.*
import me.jiuyang.stdlib.queue.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.dialect.firrtl.operation.{RegResetPolarity, RegResetType}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

final case class RegMapRegister(byteOffset: BigInt, fields: Seq[RegFieldDefinition])

final case class RegMapDefinition(
  indexWidth:     Int,
  dataWidth:      Int,
  queueEntries:   Int,
  reportError:    Boolean,
  assertionLayer: Layer,
  registers: Seq[RegMapRegister]):
  require(indexWidth > 0, s"indexWidth must be positive, not $indexWidth")
  require(dataWidth > 0 && dataWidth % 8 == 0, s"dataWidth must be a positive multiple of 8, not $dataWidth")
  require(queueEntries >= 0, s"queueEntries must not be negative, not $queueEntries")
  private case class FieldRange(definition: RegFieldDefinition, low: BigInt, high: BigInt):
    val word = low / dataWidth

  private val ranges = registers.flatMap: register =>
    require(register.byteOffset >= 0, s"register byte offset ${register.byteOffset} must not be negative")
    var bitOffset = register.byteOffset * 8
    register.fields.map: field =>
      val range = FieldRange(field, bitOffset, bitOffset + field.width)
      bitOffset = range.high
      range

  require(ranges.nonEmpty, "RegMap must contain at least one field mapping")

  private val sortedRanges = ranges.sortBy(_.low)
  sortedRanges
    .zip(sortedRanges.drop(1))
    .foreach:     (left, right) =>
      require(left.high <= right.low, s"register map overlaps at bit ${right.low}")
  ranges.foreach: range =>
    require(range.low / dataWidth == (range.high - 1) / dataWidth, s"field ${range.definition.name} crosses a word")
    require(range.word.bitLength <= indexWidth, s"field ${range.definition.name} index does not fit")

  private val definitions   = registers.flatMap(_.fields)
  private val requiresQueue =
    definitions.collect { case field: RegReadDefinition => field }.exists(_.read.isInstanceOf[RequestResponseRead]) ||
      definitions.collect { case field: RegWriteDefinition => field }
        .exists(_.write.isInstanceOf[RequestResponseWrite])
  require(!requiresQueue || queueEntries > 0, "request-response fields require a non-zero queueEntries")
  require(
    definitions.indices.forall(i => definitions.indices.forall(j => i == j || !(definitions(i) eq definitions(j)))),
    "a field definition must not appear more than once"
  )
  require(definitions.map(_.name).distinct.size == definitions.size, "register field names must be distinct")

  def apply(
    req:      Referable[DecoupledIO[RegMapRequest]] & Writable[DecoupledIO[RegMapRequest]],
    rsp:      Referable[DecoupledIO[RegMapResponse]] & Writable[DecoupledIO[RegMapResponse]]
  )(accesses: AppliedRegAccess*
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext,
    ClockScope,
    ResetScope,
    Seq[LayerTree]
  ): Unit =
    case class Layout(definition: RegFieldDefinition, wordIndex: BigInt, bitOffset: Int)
    case class FieldCircuit(
      layout:           Layout,
      readInputValid:   Wire[Bool],
      readOutputReady:  Wire[Bool],
      readInputReady:   Referable[Bool],
      readOutputValid:  Referable[Bool],
      readFrontData:    Referable[Bits],
      readBackData:     Referable[Bits],
      writeInputValid:  Wire[Bool],
      writeOutputReady: Wire[Bool],
      writeInputReady:  Referable[Bool],
      writeOutputValid: Referable[Bool])

    require(req.bits.getType.indexWidth == indexWidth, "request index width does not match RegMap")
    require(req.bits.getType.dataWidth == dataWidth, "request data width does not match RegMap")
    require(rsp.bits.getType.dataWidth == dataWidth, "response data width does not match RegMap")
    require(rsp.bits.getType.reportError == reportError, "response error shape does not match RegMap")

    val readDefinitions  = definitions.collect { case field: RegReadDefinition => field }
    val writeDefinitions = definitions.collect { case field: RegWriteDefinition => field }
    val appliedReads     = accesses.collect { case access: AppliedRegReadAccess => access }
    val appliedWrites    = accesses.collect { case access: AppliedRegWriteAccess => access }

    require(
      accesses.size == readDefinitions.size + writeDefinitions.size,
      "every register read and write capability must be applied exactly once"
    )
    readDefinitions.foreach:  field =>
      require(
        appliedReads.count(applied => applied.definition eq field.read) == 1,
        s"field ${field.name} read capability must be applied exactly once"
      )
    writeDefinitions.foreach: field =>
      require(
        appliedWrites.count(applied => applied.definition eq field.write) == 1,
        s"field ${field.name} write capability must be applied exactly once"
      )
    appliedReads.foreach:     applied =>
      require(
        readDefinitions.exists(field => field.read eq applied.definition),
        "applied read capability is not part of this RegMap"
      )
    appliedWrites.foreach:    applied =>
      require(
        writeDefinitions.exists(field => field.write eq applied.definition),
        "applied write capability is not part of this RegMap"
      )

    val flatLayouts = registers.flatMap: register =>
      var absoluteBit = register.byteOffset * 8
      register.fields.map: field =>
        val layout = Layout(field, absoluteBit / dataWidth, (absoluteBit % dataWidth).toInt)
        absoluteBit += field.width
        layout
    val layouts     = flatLayouts.groupBy(_.wordIndex).toSeq.sortBy(_._1)

    // Read data is sampled when the request is accepted and travels with the queue entry.
    val frontReadWord = Wire(Bits(dataWidth))

    val queuedRequestWidth = 1 + indexWidth + dataWidth + dataWidth / 8
    val queue              = Option.when(queueEntries > 0):
      val resetScope = summon[ResetScope]
      val instance   = SyncQueue(
        SyncQueueParameter(
          width = queuedRequestWidth,
          depth = queueEntries,
          almostEmptyLevel = 1,
          almostFullLevel = 1,
          stickyError = false,
          enableDiagnostics = false,
          asyncReset = resetScope.resetType == RegResetType.AsyncReset,
          resetMem = false
        )
      )
      instance.clock       := summon[ClockScope].clock
      instance.resetN      := (
        if resetScope.resetPolarity == RegResetPolarity.PosReset then (!resetScope.reset.asBool).asReset
        else resetScope.reset
      )
      instance.diagnosticN := true.B
      instance.dataIn      := (req.bits.read.asBits ## req.bits.index.asBits ## req.bits.mask ## frontReadWord).asUInt
      instance

    val queuedMaskLow   = dataWidth
    val queuedMaskHigh  = queuedMaskLow + dataWidth / 8 - 1
    val queuedIndexLow  = queuedMaskHigh + 1
    val queuedIndexHigh = queuedIndexLow + indexWidth - 1
    val frontReady      = Wire(Bool())
    val backValid       = Wire(Bool())
    val backRead:     Referable[Bool] =
      queue.map(_.dataOut.asBits.bit(queuedRequestWidth - 1): Referable[Bool]).getOrElse(req.bits.read)
    val backIndex:    Referable[UInt] =
      queue
        .map(_.dataOut.asBits.bits(queuedIndexHigh, queuedIndexLow).asUInt: Referable[UInt])
        .getOrElse(req.bits.index)
    val backReadWord: Referable[Bits] =
      queue.map(_.dataOut.asBits.bits(dataWidth - 1, 0): Referable[Bits]).getOrElse(frontReadWord)
    val backMask:     Referable[Bits] =
      queue.map(_.dataOut.asBits.bits(queuedMaskHigh, queuedMaskLow): Referable[Bits]).getOrElse(req.bits.mask)

    def reduceOthers(
      values: Seq[Referable[Bool]]
    ): (Seq[Referable[Bool]], Referable[Bool]) =
      if values.size <= 1 then (Seq.fill(values.size)(true.B), values.headOption.getOrElse(true.B))
      else if values.size <= 3 then
        (
          values.indices.map: index =>
            values.take(index).appendedAll(values.drop(index + 1)).reduce(_ & _),
          values.reduce(_ & _)
        )
      else
        val (groupOthers, all) = reduceOthers(values.grouped(2).map(_.reduce(_ & _)).toSeq)
        val others             = values.indices.map: index =>
          val siblingIndex = index ^ 1
          if siblingIndex < values.size then values(siblingIndex) & groupOthers(index / 2)
          else groupOthers(index / 2)
        (others, all)

    def readMask(
      layout: Layout,
      mask:   Referable[Bits]
    )(
      using Block
    ): Referable[Bool] =
      val firstLane = layout.bitOffset / 8
      val lastLane  = (layout.bitOffset + layout.definition.width - 1) / 8
      (firstLane to lastLane).map(mask.bit).reduce(_ | _)

    def writeMask(
      layout: Layout,
      mask:   Referable[Bits]
    )(
      using Block
    ): Referable[Bool] =
      val firstLane = layout.bitOffset / 8
      val lastLane  = (layout.bitOffset + layout.definition.width - 1) / 8
      (firstLane to lastLane).map(mask.bit).reduce(_ & _)

    val circuits = layouts.map: (wordIndex, wordLayouts) =>
      val fields = wordLayouts.map: layout =>
        val readInputValid                                                 = Wire(Bool())
        val readOutputReady                                                = Wire(Bool())
        val writeInputValid                                                = Wire(Bool())
        val writeOutputReady                                               = Wire(Bool())
        val high                                                           = layout.bitOffset + layout.definition.width - 1
        val frontData                                                      = req.bits.data.bits(high, layout.bitOffset)
        val heldReadData                                                   = backReadWord.bits(high, layout.bitOffset)
        val zeroData                                                       = BigInt(0).B(layout.definition.width)
        val readAccess                                                     = layout.definition match
          case definition: RegReadDefinition =>
            appliedReads.find(applied => applied.definition eq definition.read)
          case _ => None
        val readSignals: (Referable[Bool], Referable[Bool], Referable[Bits], Referable[Bits]) = readAccess match
          case Some(AppliedValueRead(_, data))                    =>
            require(
              data.width == layout.definition.width,
              s"field ${layout.definition.name} read width does not match"
            )
            (true.B, true.B, data, heldReadData)
          case Some(AppliedReadyValidRead(_, ready, valid, data)) =>
            require(
              data.width == layout.definition.width,
              s"field ${layout.definition.name} read width does not match"
            )
            ready := readInputValid
            (valid, true.B, data, heldReadData)
          case Some(
                AppliedRequestResponseRead(_, requestReady, requestValid, responseReady, responseValid, data)
              ) =>
            require(
              data.width == layout.definition.width,
              s"field ${layout.definition.name} read width does not match"
            )
            requestValid  := readInputValid
            responseReady := readOutputReady
            (requestReady, responseValid, zeroData, data)
          case None                                               =>
            (true.B, true.B, zeroData, zeroData)
        val (readInputReady, readOutputValid, readFrontData, readBackData) = readSignals

        val writeAccess                         = layout.definition match
          case definition: RegWriteDefinition =>
            appliedWrites.find(applied => applied.definition eq definition.write)
          case _ => None
        val writeSignals: (Referable[Bool], Referable[Bool]) = writeAccess match
          case Some(AppliedValueWrite(_, data))                    =>
            require(
              data.width == layout.definition.width,
              s"field ${layout.definition.name} write width does not match"
            )
            when(writeInputValid):
              data := frontData
            (true.B, true.B)
          case Some(AppliedReadyValidWrite(_, ready, valid, data)) =>
            require(
              data.width == layout.definition.width,
              s"field ${layout.definition.name} write width does not match"
            )
            valid := writeInputValid
            data  := frontData
            (ready, true.B)
          case Some(
                AppliedRequestResponseWrite(
                  _,
                  requestReady,
                  requestValid,
                  requestData,
                  responseReady,
                  responseValid
                )
              ) =>
            require(
              requestData.width == layout.definition.width,
              s"field ${layout.definition.name} write width does not match"
            )
            requestValid  := writeInputValid
            requestData   := frontData
            responseReady := writeOutputReady
            (requestReady, responseValid)
          case None                                                =>
            (true.B, true.B)
        val (writeInputReady, writeOutputValid) = writeSignals
        FieldCircuit(
          layout,
          readInputValid,
          readOutputReady,
          readInputReady,
          readOutputValid,
          readFrontData,
          readBackData,
          writeInputValid,
          writeOutputReady,
          writeInputReady,
          writeOutputValid
        )
      wordIndex -> fields

    def inputHit(
      index:     Referable[UInt],
      wordIndex: BigInt
    )(
      using Block
    ): Referable[Bool] =
      index === wordIndex.U(indexWidth)

    val flows = circuits.map: (wordIndex, fields) =>
      val readInputMasks   = fields.map(field => readMask(field.layout, req.bits.mask))
      val writeInputMasks  = fields.map(field => writeMask(field.layout, req.bits.mask))
      val readOutputMasks  = fields.map(field => readMask(field.layout, backMask))
      val writeOutputMasks = fields.map(field => writeMask(field.layout, backMask))

      val (otherReadInputReady, readInputReady)     = reduceOthers(
        fields.zip(readInputMasks).map((field, mask) => mask ? (field.readInputReady, true.B))
      )
      val (otherWriteInputReady, writeInputReady)   = reduceOthers(
        fields.zip(writeInputMasks).map((field, mask) => mask ? (field.writeInputReady, true.B))
      )
      val (otherReadOutputValid, readOutputValid)   = reduceOthers(
        fields.zip(readOutputMasks).map((field, mask) => mask ? (field.readOutputValid, true.B))
      )
      val (otherWriteOutputValid, writeOutputValid) = reduceOthers(
        fields.zip(writeOutputMasks).map((field, mask) => mask ? (field.writeOutputValid, true.B))
      )

      fields.indices.foreach: index =>
        val field = fields(index)
        field.readInputValid   :=
          req.valid & frontReady & inputHit(req.bits.index, wordIndex) & req.bits.read & readInputMasks(
            index
          ) & otherReadInputReady(index)
        field.writeInputValid  :=
          req.valid & frontReady & inputHit(req.bits.index, wordIndex) & !req.bits.read & writeInputMasks(
            index
          ) & otherWriteInputReady(index)
        field.readOutputReady  :=
          backValid & inputHit(backIndex, wordIndex) & backRead & readOutputMasks(index) & rsp.ready &
            otherReadOutputValid(index)
        field.writeOutputReady :=
          backValid & inputHit(backIndex, wordIndex) & !backRead & writeOutputMasks(index) & rsp.ready &
            otherWriteOutputValid(index)

      (wordIndex, readInputReady, writeInputReady, readOutputValid, writeOutputValid)

    val selectedInputReady = flows.foldLeft(true.B: Referable[Bool]):
      case (default, (wordIndex, readReady, writeReady, _, _)) =>
        inputHit(req.bits.index, wordIndex) ? (req.bits.read ? (readReady, writeReady), default)

    val selectedOutputValid = flows.foldLeft(true.B: Referable[Bool]):
      case (default, (wordIndex, _, _, readValid, writeValid)) =>
        inputHit(backIndex, wordIndex) ? (backRead ? (readValid, writeValid), default)

    queue match
      case Some(instance) =>
        frontReady            := !instance.full
        backValid             := !instance.empty
        instance.pushRequestN := !(req.valid & frontReady & selectedInputReady)
        instance.popRequestN  := !(rsp.ready & backValid & selectedOutputValid)
      case None           =>
        frontReady := rsp.ready & selectedOutputValid
        backValid  := req.valid & selectedInputReady

    val requestMapped = circuits
      .map((wordIndex, _) => inputHit(backIndex, wordIndex))
      .reduceOption(_ | _)
      .getOrElse(false.B)
    req.ready     := frontReady & selectedInputReady
    rsp.valid     := backValid & selectedOutputValid
    rsp.bits.read := backRead
    rsp.bits.error.foreach(_ := !requestMapped)

    def assembleWord(fields: Seq[FieldCircuit], data: FieldCircuit => Referable[Bits]): Referable[Bits] =
      val segments               = fields
        .sortBy(_.layout.bitOffset)
        .map(field => field.layout.bitOffset -> data(field))
      val withPadding            = segments.foldLeft((0, Seq.empty[Referable[Bits]])):
        case ((nextBit, result), (bitOffset, data)) =>
          val gap = Option.when(bitOffset > nextBit)(BigInt(0).U(bitOffset - nextBit).asBits).toSeq
          (bitOffset + data.width, result ++ gap :+ data)
      val (usedWidth, lowToHigh) = withPadding
      val highPadding            = Option
        .when(usedWidth < dataWidth)(BigInt(0).U(dataWidth - usedWidth).asBits)
        .toSeq
      (highPadding ++ lowToHigh.reverse).reduce(_ ## _)

    frontReadWord := circuits.foldLeft(BigInt(0).U(dataWidth).asBits: Referable[Bits]):
      case (default, (wordIndex, fields)) =>
        inputHit(req.bits.index, wordIndex) ? (assembleWord(fields, _.readFrontData), default)

    rsp.bits.data := circuits.foldLeft(BigInt(0).U(dataWidth).asBits: Referable[Bits]):
      case (default, (wordIndex, fields)) =>
        inputHit(backIndex, wordIndex) ? (assembleWord(fields, _.readBackData), default)

    layer(assertionLayer.name):
      val requestHeld      = RegInit(false.B)
      val heldRequestRead  = Reg(Bool())
      val heldRequestIndex = Reg(UInt(indexWidth))
      val heldRequestData  = Reg(Bits(dataWidth))
      val heldRequestMask  = Reg(Bits(dataWidth / 8))

      val beginRequestHold = req.valid & !req.ready & !requestHeld
      requestHeld      := req.ready ? (false.B, beginRequestHold ? (true.B, requestHeld))
      heldRequestRead  := beginRequestHold ? (req.bits.read, heldRequestRead)
      heldRequestIndex := beginRequestHold ? (req.bits.index, heldRequestIndex)
      heldRequestData  := beginRequestHold ? (req.bits.data, heldRequestData)
      heldRequestMask  := beginRequestHold ? (req.bits.mask, heldRequestMask)

      Assert(req.valid.I, requestHeld, "regmap_request_valid_held")
      Assert((req.bits.read === heldRequestRead).I, requestHeld, "regmap_request_read_stable")
      Assert((req.bits.index === heldRequestIndex).I, requestHeld, "regmap_request_index_stable")
      Assert((req.bits.data === heldRequestData).I, requestHeld, "regmap_request_data_stable")
      Assert((req.bits.mask === heldRequestMask).I, requestHeld, "regmap_request_mask_stable")

      val responseHeld      = RegInit(false.B)
      val heldResponseRead  = Reg(Bool())
      val heldResponseData  = Reg(Bits(dataWidth))
      val heldResponseError = rsp.bits.error.map(_ => Reg(Bool()))

      val beginResponseHold = rsp.valid & !rsp.ready & !responseHeld
      responseHeld     := rsp.ready ? (false.B, beginResponseHold ? (true.B, responseHeld))
      heldResponseRead := beginResponseHold ? (rsp.bits.read, heldResponseRead)
      heldResponseData := beginResponseHold ? (rsp.bits.data, heldResponseData)
      heldResponseError
        .zip(rsp.bits.error)
        .foreach: (held, error) =>
          held := beginResponseHold ? (error, held)

      Assert(rsp.valid.I, responseHeld, "regmap_response_valid_held")
      Assert((rsp.bits.read === heldResponseRead).I, responseHeld, "regmap_response_read_stable")
      Assert((rsp.bits.data === heldResponseData).I, responseHeld, "regmap_response_data_stable")
      heldResponseError
        .zip(rsp.bits.error)
        .foreach: (held, error) =>
          Assert((error === held).I, responseHeld, "regmap_response_error_stable")

      val resetScope   = summon[ResetScope]
      val active       =
        if resetScope.resetPolarity == RegResetPolarity.PosReset then !resetScope.reset.asBool
        else resetScope.reset.asBool
      val requestFire  = req.valid & req.ready
      val responseFire = rsp.valid & rsp.ready

      Cover((requestFire & req.bits.read).I, active, "regmap_read_accept")
      Cover((requestFire & !req.bits.read).I, active, "regmap_write_accept")
      Cover((req.valid & !req.ready).I, active, "regmap_request_stall")
      Cover((rsp.valid & !rsp.ready).I, active, "regmap_response_stall")
      if queueEntries > 0 then Cover((requestFire & rsp.valid).I, active, "regmap_accept_with_response_pending")
      rsp.bits.error.foreach(error => Cover((responseFire & error).I, active, "regmap_error_response"))

      circuits.foreach: (wordIndex, fields) =>
        fields.foreach: field =>
          val name = field.layout.definition.name
          field.layout.definition match
            case definition: RegReadDefinition =>
              Cover((field.readInputValid & field.readInputReady).I, active, s"regmap_${name}_read")
              if definition.read.isInstanceOf[RequestResponseRead] then
                Cover((field.readOutputReady & field.readOutputValid).I, active, s"regmap_${name}_read_response")
            case _ => ()
          field.layout.definition match
            case definition: RegWriteDefinition =>
              Cover((field.writeInputValid & field.writeInputReady).I, active, s"regmap_${name}_write")
              if definition.write.isInstanceOf[RequestResponseWrite] then
                Cover((field.writeOutputReady & field.writeOutputValid).I, active, s"regmap_${name}_write_response")
              Cover(
                (requestFire & !req.bits.read & inputHit(req.bits.index, wordIndex) & !writeMask(
                  field.layout,
                  req.bits.mask
                )).I,
                active,
                s"regmap_${name}_write_masked_out"
              )
            case _ => ()

object RegMapDefinition:
  type Map = (Int, Seq[RegFieldDefinition])

  def apply(
    indexWidth:     Int,
    dataWidth:      Int,
    assertionLayer: Layer,
    queueEntries:   Int = 0,
    reportError:    Boolean = false
  )(mapping:        Map*
  ): RegMapDefinition =
    RegMapDefinition(
      indexWidth,
      dataWidth,
      queueEntries,
      reportError,
      assertionLayer,
      mapping.map((offset, fields) => RegMapRegister(BigInt(offset), fields))
    )
