// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozi.ltltpe

import me.jiuyang.zaozi.TypeImpl
import me.jiuyang.zaozi.reftpe.HasOperation
import org.llvm.mlir.scalalib.capi.ir.{Context, Operation, Type, Value}
import java.lang.foreign.Arena

/** CIRCT `LTLAnyPropertyType`: `i1 | !ltl.sequence | !ltl.property`.
  *
  * Use [[LTLSequenceLike]] and [[LTLPropertyLike]] for API operand constraints. They mirror CIRCT's
  * `LTLAnySequenceType` and `LTLAnyPropertyType` constraints. And should not be used directly.
  */
trait LTLPropertyLike extends HasOperation:
  private[zaozi] val _operation: Operation

  def operation(
    using TypeImpl
  ): Operation

  def refer(
    using Arena,
    TypeImpl
  ): Value

  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type

/** CIRCT `LTLAnySequenceType`: `i1 | !ltl.sequence`. */
trait LTLSequenceLike extends LTLPropertyLike
