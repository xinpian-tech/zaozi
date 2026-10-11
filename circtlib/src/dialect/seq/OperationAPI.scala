// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.seq.operation

import org.llvm.mlir.scalalib.capi.ir.{Context, Location, Operation, Value}
import org.llvm.mlir.scalalib.capi.support.HasOperation

import java.lang.foreign.Arena

class ToClock(val _operation: Operation)
trait ToClockApi extends HasOperation[ToClock]:
  def op(
    input:    Value,
    location: Location
  )(
    using Arena,
    Context
  ):   ToClock
  extension (ref: ToClock)
    def result(
      using Arena
    ): Value

class FromClock(val _operation: Operation)
trait FromClockApi extends HasOperation[FromClock]:
  def op(
    input:    Value,
    location: Location
  )(
    using Arena,
    Context
  ): FromClock
  extension (ref: FromClock)
    def result(
      using Arena
    ): Value

class ClockInv(val _operation: Operation)
trait ClockInvApi extends HasOperation[ClockInv]:
  def op(
    input:    Value,
    location: Location
  )(
    using Arena,
    Context
  ):   ClockInv
  extension (ref: ClockInv)
    def result(
      using Arena
    ): Value
