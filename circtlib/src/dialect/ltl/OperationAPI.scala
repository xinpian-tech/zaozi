// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.ltl.operation

import org.llvm.circt.scalalib.capi.dialect.ltl.LTLClockEdge
import org.llvm.mlir.scalalib.capi.support.HasOperation
import org.llvm.mlir.scalalib.capi.ir.{Context, Location, Operation, Value}

import java.lang.foreign.Arena

class And(val _operation: Operation)
trait AndApi extends HasOperation[And]:
  def op(
    inputs:   Seq[Value],
    location: Location
  )(
    using Arena,
    Context
  ):   And
  extension (ref: And)
    def result(
      using Arena
    ): Value
end AndApi

class BooleanConstant(val _operation: Operation)
trait BooleanConstantApi extends HasOperation[BooleanConstant]:
  def op(
    value:    Boolean,
    location: Location
  )(
    using Arena,
    Context
  ):   BooleanConstant
  extension (ref: BooleanConstant)
    def result(
      using Arena
    ): Value
end BooleanConstantApi

class ClockedAtom(val _operation: Operation)
trait ClockedAtomApi extends HasOperation[ClockedAtom]:
  def op(
    input:    Value,
    edge:     LTLClockEdge,
    clock:    Value,
    location: Location
  )(
    using Arena,
    Context
  ):   ClockedAtom
  extension (ref: ClockedAtom)
    def result(
      using Arena
    ): Value
end ClockedAtomApi

class ClockedDelay(val _operation: Operation)
trait ClockedDelayApi extends HasOperation[ClockedDelay]:
  def op(
    input:    Value,
    edge:     LTLClockEdge,
    clock:    Value,
    delay:    Long,
    length:   Option[Long],
    location: Location
  )(
    using Arena,
    Context
  ):   ClockedDelay
  extension (ref: ClockedDelay)
    def result(
      using Arena
    ): Value
end ClockedDelayApi

class Concat(val _operation: Operation)
trait ConcatApi extends HasOperation[Concat]:
  def op(
    inputs:   Seq[Value],
    location: Location
  )(
    using Arena,
    Context
  ):   Concat
  extension (ref: Concat)
    def result(
      using Arena
    ): Value
end ConcatApi

class Implication(val _operation: Operation)
trait ImplicationApi extends HasOperation[Implication]:
  def op(
    antecedent: Value,
    consequent: Value,
    location:   Location
  )(
    using Arena,
    Context
  ):   Implication
  extension (ref: Implication)
    def result(
      using Arena
    ): Value
end ImplicationApi

class Intersect(val _operation: Operation)
trait IntersectApi extends HasOperation[Intersect]:
  def op(
    inputs:   Seq[Value],
    location: Location
  )(
    using Arena,
    Context
  ):   Intersect
  extension (ref: Intersect)
    def result(
      using Arena
    ): Value
end IntersectApi

class Not(val _operation: Operation)
trait NotApi extends HasOperation[Not]:
  def op(
    input:    Value,
    location: Location
  )(
    using Arena,
    Context
  ):   Not
  extension (ref: Not)
    def result(
      using Arena
    ): Value
end NotApi

class Or(val _operation: Operation)
trait OrApi extends HasOperation[Or]:
  def op(
    inputs:   Seq[Value],
    location: Location
  )(
    using Arena,
    Context
  ):   Or
  extension (ref: Or)
    def result(
      using Arena
    ): Value
end OrApi

class Past(val _operation: Operation)
trait PastApi extends HasOperation[Past]:
  def op(
    input:    Value,
    delay:    Long,
    clock:    Value,
    location: Location
  )(
    using Arena,
    Context
  ):   Past
  extension (ref: Past)
    def result(
      using Arena
    ): Value
end PastApi

class Sampled(val _operation: Operation)
trait SampledApi extends HasOperation[Sampled]:
  def op(
    expression: Value,
    location:   Location
  )(
    using Arena,
    Context
  ):   Sampled
  extension (ref: Sampled)
    def result(
      using Arena
    ): Value
end SampledApi
