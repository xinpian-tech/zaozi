// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.comb.operation

import org.llvm.mlir.scalalib.capi.ir.{Context, Location, Operation, Value}
import org.llvm.mlir.scalalib.capi.support.HasOperation
import java.lang.foreign.Arena

enum ICmpPredicate(val encoding: Long):
  case Eq  extends ICmpPredicate(0)
  case Ne  extends ICmpPredicate(1)
  case Slt extends ICmpPredicate(2)
  case Sle extends ICmpPredicate(3)
  case Sgt extends ICmpPredicate(4)
  case Sge extends ICmpPredicate(5)
  case Ult extends ICmpPredicate(6)
  case Ule extends ICmpPredicate(7)
  case Ugt extends ICmpPredicate(8)
  case Uge extends ICmpPredicate(9)

class Add(val _operation: Operation)
trait AddApi extends HasOperation[Add]:
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ): Add

class Sub(val _operation: Operation)
trait SubApi extends HasOperation[Sub]:
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ): Sub

class And(val _operation: Operation)
trait AndApi extends HasOperation[And]:
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ): And

class Or(val _operation: Operation)
trait OrApi extends HasOperation[Or]:
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ): Or

class Xor(val _operation: Operation)
trait XorApi extends HasOperation[Xor]:
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ): Xor

class ICmp(val _operation: Operation)
trait ICmpApi extends HasOperation[ICmp]:
  def op(
    predicate: ICmpPredicate,
    lhs:       Value,
    rhs:       Value,
    location:  Location
  )(
    using Arena,
    Context
  ): ICmp

class Concat(val _operation: Operation)
trait ConcatApi extends HasOperation[Concat]:
  /** Concatenates operands in MSB-first order. */
  def op(
    inputs:   Seq[Value],
    location: Location
  )(
    using Arena,
    Context
  ): Concat

class Extract(val _operation: Operation)
trait ExtractApi extends HasOperation[Extract]:
  def op(
    input:    Value,
    low:      Int,
    width:    Int,
    location: Location
  )(
    using Arena,
    Context
  ): Extract

class Mux(val _operation: Operation)
trait MuxApi extends HasOperation[Mux]:
  def op(
    condition: Value,
    whenTrue:  Value,
    whenFalse: Value,
    location:  Location
  )(
    using Arena,
    Context
  ): Mux
