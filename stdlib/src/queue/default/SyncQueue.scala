// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.queue.default

import java.lang.foreign.Arena

import me.jiuyang.stdlib.*
import me.jiuyang.stdlib.default.{*, given}
import me.jiuyang.stdlib.queue.{SyncQueueIO, SyncQueueImpl, SyncQueueLayers, SyncQueueParameter, SyncQueueProbe, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Operation-oriented synchronous FIFO implementation.
  *
  * The controller is elaborated in this generator. [[Ram]] and the arithmetic primitives are the queue's child modules.
  */
@generator
object SyncQueue extends Generator[SyncQueueParameter, SyncQueueLayers, SyncQueueIO, SyncQueueProbe]:
  override def moduleName(p: SyncQueueParameter): String =
    s"SyncQueue_width${p.width}_depth${p.depth}_almostEmptyLevel${p.almostEmptyLevel}" +
      s"_almostFullLevel${p.almostFullLevel}_stickyError${p.stickyError}" +
      s"_enableDiagnostics${p.enableDiagnostics}_asyncReset${p.asyncReset}_resetMem${p.resetMem}"

  def expectedGtechCells(p: SyncQueueParameter): Map[String, Int] = Map.empty

  def architecture(parameter: SyncQueueParameter) =
    require(parameter.depth >= 2, s"SyncQueue requires depth>=2, got ${parameter.depth}")

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

    layer("Verification"):
      given ClockEvent = posedge(io.clock)

      Cover((!writeN).S, io.resetN.asBool, "sync_queue_push_accept")
      Cover(read.S, io.resetN.asBool, "sync_queue_pop_accept")
      Cover((!writeN & read).S, io.resetN.asBool, "sync_queue_push_pop_same_cycle")
      Cover((!notEmpty & !writeN).S, io.resetN.asBool, "sync_queue_empty_to_nonempty")
      Cover((read & !nextNotEmpty).S, io.resetN.asBool, "sync_queue_pop_to_empty")
      Cover(nextHalfFull.S, io.resetN.asBool, "sync_queue_reach_half_full")
      Cover(nextAlmostFull.S, io.resetN.asBool, "sync_queue_reach_almost_full")
      Cover(nextFull.S, io.resetN.asBool, "sync_queue_reach_full")
      Cover((!writeN & writeAddressAtMax).S, io.resetN.asBool, "sync_queue_write_wrap")
      Cover((read & readAddressAtMax).S, io.resetN.asBool, "sync_queue_read_wrap")
      Cover(underrun.S, io.resetN.asBool, "sync_queue_underflow_request")
      Cover(overrun.S, io.resetN.asBool, "sync_queue_overflow_request")
      if parameter.enableDiagnostics then
        Cover(diagnosticClear.S, io.resetN.asBool, "sync_queue_diagnostic_clear")
        Cover(pointerMismatch.S, io.resetN.asBool, "sync_queue_diagnostic_pointer_mismatch")
      if parameter.stickyError then Cover(retainedError.S, io.resetN.asBool, "sync_queue_sticky_error_retained")

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

given SyncQueueImpl with
  def apply(
    parameter: SyncQueueParameter
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Wire[SyncQueueIO] =
    val io    = Wire(new SyncQueueIO(parameter))
    val queue =
      if parameter.depth == 1 then SingleEntrySyncQueue.instantiate(parameter)
      else SyncQueue.instantiate(parameter)

    queue.io.clock        := io.clock
    queue.io.resetN       := io.resetN
    queue.io.pushRequestN := io.pushRequestN
    queue.io.popRequestN  := io.popRequestN
    queue.io.diagnosticN  := io.diagnosticN
    queue.io.dataIn       := io.dataIn
    io.empty              := queue.io.empty
    io.almostEmpty        := queue.io.almostEmpty
    io.halfFull           := queue.io.halfFull
    io.almostFull         := queue.io.almostFull
    io.full               := queue.io.full
    io.error              := queue.io.error
    io.dataOut            := queue.io.dataOut

    io

end given
