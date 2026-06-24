// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package org.llvm.circt.scalalib.dialect.verif.operation

import org.llvm.mlir.scalalib.capi.ir.{Context, Location, Operation, Value}
import org.llvm.mlir.scalalib.capi.support.HasOperation

import java.lang.foreign.Arena

class Assert(val _operation: Operation)
trait AssertApi extends HasOperation[Assert]:
  def op(
    input:    Value,
    label:    Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): Assert
end AssertApi

class Assume(val _operation: Operation)
trait AssumeApi extends HasOperation[Assume]:
  def op(
    input:    Value,
    label:    Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): Assume
end AssumeApi

class Cover(val _operation: Operation)
trait CoverApi extends HasOperation[Cover]:
  def op(
    input:    Value,
    label:    Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): Cover
end CoverApi
