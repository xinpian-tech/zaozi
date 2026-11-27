// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.queue.default

import me.jiuyang.stdlib.queue.{SyncQueueIO, SyncQueueLayers, SyncQueueParameter, SyncQueueProbe, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

@generator
object SingleEntrySyncQueue extends Generator[SyncQueueParameter, SyncQueueLayers, SyncQueueIO, SyncQueueProbe]:
  override def moduleName(p: SyncQueueParameter): String =
    s"SingleEntrySyncQueue_width${p.width}_depth${p.depth}_almostEmptyLevel${p.almostEmptyLevel}" +
      s"_almostFullLevel${p.almostFullLevel}_stickyError${p.stickyError}" +
      s"_enableDiagnostics${p.enableDiagnostics}_asyncReset${p.asyncReset}_resetMem${p.resetMem}"

  def expectedGtechCells(p: SyncQueueParameter): Map[String, Int] = Map.empty

  def architecture(parameter: SyncQueueParameter) =
    require(parameter.depth == 1, s"SingleEntrySyncQueue requires depth=1, got ${parameter.depth}")

    val io = summon[Interface[SyncQueueIO]]

    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope =
      if parameter.asyncReset then ResetScope.asyncActiveLow(io.resetN)
      else ResetScope.syncActiveLow(io.resetN)

    val full  = RegInit(false.B)
    val data  =
      if parameter.resetMem then RegInit(BigInt(0).U(parameter.width).asBits)
      else Reg(Bits(parameter.width))
    val error = RegInit(false.B)

    val pushRequest = !io.pushRequestN
    val popRequest  = !io.popRequestN
    val push        = pushRequest & (!full | popRequest)
    val pop         = popRequest & full
    val underrun    = popRequest & !full
    val overrun     = pushRequest & !popRequest & full

    full  := push | (full & !pop)
    error := (if parameter.stickyError then error | underrun | overrun else underrun | overrun)
    when(push):
      data := io.dataIn.asBits

    io.empty       := !full
    io.almostEmpty := (if parameter.almostEmptyLevel == 0 then !full else true.B)
    io.halfFull    := full
    io.almostFull  := (if parameter.almostFullLevel == 0 then full else true.B)
    io.full        := full
    io.error       := error
    io.dataOut     := data.asUInt

    layer("Verification"):
      given ClockEvent = posedge(io.clock)

      Cover(push.S, io.resetN.asBool, "single_entry_queue_push_accept")
      Cover(pop.S, io.resetN.asBool, "single_entry_queue_pop_accept")
      Cover((push & pop).S, io.resetN.asBool, "single_entry_queue_push_pop_same_cycle")
      Cover((push & !full).S, io.resetN.asBool, "single_entry_queue_empty_to_full")
      Cover((pop & full & !push).S, io.resetN.asBool, "single_entry_queue_full_to_empty")
      Cover(underrun.S, io.resetN.asBool, "single_entry_queue_underflow_request")
      Cover(overrun.S, io.resetN.asBool, "single_entry_queue_overflow_request")
      if parameter.stickyError then Cover(error.S, io.resetN.asBool, "single_entry_queue_sticky_error_retained")
