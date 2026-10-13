// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.comb.operation

import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.support.HasOperation
import java.lang.foreign.Arena

given AddApi with
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ): Add =
    Add(
      summon[OperationApi]
        .operationCreate("comb.add", location, operands = Seq(lhs, rhs), resultsTypes = Some(Seq(lhs.getType)))
    )
  extension (ref: Add)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)

given SubApi with
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ): Sub =
    Sub(
      summon[OperationApi]
        .operationCreate("comb.sub", location, operands = Seq(lhs, rhs), resultsTypes = Some(Seq(lhs.getType)))
    )
  extension (ref: Sub)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)

given MulApi with
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ): Mul =
    Mul(
      summon[OperationApi]
        .operationCreate("comb.mul", location, operands = Seq(lhs, rhs), resultsTypes = Some(Seq(lhs.getType)))
    )
  extension (ref: Mul)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)

given AndApi with
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ): And =
    And(
      summon[OperationApi]
        .operationCreate("comb.and", location, operands = Seq(lhs, rhs), resultsTypes = Some(Seq(lhs.getType)))
    )
  extension (ref: And)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)

given OrApi with
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ): Or =
    Or(
      summon[OperationApi]
        .operationCreate("comb.or", location, operands = Seq(lhs, rhs), resultsTypes = Some(Seq(lhs.getType)))
    )
  extension (ref: Or)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)

given XorApi with
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ): Xor =
    Xor(
      summon[OperationApi]
        .operationCreate("comb.xor", location, operands = Seq(lhs, rhs), resultsTypes = Some(Seq(lhs.getType)))
    )
  extension (ref: Xor)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)

given ICmpApi with
  def op(
    lhs:       Value,
    rhs:       Value,
    predicate: Long,
    location:  Location
  )(
    using Arena,
    Context
  ): ICmp =
    ICmp(
      summon[OperationApi].operationCreate(
        "comb.icmp",
        location,
        operands = Seq(lhs, rhs),
        resultsTypes = Some(Seq(1.integerTypeGet)),
        namedAttributes = Seq(
          summon[NamedAttributeApi]
            .namedAttributeGet("predicate".identifierGet, predicate.integerAttrGet(64.integerTypeGet))
        )
      )
    )
  extension (ref: ICmp)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)

given MuxApi with
  def op(
    cond:      Value,
    whenTrue:  Value,
    whenFalse: Value,
    location:  Location
  )(
    using Arena,
    Context
  ): Mux =
    Mux(
      summon[OperationApi].operationCreate(
        "comb.mux",
        location,
        operands = Seq(cond, whenTrue, whenFalse),
        resultsTypes = Some(Seq(whenTrue.getType))
      )
    )
  extension (ref: Mux)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
