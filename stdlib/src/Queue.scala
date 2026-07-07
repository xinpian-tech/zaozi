// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Yuhang Zeng <unlsycn@unlsycn.com>
package me.jiuyang.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

case class QueueParameter[D <: HardwareDataType](
  gen:              D,
  entries:          Int,
  pipe:             Boolean = false,
  flow:             Boolean = false,
  useAsyncReset:    Boolean = false,
  resetMem:         Boolean = false,
  almostEmptyLevel: Int = 1,
  almostFullLevel:  Int = 1)
    extends Parameter

class QueueIO[D <: HardwareDataType](
  parameter: QueueParameter[D]
)(
  using TypeImpl,
  ConstructorApi)
    extends HWBundle(parameter):
  val _ca = summon[ConstructorApi]
  import _ca.*

  val clock       = Flipped(Clock())
  val reset       = Flipped(Reset())
  val enq         = Flipped(Decoupled(parameter.gen))
  val deq         = Aligned(Decoupled(parameter.gen))
  val empty       = Aligned(Bool())
  val full        = Aligned(Bool())
  val almostEmpty = Option.when(parameter.entries >= 2)(Aligned(Bool()))
  val almostFull  = Option.when(parameter.entries >= 2)(Aligned(Bool()))

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
