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
  * The controller is elaborated in this generator. [[Ram]] and the arithmetic primitives are the queue's child modules.
  */
case class SyncQueueParameter(
  /** Number of bits stored in each queue entry. */
  width:             Int,
  /** Logical number of entries available to the queue. */
  depth:             Int,
  /** Assert almost-empty when occupancy is at most this value. */
  almostEmptyLevel:  Int,
  /** Assert almost-full when occupancy reaches `depth - almostFullLevel`. */
  almostFullLevel:   Int,
  /** Keep overflow, underflow, and enabled diagnostic errors asserted until reset. */
  stickyError:       Boolean,
  /** Enable pointer-consistency diagnostics controlled by `diagnosticN`; requires sticky errors. */
  enableDiagnostics: Boolean,
  /** Use asynchronous active-low reset when true, or synchronous active-low reset when false. */
  asyncReset:        Boolean,
  /** Reset all RAM entries to zero when true; otherwise reset only queue control state. */
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

  /** Number of bits used by the read and write addresses. */
  def addressWidth: Int = QueueHelper.bitWidth(depth)

given upickle.default.ReadWriter[SyncQueueParameter] = upickle.default.macroRW

class SyncQueueLayers(parameter: SyncQueueParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class SyncQueueIO(parameter: SyncQueueParameter) extends HWBundle(parameter):
  /** Clock shared by the queue controller and RAM writes. */
  val clock = Flipped(Clock())

  /** Active-low reset for control state and, when enabled, RAM contents. */
  val resetN = Flipped(Reset())

  /** Active-low request to enqueue `dataIn`. */
  val pushRequestN = Flipped(Bool())

  /** Active-low request to dequeue the current entry. */
  val popRequestN = Flipped(Bool())

  /** Active-low diagnostic control used only when `enableDiagnostics` is true. */
  val diagnosticN = Flipped(Bool())

  /** Data written when a push request is accepted. */
  val dataIn = Flipped(UInt(parameter.width))

  /** Indicates that the queue contains no entries. */
  val empty = Aligned(Bool())

  /** Indicates occupancy at or below `almostEmptyLevel`. */
  val almostEmpty = Aligned(Bool())

  /** Indicates occupancy at or above half the logical depth. */
  val halfFull = Aligned(Bool())

  /** Indicates occupancy at or above `depth - almostFullLevel`. */
  val almostFull = Aligned(Bool())

  /** Indicates that all logical entries are occupied. */
  val full = Aligned(Bool())

  /** Registered overflow, underflow, or enabled diagnostic error indication. */
  val error = Aligned(Bool())

  /** Data at the current read address. */
  val dataOut = Aligned(UInt(parameter.width))

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

    // The RAM write control is active low. A push is accepted unless the queue is full and no simultaneous pop frees
    // an entry.
    val writeN = io.pushRequestN | (full & io.popRequestN)
    val read   = !io.popRequestN & notEmpty

    val writeAddressIncrementer = Incrementer.instantiate(BKAIncrementerParameter(addressWidth))
    writeAddressIncrementer.io.A := writeAddress.asBits
    val writeAddressPlusOne = writeAddressIncrementer.io.SUM.asUInt
    val nextWriteAddress    = Wire(UInt(addressWidth))
    nextWriteAddress := writeAddress
    when(!writeN) {
      when(writeAddressAtMax) {
        nextWriteAddress := 0.U(addressWidth)
      }.otherwise {
        nextWriteAddress := writeAddressPlusOne
      }
    }

    val readAddressIncrementer = Incrementer.instantiate(BKAIncrementerParameter(addressWidth))
    readAddressIncrementer.io.A := readAddress.asBits
    val readAddressPlusOne = readAddressIncrementer.io.SUM.asUInt
    // Diagnostic mode follows the DWBB interface: pulling diagnosticN low returns the read pointer to address zero.
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

    // Simultaneous accepted push and pop leave occupancy unchanged. A push into an empty queue still increments it
    // even when pop is also requested, because the pop is rejected by the current empty state.
    val incrementWordCount   =
      (!io.pushRequestN & io.popRequestN & !full) | (!io.pushRequestN & !notEmpty)
    val decrementWordCount   = io.pushRequestN & !io.popRequestN & notEmpty
    val wordCountIncrementer = Incrementer.instantiate(BKAIncrementerParameter(addressWidth))
    wordCountIncrementer.io.A := wordCount.asBits
    val wordCountPlusOne    = wordCountIncrementer.io.SUM.asUInt
    val wordCountSubtractor = BrentKungAdder.instantiate(BrentKungAdderParameter(addressWidth, 4))
    val wordCountSubtractIO =
      wordCountSubtractor.io.asInstanceOf[Interface[PrefixAdderIO[BrentKungAdderParameter]]]
    wordCountSubtractIO.A  := wordCount.asBits
    wordCountSubtractIO.B  := ~1.U(addressWidth).asBits
    wordCountSubtractIO.CI := true.B
    val wordCountMinusOne = wordCountSubtractIO.SUM.asUInt
    val advancedWordCount = decrementWordCount ? (wordCountMinusOne, wordCountPlusOne)
    val nextWordCount     = (incrementWordCount | decrementWordCount) ? (advancedWordCount, Node(wordCount))

    // At a power-of-two depth wordCount cannot encode `depth`; full carries that extra occupancy state explicitly.
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
    // Equal pointers are valid only at empty or full. Unequal pointers must denote a partially occupied queue.
    val pointerMismatch =
      if parameter.enableDiagnostics then (writeAddress.asBits ^ readAddress.asBits).orR ^ (notEmpty & !full)
      else Node(false.B)
    val retainedError   = if parameter.stickyError then Node(error) else Node(false.B)
    val nextError       = underrun | overrun | pointerMismatch | retainedError

    // Commit pointers, occupancy, status, and error together so the registered outputs describe the same request set.
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

    // `pipe` lets a same-cycle dequeue make room in a full queue. `flow` bypasses an empty queue without writing RAM.
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
