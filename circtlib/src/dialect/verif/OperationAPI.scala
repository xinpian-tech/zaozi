// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package org.llvm.circt.scalalib.dialect.verif.operation

import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Location, Operation, Type, Value}
import org.llvm.mlir.scalalib.capi.support.HasOperation

import scala.scalanative.unsafe.Zone

class Contract(val _operation: Operation)
trait ContractApi extends HasOperation[Contract]:
  def op(
    inputs:      Seq[Value],
    resultTypes: Seq[Type],
    location:    Location
  )(
    using Zone,
    Context
  ): Contract

  extension (ref: Contract)
    def block(
      using Zone
    ): Block
    def result(
      using Zone
    ): Value
end ContractApi

class Assert(val _operation: Operation)
trait AssertApi extends HasOperation[Assert]:
  def op(
    input:    Value,
    label:    Option[String],
    location: Location
  )(
    using Zone,
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
    using Zone,
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
    using Zone,
    Context
  ): Cover
end CoverApi

class Require(val _operation: Operation)
trait RequireApi extends HasOperation[Require]:
  def op(
    input:    Value,
    label:    Option[String],
    location: Location
  )(
    using Zone,
    Context
  ): Require
end RequireApi

class Ensure(val _operation: Operation)
trait EnsureApi extends HasOperation[Ensure]:
  def op(
    input:    Value,
    label:    Option[String],
    location: Location
  )(
    using Zone,
    Context
  ): Ensure
end EnsureApi
