// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.queue.default

import me.jiuyang.stdlib.*
import me.jiuyang.stdlib.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class SingleEntryQueueParameter(
  width:      Int,
  pipe:       Boolean,
  flow:       Boolean,
  asyncReset: Boolean,
  resetMem:   Boolean)
    extends Parameter:
  require(width >= 1 && width <= 2048, s"SingleEntryQueue width must be 1..2048, got $width")

given upickle.default.ReadWriter[SingleEntryQueueParameter] = upickle.default.macroRW

class SingleEntryQueueLayers(parameter: SingleEntryQueueParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class SingleEntryQueueIO(parameter: SingleEntryQueueParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val enq   = Flipped(Decoupled(Bits(parameter.width)))
  val deq   = Aligned(Decoupled(Bits(parameter.width)))
  val empty = Aligned(Bool())
  val full  = Aligned(Bool())

class SingleEntryQueueProbe(parameter: SingleEntryQueueParameter)
    extends DVBundle[SingleEntryQueueParameter, SingleEntryQueueLayers](parameter)

@generator
object SingleEntryQueue
    extends Generator[
      SingleEntryQueueParameter,
      SingleEntryQueueLayers,
      SingleEntryQueueIO,
      SingleEntryQueueProbe
    ]:
  override def moduleName(p: SingleEntryQueueParameter): String =
    s"SingleEntryQueue_width${p.width}_pipe${p.pipe}_flow${p.flow}" +
      s"_asyncReset${p.asyncReset}_resetMem${p.resetMem}"

  def expectedGtechCells(p: SingleEntryQueueParameter): Map[String, Int] = Map.empty

  def architecture(parameter: SingleEntryQueueParameter) =
    val io = summon[Interface[SingleEntryQueueIO]]

    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope =
      if parameter.asyncReset then ResetScope.asyncActiveHigh(io.reset)
      else ResetScope.syncActiveHigh(io.reset)

    val full = RegInit(false.B)
    val data =
      if parameter.resetMem then RegInit(BigInt(0).U(parameter.width).asBits)
      else Reg(Bits(parameter.width))

    io.enq.ready := !full | (if parameter.pipe then io.deq.ready else false.B)
    io.deq.valid := full | (if parameter.flow then io.enq.valid else false.B)

    val enqueue = io.enq.valid & io.enq.ready
    val dequeue = io.deq.valid & io.deq.ready
    val bypass  = if parameter.flow then !full & dequeue else false.B
    val store   = enqueue & !bypass

    full := store | (full & !dequeue)
    when(store):
      data := io.enq.bits.asBits

    io.deq.bits := (if parameter.flow then full ? (data, io.enq.bits) else data)
    io.empty    := !full
    io.full     := full

    layer("Verification"):
      given ClockEvent = posedge(io.clock)

      Cover(enqueue.S, !io.reset.asBool, "single_entry_queue_enqueue_accept")
      Cover(dequeue.S, !io.reset.asBool, "single_entry_queue_dequeue_accept")
      Cover((enqueue & dequeue).S, !io.reset.asBool, "single_entry_queue_enqueue_dequeue_same_cycle")
      Cover((store & !full).S, !io.reset.asBool, "single_entry_queue_empty_to_full")
      Cover((dequeue & full & !store).S, !io.reset.asBool, "single_entry_queue_full_to_empty")
      if parameter.flow then Cover(bypass.S, !io.reset.asBool, "single_entry_queue_flow_bypass")
      if parameter.pipe then Cover((full & enqueue & dequeue).S, !io.reset.asBool, "single_entry_queue_pipe_replace")
