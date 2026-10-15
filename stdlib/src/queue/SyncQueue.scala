// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Yuhang Zeng <unlsycn@unlsycn.com>
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.queue

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Active-low operation-oriented synchronous FIFO configuration. */
case class SyncQueueParameter(
  /** Number of bits stored in each queue entry. */
  width:             Int,
  /** Logical number of entries available to the queue. */
  depth:             Int,
  /** Assert `almostEmpty` when occupancy is at most this value. */
  almostEmptyLevel:  Int,
  /** Assert `almostFull` when occupancy reaches `depth - almostFullLevel`. */
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
  require(depth >= 1 && depth <= 1024, s"SyncQueue depth must be 1..1024, got $depth")
  if depth == 1 then
    require(
      almostEmptyLevel >= 0 && almostEmptyLevel <= 1,
      s"SyncQueue almostEmptyLevel must be 0..1, got $almostEmptyLevel"
    )
    require(
      almostFullLevel >= 0 && almostFullLevel <= 1,
      s"SyncQueue almostFullLevel must be 0..1, got $almostFullLevel"
    )
  else
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
  def addressWidth: Int = QueueGeometry.bitWidth(depth)

given upickle.default.ReadWriter[SyncQueueParameter] = upickle.default.macroRW

class SyncQueueLayers(parameter: SyncQueueParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

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

object SyncQueue:
  def apply(
    parameter: SyncQueueParameter
  )(
    using Arena,
    SyncQueueImpl,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Wire[SyncQueueIO] = summon[SyncQueueImpl].apply(parameter)

trait SyncQueueImpl:
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
  ): Wire[SyncQueueIO]

private[queue] object QueueGeometry:
  /** Minimum number of bits required to represent values in `[0, value)`. */
  def bitWidth(value: Int): Int =
    require(value > 0, s"bitWidth input must be positive, got $value")
    math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(value - 1))

  /** Physical RAM depth used by the default arbitrary-depth dual-clock controller. */
  def effectiveDepth(depth: Int): Int =
    if (1 << bitWidth(depth)) == depth then depth else depth + 2 - (depth % 2)
