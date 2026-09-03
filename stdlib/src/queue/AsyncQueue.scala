// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.queue

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Operation-oriented dual-clock FIFO configuration. */
case class AsyncQueueParameter(
  /** Number of bits stored in each queue entry. */
  width:                Int,
  /** Logical number of entries available to the queue. */
  depth:                Int,
  /** Assert push-domain almost-empty when its estimated occupancy is at most this value. */
  pushAlmostEmptyLevel: Int,
  /** Assert push-domain almost-full when its estimated occupancy reaches `depth - pushAlmostFullLevel`. */
  pushAlmostFullLevel:  Int,
  /** Assert pop-domain almost-empty when its estimated occupancy is at most this value. */
  popAlmostEmptyLevel:  Int,
  /** Assert pop-domain almost-full when its estimated occupancy reaches `depth - popAlmostFullLevel`. */
  popAlmostFullLevel:   Int,
  /** Keep overflow and underflow errors asserted until reset; otherwise report only the current illegal request. */
  stickyError:          Boolean,
  /** Synchronizer stages used to transfer the pop Gray pointer into the push clock domain. */
  pushSync:             Int,
  /** Synchronizer stages used to transfer the push Gray pointer into the pop clock domain. */
  popSync:              Int,
  /** Use asynchronous active-low reset when true, or synchronous active-low reset when false. */
  asyncReset:           Boolean,
  /** Reset all storage entries when true; otherwise reset only queue control state. */
  resetMem:             Boolean)
    extends Parameter:
  require(width >= 1 && width <= 256, s"AsyncQueue width must be 1..256, got $width")
  require(depth > 0 && depth <= 256, s"AsyncQueue depth must be 1..256, got $depth")
  Seq(
    "pushAlmostEmptyLevel" -> pushAlmostEmptyLevel,
    "pushAlmostFullLevel"  -> pushAlmostFullLevel,
    "popAlmostEmptyLevel"  -> popAlmostEmptyLevel,
    "popAlmostFullLevel"   -> popAlmostFullLevel
  ).foreach: (name, level) =>
    if depth == 1 then require(level >= 0 && level <= 1, s"AsyncQueue $name must be 0..1 for depth=1, got $level")
    else require(level >= 1 && level <= depth - 1, s"AsyncQueue $name must be 1..depth-1, got $level")
  require(pushSync >= 1 && pushSync <= 3, s"AsyncQueue pushSync must be 1..3, got $pushSync")
  require(popSync >= 1 && popSync <= 3, s"AsyncQueue popSync must be 1..3, got $popSync")

  /** Number of bits used by the physical storage addresses. */
  def addressWidth: Int = QueueGeometry.bitWidth(depth)

  /** Physical storage depth required by the default arbitrary-depth pointer mapping. */
  def effectiveDepth: Int = if depth == 1 then 1 else QueueGeometry.effectiveDepth(depth)

given upickle.default.ReadWriter[AsyncQueueParameter] = upickle.default.macroRW

class AsyncQueueLayers(parameter: AsyncQueueParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class AsyncQueuePortIO extends Bundle:
  /** Local clock for this push or pop endpoint. */
  val clock = Flipped(Clock())

  /** Active-low push or pop request sampled in this endpoint's clock domain. */
  val requestN = Flipped(Bool())

  /** Local synchronized view indicating that the queue contains no entries. */
  val empty = Aligned(Bool())

  /** Local synchronized view indicating occupancy at or below the configured almost-empty level. */
  val almostEmpty = Aligned(Bool())

  /** Local synchronized view indicating occupancy at or above half the logical depth. */
  val halfFull = Aligned(Bool())

  /** Local synchronized view indicating occupancy at or above the configured almost-full threshold. */
  val almostFull = Aligned(Bool())

  /** Local synchronized view indicating that all logical entries are occupied. */
  val full = Aligned(Bool())

  /** Registered overflow or underflow indication for this endpoint. */
  val error = Aligned(Bool())

class AsyncQueueIO(parameter: AsyncQueueParameter) extends HWBundle(parameter):
  /** Shared active-low reset for queue control state and, when enabled, storage contents. */
  val resetN = Flipped(Reset())

  /** Push-clock-domain request and status interface. */
  val push = Aligned(new AsyncQueuePortIO)

  /** Pop-clock-domain request and status interface. */
  val pop = Aligned(new AsyncQueuePortIO)

  /** Data written when a push request is accepted. */
  val dataIn = Flipped(UInt(parameter.width))

  /** Data at the current pop address; consumed only by an accepted pop request. */
  val dataOut = Aligned(UInt(parameter.width))

class AsyncQueueProbe(parameter: AsyncQueueParameter) extends DVBundle[AsyncQueueParameter, AsyncQueueLayers](parameter)

object AsyncQueue:
  def apply(
    parameter: AsyncQueueParameter
  )(
    using Arena,
    AsyncQueueImpl,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Wire[AsyncQueueIO] = summon[AsyncQueueImpl].apply(parameter)

trait AsyncQueueImpl:
  def apply(
    parameter: AsyncQueueParameter
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Wire[AsyncQueueIO]
