// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sim.operation

import org.llvm.circt.scalalib.capi.dialect.sim.{TypeApi as SimTypeApi, given}
import org.llvm.mlir.scalalib.capi.ir.{
  Attribute,
  AttributeApi,
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

private inline def fstringType(
  using Arena,
  Context
): Type = summon[SimTypeApi].formatStringTypeGet

private inline def named(
  name:  String,
  value: Attribute
)(
  using Arena,
  Context
): NamedAttribute =
  summon[NamedAttributeApi].namedAttributeGet(name.identifierGet, value)

given FormatLiteralApi with
  def op(
    literal:     String,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): FormatLiteral =
    FormatLiteral(
      summon[OperationApi].operationCreate(
        name = "sim.fmt.literal",
        location = location,
        namedAttributes = Seq(named("literal", literal.stringAttrGet)),
        resultsTypes = Some(Seq(fstringType))
      )
    )
  extension (ref: FormatLiteral)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given FormatDecApi with
  def op(
    value:       Value,
    isSigned:    Boolean,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): FormatDec =
    // `isSigned` is a UnitAttr: present means signed, absent means unsigned.
    FormatDec(
      summon[OperationApi].operationCreate(
        name = "sim.fmt.dec",
        location = location,
        namedAttributes =
          if isSigned then Seq(named("isSigned", summon[AttributeApi].unitAttrGet))
          else Seq.empty,
        operands = Seq(value),
        resultsTypes = Some(Seq(fstringType))
      )
    )
  extension (ref: FormatDec)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given FormatHexApi with
  def op(
    value:       Value,
    isUppercase: Boolean,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): FormatHex =
    // `isHexUppercase` is a required BoolAttr on sim.fmt.hex.
    FormatHex(
      summon[OperationApi].operationCreate(
        name = "sim.fmt.hex",
        location = location,
        namedAttributes = Seq(named("isHexUppercase", isUppercase.boolAttrGet)),
        operands = Seq(value),
        resultsTypes = Some(Seq(fstringType))
      )
    )
  extension (ref: FormatHex)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given FormatCharApi with
  def op(
    value:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): FormatChar =
    FormatChar(
      summon[OperationApi].operationCreate(
        name = "sim.fmt.char",
        location = location,
        operands = Seq(value),
        resultsTypes = Some(Seq(fstringType))
      )
    )
  extension (ref: FormatChar)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given FormatCurrentTimeApi with
  def op(
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): FormatCurrentTime =
    FormatCurrentTime(
      summon[OperationApi].operationCreate(
        name = "sim.fmt.current_time",
        location = location,
        resultsTypes = Some(Seq(fstringType))
      )
    )
  extension (ref: FormatCurrentTime)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given FormatConcatApi with
  def op(
    inputs:      Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): FormatConcat =
    FormatConcat(
      summon[OperationApi].operationCreate(
        name = "sim.fmt.concat",
        location = location,
        operands = inputs,
        resultsTypes = Some(Seq(fstringType))
      )
    )
  extension (ref: FormatConcat)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given
