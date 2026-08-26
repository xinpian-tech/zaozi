// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Yuhang Zeng <unlsycn@unlsycn.com>
package me.jiuyang.stdlib.queue

import me.jiuyang.stdlib.{Decoupled, HardwareDataType}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

/** Generic decoupled FIFO configuration used by all [[QueueImpl]] backends. */
case class QueueParameter[D <: HardwareDataType](
  /** Hardware type stored in each queue entry. */
  gen:              D,
  /** Logical number of entries available to the queue. */
  entries:          Int,
  /** Allow an enqueue while full when a dequeue is accepted in the same cycle. */
  pipe:             Boolean = false,
  /** Bypass `enq.bits` directly to `deq.bits` while the queue is empty. */
  flow:             Boolean = false,
  /** Use asynchronous reset when true, or synchronous reset when false. */
  asyncReset:       Boolean = false,
  /** Reset all storage entries when true; otherwise reset only queue control state. */
  resetMem:         Boolean = false,
  /** Assert `almostEmpty` when occupancy is at most this value. */
  almostEmptyLevel: Int = 1,
  /** Assert `almostFull` when occupancy reaches `entries - almostFullLevel`. */
  almostFullLevel:  Int = 1)
    extends Parameter:
  require(entries >= 2, "Queue entries must be at least 2")

/** Backend-independent decoupled Queue interface. */
class QueueIO[D <: HardwareDataType](
  parameter: QueueParameter[D]
)(
  using TypeImpl,
  ConstructorApi)
    extends HWBundle(parameter):
  val _ca = summon[ConstructorApi]
  import _ca.*

  /** Clock shared by the queue controller and storage. */
  val clock = Flipped(Clock())

  /** Active-high reset using the synchronous or asynchronous mode selected by `asyncReset`. */
  val reset = Flipped(Reset())

  /** Decoupled enqueue endpoint. */
  val enq = Flipped(Decoupled(parameter.gen))

  /** Decoupled dequeue endpoint. */
  val deq = Aligned(Decoupled(parameter.gen))

  /** Indicates that the queue contains no entries. */
  val empty = Aligned(Bool())

  /** Indicates that all logical entries are occupied. */
  val full = Aligned(Bool())

  /** Indicates occupancy at or below `almostEmptyLevel`. */
  val almostEmpty = Option.when(parameter.entries >= 2)(Aligned(Bool()))

  /** Indicates occupancy at or above `entries - almostFullLevel`. */
  val almostFull = Option.when(parameter.entries >= 2)(Aligned(Bool()))

object Queue:
  def apply[D <: HardwareDataType](
    parameter: QueueParameter[D]
  )(
    using Arena,
    QueueImpl,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Wire[QueueIO[D]] = summon[QueueImpl].apply(parameter)

trait QueueImpl:
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
  ): Wire[QueueIO[D]]
