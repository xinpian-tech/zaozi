// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.comb.operation

import org.llvm.mlir.scalalib.capi.ir.{
  Context,
  Location,
  NamedAttributeApi,
  Operation,
  OperationApi,
  TypeApi,
  Value,
  given
}
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
    require(lhs.getType.equal(rhs.getType), "comb.add operands must have equal types")
    new Add(
      summon[OperationApi].operationCreate(
        name = "comb.add",
        location = location,
        operands = Seq(lhs, rhs),
        resultsTypes = Some(Seq(lhs.getType))
      )
    )
  extension (ref: Add) def operation: Operation = ref._operation

given SubApi with
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ): Sub =
    require(lhs.getType.equal(rhs.getType), "comb.sub operands must have equal types")
    new Sub(
      summon[OperationApi].operationCreate(
        name = "comb.sub",
        location = location,
        operands = Seq(lhs, rhs),
        resultsTypes = Some(Seq(lhs.getType))
      )
    )
  extension (ref: Sub) def operation: Operation = ref._operation

given AndApi with
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ): And =
    require(lhs.getType.equal(rhs.getType), "comb.and operands must have equal types")
    new And(
      summon[OperationApi].operationCreate(
        name = "comb.and",
        location = location,
        operands = Seq(lhs, rhs),
        resultsTypes = Some(Seq(lhs.getType))
      )
    )
  extension (ref: And) def operation: Operation = ref._operation

given OrApi with
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ): Or =
    require(lhs.getType.equal(rhs.getType), "comb.or operands must have equal types")
    new Or(
      summon[OperationApi].operationCreate(
        name = "comb.or",
        location = location,
        operands = Seq(lhs, rhs),
        resultsTypes = Some(Seq(lhs.getType))
      )
    )
  extension (ref: Or) def operation: Operation = ref._operation

given XorApi with
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ): Xor =
    require(lhs.getType.equal(rhs.getType), "comb.xor operands must have equal types")
    new Xor(
      summon[OperationApi].operationCreate(
        name = "comb.xor",
        location = location,
        operands = Seq(lhs, rhs),
        resultsTypes = Some(Seq(lhs.getType))
      )
    )
  extension (ref: Xor) def operation: Operation = ref._operation

given ICmpApi with
  def op(
    predicate: ICmpPredicate,
    lhs:       Value,
    rhs:       Value,
    location:  Location
  )(
    using Arena,
    Context
  ): ICmp =
    require(lhs.getType.equal(rhs.getType), "comb.icmp operands must have equal types")
    new ICmp(
      summon[OperationApi].operationCreate(
        name = "comb.icmp",
        location = location,
        operands = Seq(lhs, rhs),
        namedAttributes = Seq(
          summon[NamedAttributeApi]
            .namedAttributeGet("predicate".identifierGet, predicate.encoding.integerAttrGet(64.integerTypeGet))
        ),
        resultsTypes = Some(Seq(1.integerTypeGet))
      )
    )
  extension (ref: ICmp) def operation: Operation = ref._operation

given ConcatApi with
  def op(
    inputs:   Seq[Value],
    location: Location
  )(
    using Arena,
    Context
  ): Concat =
    require(inputs.nonEmpty, "comb.concat requires operands")
    new Concat(
      summon[OperationApi].operationCreate(
        name = "comb.concat",
        location = location,
        operands = inputs,
        resultsTypes = Some(Seq(inputs.map(_.getType.integerTypeGetWidth).sum.integerTypeGet))
      )
    )
  extension (ref: Concat) def operation: Operation = ref._operation

given ExtractApi with
  def op(
    input:    Value,
    low:      Int,
    width:    Int,
    location: Location
  )(
    using Arena,
    Context
  ): Extract =
    require(low >= 0 && width > 0 && low + width <= input.getType.integerTypeGetWidth, "invalid extraction range")
    new Extract(
      summon[OperationApi].operationCreate(
        name = "comb.extract",
        location = location,
        operands = Seq(input),
        namedAttributes = Seq(
          summon[NamedAttributeApi]
            .namedAttributeGet("lowBit".identifierGet, low.toLong.integerAttrGet(32.integerTypeGet))
        ),
        resultsTypes = Some(Seq(width.integerTypeGet))
      )
    )
  extension (ref: Extract) def operation: Operation = ref._operation

given MuxApi with
  def op(
    condition: Value,
    whenTrue:  Value,
    whenFalse: Value,
    location:  Location
  )(
    using Arena,
    Context
  ): Mux =
    require(condition.getType.equal(1.integerTypeGet), "mux condition must be i1")
    require(whenTrue.getType.equal(whenFalse.getType), "mux values must have equal types")
    new Mux(
      summon[OperationApi].operationCreate(
        name = "comb.mux",
        location = location,
        operands = Seq(condition, whenTrue, whenFalse),
        resultsTypes = Some(Seq(whenTrue.getType))
      )
    )
  extension (ref: Mux) def operation: Operation = ref._operation
