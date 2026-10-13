// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.comb.operation

import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.support.HasOperation
import java.lang.foreign.Arena

class Add(val _operation: Operation)
trait AddApi extends HasOperation[Add]:
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ):   Add
  extension (ref: Add)
    def result(
      using Arena
    ): Value

class Sub(val _operation: Operation)
trait SubApi extends HasOperation[Sub]:
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ):   Sub
  extension (ref: Sub)
    def result(
      using Arena
    ): Value

class Mul(val _operation: Operation)
trait MulApi extends HasOperation[Mul]:
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ):   Mul
  extension (ref: Mul)
    def result(
      using Arena
    ): Value

class And(val _operation: Operation)
trait AndApi extends HasOperation[And]:
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ):   And
  extension (ref: And)
    def result(
      using Arena
    ): Value

class Or(val _operation: Operation)
trait OrApi extends HasOperation[Or]:
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ):   Or
  extension (ref: Or)
    def result(
      using Arena
    ): Value

class Xor(val _operation: Operation)
trait XorApi extends HasOperation[Xor]:
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ):   Xor
  extension (ref: Xor)
    def result(
      using Arena
    ): Value

class ICmp(val _operation: Operation)
trait ICmpApi extends HasOperation[ICmp]:
  def op(
    lhs:       Value,
    rhs:       Value,
    predicate: Long,
    location:  Location
  )(
    using Arena,
    Context
  ):   ICmp
  extension (ref: ICmp)
    def result(
      using Arena
    ): Value

class Mux(val _operation: Operation)
trait MuxApi extends HasOperation[Mux]:
  def op(
    cond:      Value,
    whenTrue:  Value,
    whenFalse: Value,
    location:  Location
  )(
    using Arena,
    Context
  ):   Mux
  extension (ref: Mux)
    def result(
      using Arena
    ): Value
