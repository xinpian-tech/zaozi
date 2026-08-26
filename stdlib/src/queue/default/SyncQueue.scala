// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.queue.default

import java.lang.foreign.Arena

import me.jiuyang.stdlib.*
import me.jiuyang.stdlib.default.{*, given}
import me.jiuyang.stdlib.queue.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Operation-oriented implementation of `DW_fifo_s1_sf`.
  *
  * The controller is elaborated in this generator. [[Ram]] is the only queue child module.
  */
case class SyncQueueParameter(
  width:             Int,
  depth:             Int,
  almostEmptyLevel:  Int,
  almostFullLevel:   Int,
  stickyError:       Boolean,
  enableDiagnostics: Boolean,
  asyncReset:        Boolean,
  resetMem:          Boolean)
    extends Parameter:
  require(width >= 1 && width <= 2048, s"SyncQueue width must be 1..2048, got $width")
  require(depth >= 2 && depth <= 1024, s"SyncQueue depth must be 2..1024, got $depth")
  require(
    almostEmptyLevel >= 1 && almostEmptyLevel <= depth - 1,
    s"SyncQueue almostEmptyLevel must be 1..depth-1, got $almostEmptyLevel"
  )
  require(
    almostFullLevel >= 1 && almostFullLevel <= depth - 1,
    s"SyncQueue almostFullLevel must be 1..depth-1, got $almostFullLevel"
  )
  require(stickyError || !enableDiagnostics, "SyncQueue diagnostics require stickyError=true")
  def addressWidth: Int = QueueHelper.bitWidth(depth)

given upickle.default.ReadWriter[SyncQueueParameter] = upickle.default.macroRW

class SyncQueueLayers(parameter: SyncQueueParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class SyncQueueIO(parameter: SyncQueueParameter) extends HWBundle(parameter):
  val clock        = Flipped(Clock())
  val resetN       = Flipped(Reset())
  val pushRequestN = Flipped(Bool())
  val popRequestN  = Flipped(Bool())
  val diagnosticN  = Flipped(Bool())
  val dataIn       = Flipped(UInt(parameter.width))
  val empty        = Aligned(Bool())
  val almostEmpty  = Aligned(Bool())
  val halfFull     = Aligned(Bool())
  val almostFull   = Aligned(Bool())
  val full         = Aligned(Bool())
  val error        = Aligned(Bool())
  val dataOut      = Aligned(UInt(parameter.width))

class SyncQueueProbe(parameter: SyncQueueParameter) extends DVBundle[SyncQueueParameter, SyncQueueLayers](parameter)

@generator
object SyncQueue extends Generator[SyncQueueParameter, SyncQueueLayers, SyncQueueIO, SyncQueueProbe]:
  override def moduleName(p: SyncQueueParameter): String =
    s"SyncQueue_width${p.width}_depth${p.depth}_almostEmptyLevel${p.almostEmptyLevel}" +
      s"_almostFullLevel${p.almostFullLevel}_stickyError${p.stickyError}" +
      s"_enableDiagnostics${p.enableDiagnostics}_asyncReset${p.asyncReset}_resetMem${p.resetMem}"

  def expectedGtechCells(p: SyncQueueParameter): Map[String, Int] = Map.empty

  def architecture(parameter: SyncQueueParameter) =
    val io           = summon[Interface[SyncQueueIO]]
    given ClockScope = ClockScope.posedge(io.clock)

    given ResetScope =
      if parameter.asyncReset then ResetScope.asyncActiveLow(io.resetN)
      else ResetScope.syncActiveLow(io.resetN)

    val addressWidth        = parameter.addressWidth
    val addressMask         = (1 << addressWidth) - 1
    val lastAddress         = (parameter.depth - 1) & addressMask
    val halfFullLevel       = ((parameter.depth + 1) / 2) & addressMask
    val almostFullThreshold = parameter.depth - parameter.almostFullLevel
    val powerOfTwoDepth     = (1L << addressWidth) == parameter.depth.toLong

    val notEmpty          = RegInit(false.B)
    val notAlmostEmpty    = RegInit(false.B)
    val halfFull          = RegInit(false.B)
    val almostFull        = RegInit(false.B)
    val full              = RegInit(false.B)
    val error             = RegInit(false.B)
    val writeAddress      = RegInit(0.U(addressWidth))
    val readAddress       = RegInit(0.U(addressWidth))
    val writeAddressAtMax = RegInit(false.B)
    val readAddressAtMax  = RegInit(false.B)
    val wordCount         = RegInit(0.U(addressWidth))

    val writeN = io.pushRequestN | (full & io.popRequestN)
    val read   = !io.popRequestN & notEmpty

    val writeAddressPlusOne = (writeAddress + 1.U(addressWidth)).asBits.bits(addressWidth - 1, 0).asUInt
    val nextWriteAddress    = Wire(UInt(addressWidth))
    nextWriteAddress := writeAddress
    when(!writeN) {
      when(writeAddressAtMax) {
        nextWriteAddress := 0.U(addressWidth)
      }.otherwise {
        nextWriteAddress := writeAddressPlusOne
      }
    }

    val readAddressPlusOne = (readAddress + 1.U(addressWidth)).asBits.bits(addressWidth - 1, 0).asUInt
    val diagnosticClear    = if parameter.enableDiagnostics then !io.diagnosticN else Node(false.B)
    val nextReadAddress    = Wire(UInt(addressWidth))
    nextReadAddress := readAddress
    when((read & readAddressAtMax) | diagnosticClear) {
      nextReadAddress := 0.U(addressWidth)
    }.otherwise {
      when(read) {
        nextReadAddress := readAddressPlusOne
      }
    }

    val nextReadAddressAtMax  =
      (nextReadAddress.asBits & lastAddress.U(addressWidth).asBits).asUInt === lastAddress.U(addressWidth)
    val nextWriteAddressAtMax =
      (nextWriteAddress.asBits & lastAddress.U(addressWidth).asBits).asUInt === lastAddress.U(addressWidth)

    val incrementWordCount =
      (!io.pushRequestN & io.popRequestN & !full) | (!io.pushRequestN & !notEmpty)
    val decrementWordCount = io.pushRequestN & !io.popRequestN & notEmpty
    val wordCountPlusOne   = (wordCount + 1.U(addressWidth)).asBits.bits(addressWidth - 1, 0).asUInt
    val wordCountMinusOne  = (wordCount - 1.U(addressWidth)).asBits.bits(addressWidth - 1, 0).asUInt
    val advancedWordCount  = decrementWordCount ? (wordCountMinusOne, wordCountPlusOne)
    val nextWordCount      = (incrementWordCount | decrementWordCount) ? (advancedWordCount, Node(wordCount))

    val enteringFull       =
      (wordCount === lastAddress.U(addressWidth)) ? (!io.pushRequestN & io.popRequestN, false.B)
    val nextFull           = enteringFull | (full & io.pushRequestN & io.popRequestN) | (full & !io.pushRequestN)
    val nextNotEmpty       =
      (nextWordCount === 0.U(addressWidth)) ? (nextFull, true.B)
    val nextHalfFull       =
      (nextWordCount >= halfFullLevel.U(addressWidth)) ? (true.B, nextFull)
    val belowAlmostEmpty   = nextWordCount <= parameter.almostEmptyLevel.U(addressWidth)
    val nextNotAlmostEmpty =
      if powerOfTwoDepth then !(belowAlmostEmpty & !nextFull) else !belowAlmostEmpty
    val nextAlmostFull     =
      (nextWordCount >= almostFullThreshold.U(addressWidth)) ? (true.B, nextFull)

    val underrun        = !io.popRequestN & !notEmpty
    val overrun         = !io.pushRequestN & io.popRequestN & full
    val pointerMismatch =
      if parameter.enableDiagnostics then (writeAddress.asBits ^ readAddress.asBits).orR ^ (notEmpty & !full)
      else Node(false.B)
    val retainedError   = if parameter.stickyError then Node(error) else Node(false.B)
    val nextError       = underrun | overrun | pointerMismatch | retainedError

    notEmpty          := nextNotEmpty
    notAlmostEmpty    := nextNotAlmostEmpty
    halfFull          := nextHalfFull
    almostFull        := nextAlmostFull
    full              := nextFull
    error             := nextError
    writeAddress      := nextWriteAddress
    readAddress       := nextReadAddress
    writeAddressAtMax := nextWriteAddressAtMax
    readAddressAtMax  := nextReadAddressAtMax
    wordCount         := nextWordCount

    io.empty       := !notEmpty
    io.almostEmpty := !notAlmostEmpty
    io.halfFull    := halfFull
    io.almostFull  := almostFull
    io.full        := full
    io.error       := error

    val ram = Ram.instantiate(
      RamParameter(parameter.width, parameter.depth, parameter.asyncReset, parameter.resetMem)
    )
    ram.io.clock        := io.clock
    ram.io.resetN       := io.resetN
    ram.io.chipSelectN  := false.B
    ram.io.writeN       := writeN
    ram.io.readAddress  := readAddress
    ram.io.writeAddress := writeAddress
    ram.io.writeData    := io.dataIn

    io.dataOut := ram.io.readData

given QueueImpl with
  def apply[D <: HardwareDataType](
    parameter: QueueParameter[D]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Wire[QueueIO[D]] =
    val io    = Wire(new QueueIO(parameter))
    val queue = SyncQueue.instantiate(
      SyncQueueParameter(
        width = parameter.gen.width,
        depth = parameter.entries,
        almostEmptyLevel = parameter.almostEmptyLevel,
        almostFullLevel = parameter.almostFullLevel,
        stickyError = false,
        enableDiagnostics = false,
        asyncReset = parameter.asyncReset,
        resetMem = parameter.resetMem
      )
    )

    queue.io.clock       := io.clock
    queue.io.resetN      := (!io.reset.asBool).asReset
    queue.io.diagnosticN := true.B
    queue.io.dataIn      := io.enq.bits.asBits.asUInt

    io.enq.ready          := !queue.io.full | (if parameter.pipe then io.deq.ready else false.B)
    queue.io.pushRequestN :=
      !(io.enq.fire & (if parameter.flow then !(queue.io.empty & io.deq.ready) else true.B))

    io.deq.valid         := !queue.io.empty | (if parameter.flow then io.enq.valid else false.B)
    queue.io.popRequestN := !(io.deq.ready & !queue.io.empty)
    val storedData = queue.io.dataOut.asBits.asType(io.deq.bits.getType)
    io.deq.bits :<= (if parameter.flow then queue.io.empty ? (io.enq.bits, storedData) else storedData)

    io.empty := queue.io.empty
    io.full  := queue.io.full
    io.almostEmpty.foreach(_ := queue.io.almostEmpty)
    io.almostFull.foreach(_ := queue.io.almostFull)

    io

end given
