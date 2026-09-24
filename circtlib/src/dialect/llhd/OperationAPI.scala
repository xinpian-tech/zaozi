// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.llhd.operation

import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Location, Operation, Value}
import org.llvm.mlir.scalalib.capi.support.HasOperation

import java.lang.foreign.Arena

class ConstantTime(val _operation: Operation)
trait ConstantTimeApi extends HasOperation[ConstantTime]:
  def op(
    timeUnit: String,
    amount:   Long,
    location: Location
  )(
    using Arena,
    Context
  ):   ConstantTime
  extension (ref: ConstantTime)
    def result(
      using Arena
    ): Value
end ConstantTimeApi

class Signal(val _operation: Operation)
trait SignalApi extends HasOperation[Signal]:
  def op(
    initial:  Value,
    name:     Option[String],
    location: Location
  )(
    using Arena,
    Context
  ):   Signal
  extension (ref: Signal)
    def result(
      using Arena
    ): Value
end SignalApi

class Probe(val _operation: Operation)
trait ProbeApi extends HasOperation[Probe]:
  def op(
    signal:     Value,
    resultType: org.llvm.mlir.scalalib.capi.ir.Type,
    location:   Location
  )(
    using Arena,
    Context
  ):   Probe
  extension (ref: Probe)
    def result(
      using Arena
    ): Value
end ProbeApi

class Drive(val _operation: Operation)
trait DriveApi extends HasOperation[Drive]:
  def op(
    signal:   Value,
    value:    Value,
    delay:    Value,
    enable:   Option[Value],
    location: Location
  )(
    using Arena,
    Context
  ): Drive
end DriveApi

class Process(val _operation: Operation)
trait ProcessApi extends HasOperation[Process]:
  def op(
    location: Location
  )(
    using Arena,
    Context
  ):   Process
  extension (ref: Process)
    def body(
      using Arena
    ): Block
end ProcessApi

class Wait(val _operation: Operation)
trait WaitApi extends HasOperation[Wait]:
  def op(
    delay:       Option[Value],
    observed:    Seq[Value],
    destination: Block,
    location:    Location
  )(
    using Arena,
    Context
  ): Wait
end WaitApi

class Halt(val _operation: Operation)
trait HaltApi extends HasOperation[Halt]:
  def op(
    location: Location
  )(
    using Arena,
    Context
  ): Halt
end HaltApi
