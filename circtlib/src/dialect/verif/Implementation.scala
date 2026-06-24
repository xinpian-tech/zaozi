// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package org.llvm.circt.scalalib.dialect.verif.operation

import org.llvm.mlir.scalalib.capi.ir.{
  Context,
  Location,
  NamedAttribute,
  NamedAttributeApi,
  Operation,
  OperationApi,
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
