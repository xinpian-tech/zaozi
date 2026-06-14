// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package org.llvm.circt.scalalib.dialect.verif.operation

import org.llvm.mlir.scalalib.capi.ir.{
  Block,
  Context,
  Location,
  NamedAttribute,
  NamedAttributeApi,
  Operation,
  OperationApi,
  Type,
  Value,
  given
}

import java.lang.foreign.Arena

private inline def labelAttrs(
  label: Option[String]
)(
  using Arena,
  Context
): Seq[NamedAttribute] =
  label.map(value => summon[NamedAttributeApi].namedAttributeGet("label".identifierGet, value.stringAttrGet)).toSeq

given ContractApi with
  def op(
    inputs:      Seq[Value],
    resultTypes: Seq[Type],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Contract =
    Contract(
      summon[OperationApi].operationCreate(
        name = "verif.contract",
        location = location,
        operands = inputs,
        resultsTypes = Some(resultTypes),
        regionBlockTypeLocations = Seq(
          Seq(
            (Seq.empty, Seq.empty)
          )
        )
      )
    )
  extension (ref: Contract)
    def operation: Operation = ref._operation
    def block(
      using Arena
    ): Block = operation.getFirstRegion.getFirstBlock
    def result(
      using Arena
    ): Value = operation.getResult(0)
end given

given AssertApi with
  def op(
    input:    Value,
    label:    Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): Assert =
    Assert(
      summon[OperationApi].operationCreate(
        name = "verif.assert",
        location = location,
        namedAttributes = labelAttrs(label),
        operands = Seq(input)
      )
    )
  extension (ref: Assert) def operation: Operation = ref._operation
end given

given AssumeApi with
  def op(
    input:    Value,
    label:    Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): Assume =
    Assume(
      summon[OperationApi].operationCreate(
        name = "verif.assume",
        location = location,
        namedAttributes = labelAttrs(label),
        operands = Seq(input)
      )
    )
  extension (ref: Assume) def operation: Operation = ref._operation
end given

given CoverApi with
  def op(
    input:    Value,
    label:    Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): Cover =
    Cover(
      summon[OperationApi].operationCreate(
        name = "verif.cover",
        location = location,
        namedAttributes = labelAttrs(label),
        operands = Seq(input)
      )
    )
  extension (ref: Cover) def operation: Operation = ref._operation
end given

given RequireApi with
  def op(
    input:    Value,
    label:    Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): Require =
    Require(
      summon[OperationApi].operationCreate(
        name = "verif.require",
        location = location,
        namedAttributes = labelAttrs(label),
        operands = Seq(input)
      )
    )
  extension (ref: Require) def operation: Operation = ref._operation
end given

given EnsureApi with
  def op(
    input:    Value,
    label:    Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): Ensure =
    Ensure(
      summon[OperationApi].operationCreate(
        name = "verif.ensure",
        location = location,
        namedAttributes = labelAttrs(label),
        operands = Seq(input)
      )
    )
  extension (ref: Ensure) def operation: Operation = ref._operation
end given
