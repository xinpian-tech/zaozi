// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.firrtl.operation

import org.llvm.circt.scalalib.capi.dialect.firrtl.{FirrtlBundleField, FirrtlEventControl, FirrtlNameKind}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Context, Location, Operation, Type, Value}

import java.lang.foreign.Arena

class InstanceChoice(val _operation: Operation)
class Instance(val _operation: Operation)
trait InstanceApi extends HasOperation[Instance]:
  inline def op(
    moduleName:   String,
    instanceName: String,
    nameKind:     FirrtlNameKind,
    location:     Location,
    interface:    Seq[FirrtlBundleField],
    layers:       Seq[Seq[String]]
  )(
    using arena:  Arena,
    context:      Context
  ): Instance
end InstanceApi
class Mem(val _operation: Operation)
class Node(val _operation: Operation)
trait NodeApi     extends HasOperation[Node]:
end NodeApi
class Object(val _operation: Operation)
class Reg(val _operation: Operation)
trait RegApi      extends HasOperation[Reg]:
  inline def op(
    name:        String,
    location:    Location,
    nameKind:    FirrtlNameKind,
    tpe:         Type,
    clock:       Value,
    clockEdge:   FirrtlEventControl
  )(
    using arena: Arena,
    context:     Context
  ): Reg
end RegApi
class RegReset(val _operation: Operation)
enum RegResetType:
  case SyncReset, AsyncReset

  def attrValue: Long = this match
    case SyncReset  => 0L
    case AsyncReset => 1L
end RegResetType

enum RegResetPolarity:
  case PosReset, NegReset

  def attrValue: Long = this match
    case PosReset => 0L
    case NegReset => 1L
end RegResetPolarity

trait RegResetApi extends HasOperation[RegReset]:
  inline def op(
    name:          String,
    location:      Location,
    nameKind:      FirrtlNameKind,
    tpe:           Type,
    clock:         Value,
    reset:         Value,
    resetValue:    Value,
    clockEdge:     FirrtlEventControl,
    resetType:     RegResetType,
    resetPolarity: RegResetPolarity
  )(
    using arena:   Arena,
    context:       Context
  ): RegReset
end RegResetApi
class Wire(val _operation: Operation)
trait WireApi     extends HasOperation[Wire]:
  def op(
    name:        String,
    location:    Location,
    nameKind:    FirrtlNameKind,
    tpe:         Type
  )(
    using arena: Arena,
    context:     Context
  ):   Wire
  extension (ref: Wire)
    def result(
      using Arena
    ): Value
end WireApi
