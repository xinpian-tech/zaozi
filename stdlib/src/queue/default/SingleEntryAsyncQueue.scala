// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.queue.default

import java.lang.foreign.Arena

import me.jiuyang.stdlib.queue.{AsyncQueueIO, AsyncQueueLayers, AsyncQueueParameter, AsyncQueueProbe, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

private final case class SingleEntryAsyncDirectionState(
  toggle: Reg[Bool],
  error:  Reg[Bool])

private final case class SingleEntryAsyncDirectionOutput(
  toggle:          Referable[Bool],
  empty:           Referable[Bool],
  almostEmpty:     Referable[Bool],
  halfFull:        Referable[Bool],
  almostFull:      Referable[Bool],
  full:            Referable[Bool],
  error:           Referable[Bool],
  accepted:        Referable[Bool],
  boundaryRequest: Referable[Bool])

private enum SingleEntryAsyncDirectionKind:
  case Push, Pop

private final class SingleEntryAsyncDirection(
  clock:              Ref[Clock],
  requestN:           Referable[Bool],
  almostEmptyLevel:   Int,
  almostFullLevel:    Int,
  synchronizerStages: Int,
  stickyError:        Boolean,
  kind:               SingleEntryAsyncDirectionKind
)(
  using Arena,
  Context,
  Block,
  sourcecode.File,
  sourcecode.Line,
  sourcecode.Name.Machine,
  InstanceContext,
  ResetScope):
  private val state =
    ClockScope.posedge(clock):
      SingleEntryAsyncDirectionState(
        toggle = RegInit(false.B),
        error = RegInit(false.B)
      )

  def toggle: Referable[Bool] = state.toggle

  def build(remoteToggle: Referable[Bool]): SingleEntryAsyncDirectionOutput =
    val synchronizedRemoteToggle =
      ClockScope.posedge(clock):
        val stages = Seq.fill(synchronizerStages)(RegInit(false.B))
        stages.head := remoteToggle
        stages.tail
          .zip(stages)
          .foreach: (sink, source) =>
            sink := source
        Node(stages.last)

    val occupied = state.toggle ^ synchronizedRemoteToggle
    val empty    = !occupied
    val full     = occupied

    val atBoundary      = kind match
      case SingleEntryAsyncDirectionKind.Push => full
      case SingleEntryAsyncDirectionKind.Pop  => empty
    val request         = !requestN
    val accepted        = request & !atBoundary
    val boundaryRequest = request & atBoundary

    ClockScope.posedge(clock):
      when(accepted) {
        state.toggle := !state.toggle
      }
      state.error := (if stickyError then state.error | boundaryRequest else boundaryRequest)

    SingleEntryAsyncDirectionOutput(
      toggle = state.toggle,
      empty = empty,
      almostEmpty = if almostEmptyLevel == 0 then empty else true.B,
      halfFull = full,
      almostFull = if almostFullLevel == 0 then full else true.B,
      full = full,
      error = state.error,
      accepted = accepted,
      boundaryRequest = boundaryRequest
    )

@generator
object SingleEntryAsyncQueue extends Generator[AsyncQueueParameter, AsyncQueueLayers, AsyncQueueIO, AsyncQueueProbe]:
  override def moduleName(p: AsyncQueueParameter): String =
    s"SingleEntryAsyncQueue_width${p.width}_depth${p.depth}_pushAlmostEmptyLevel${p.pushAlmostEmptyLevel}" +
      s"_pushAlmostFullLevel${p.pushAlmostFullLevel}_popAlmostEmptyLevel${p.popAlmostEmptyLevel}" +
      s"_popAlmostFullLevel${p.popAlmostFullLevel}_stickyError${p.stickyError}" +
      s"_pushSync${p.pushSync}_popSync${p.popSync}_asyncReset${p.asyncReset}_resetMem${p.resetMem}"

  def expectedGtechCells(p: AsyncQueueParameter): Map[String, Int] = Map.empty

  def architecture(parameter: AsyncQueueParameter) =
    require(parameter.depth == 1, s"SingleEntryAsyncQueue requires depth=1, got ${parameter.depth}")

    val io = summon[Interface[AsyncQueueIO]]

    given ClockScope = ClockScope.posedge(io.push.clock)
    given ResetScope =
      if parameter.asyncReset then ResetScope.asyncActiveLow(io.resetN)
      else ResetScope.syncActiveLow(io.resetN)

    val pushDirection = new SingleEntryAsyncDirection(
      clock = io.push.clock,
      requestN = io.push.requestN,
      almostEmptyLevel = parameter.pushAlmostEmptyLevel,
      almostFullLevel = parameter.pushAlmostFullLevel,
      synchronizerStages = parameter.pushSync,
      stickyError = parameter.stickyError,
      kind = SingleEntryAsyncDirectionKind.Push
    )
    val popDirection  = new SingleEntryAsyncDirection(
      clock = io.pop.clock,
      requestN = io.pop.requestN,
      almostEmptyLevel = parameter.popAlmostEmptyLevel,
      almostFullLevel = parameter.popAlmostFullLevel,
      synchronizerStages = parameter.popSync,
      stickyError = parameter.stickyError,
      kind = SingleEntryAsyncDirectionKind.Pop
    )

    val push = pushDirection.build(popDirection.toggle)
    val pop  = popDirection.build(pushDirection.toggle)

    val data =
      if parameter.resetMem then RegInit(BigInt(0).U(parameter.width).asBits)
      else Reg(Bits(parameter.width))
    when(push.accepted) {
      data := io.dataIn.asBits
    }

    io.push.empty       := push.empty
    io.push.almostEmpty := push.almostEmpty
    io.push.halfFull    := push.halfFull
    io.push.almostFull  := push.almostFull
    io.push.full        := push.full
    io.push.error       := push.error
    io.pop.empty        := pop.empty
    io.pop.almostEmpty  := pop.almostEmpty
    io.pop.halfFull     := pop.halfFull
    io.pop.almostFull   := pop.almostFull
    io.pop.full         := pop.full
    io.pop.error        := pop.error
    io.dataOut          := data.asUInt

    layer("Verification"):
      posedge(io.push.clock):
        Cover(push.accepted.S, io.resetN.asBool, "single_entry_async_queue_push_accept")
        Cover((push.accepted & push.empty).S, io.resetN.asBool, "single_entry_async_queue_push_empty_to_full")
        Cover(push.full.S, io.resetN.asBool, "single_entry_async_queue_push_reach_full")
        Cover(push.boundaryRequest.S, io.resetN.asBool, "single_entry_async_queue_overflow_request")
        if parameter.stickyError then
          Cover(push.error.S, io.resetN.asBool, "single_entry_async_queue_push_sticky_error_retained")

      posedge(io.pop.clock):
        Cover(pop.accepted.S, io.resetN.asBool, "single_entry_async_queue_pop_accept")
        Cover((pop.accepted & pop.full).S, io.resetN.asBool, "single_entry_async_queue_pop_to_empty")
        Cover(pop.full.S, io.resetN.asBool, "single_entry_async_queue_pop_reach_full")
        Cover(pop.boundaryRequest.S, io.resetN.asBool, "single_entry_async_queue_underflow_request")
        if parameter.stickyError then
          Cover(pop.error.S, io.resetN.asBool, "single_entry_async_queue_pop_sticky_error_retained")
