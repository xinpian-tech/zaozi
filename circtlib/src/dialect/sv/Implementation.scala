// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sv.operation

import org.llvm.circt.scalalib.capi.dialect.hw.{TypeApi as HWTypeApi, given}
import org.llvm.mlir.scalalib.capi.ir.{
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

private inline def named(
  name:  String,
  value: org.llvm.mlir.scalalib.capi.ir.Attribute
)(
  using Arena,
  Context
): NamedAttribute =
  summon[NamedAttributeApi].namedAttributeGet(name.identifierGet, value)

given RegApi with
  def op(
    elementType: Type,
    name:        String,
    init:        Option[Value],
    location:    Location
  )(
    using Arena,
    Context
  ): Reg =
    init.foreach(value => require(value.getType.equal(elementType), s"$name register initializer type mismatch"))
    Reg(
      summon[OperationApi].operationCreate(
        name = "sv.reg",
        location = location,
        namedAttributes = Seq(named("name", name.stringAttrGet)),
        operands = init.toSeq,
        resultsTypes = Some(Seq(elementType.inOutTypeGet()))
      )
    )

  extension (ref: Reg)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ReadInOutApi with
  def op(
    input:    Value,
    location: Location
  )(
    using Arena,
    Context
  ): ReadInOut =
    val inoutType = input.getType
    require(inoutType.isInOut(), "sv.read_inout input must have an !hw.inout type")
    ReadInOut(
      summon[OperationApi].operationCreate(
        name = "sv.read_inout",
        location = location,
        operands = Seq(input),
        resultsTypes = Some(Seq(inoutType.inOutTypeGetElementType()))
      )
    )

  extension (ref: ReadInOut)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given VerbatimApi with
  def op(
    formatString:  String,
    substitutions: Seq[Value],
    location:      Location
  )(
    using Arena,
    Context
  ): Verbatim =
    Verbatim(
      summon[OperationApi].operationCreate(
        name = "sv.verbatim",
        location = location,
        namedAttributes = Seq(named("format_string", formatString.stringAttrGet)),
        operands = substitutions,
        resultsTypes = Some(Seq.empty)
      )
    )

  extension (ref: Verbatim) def operation: Operation = ref._operation
end given
