// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sv.operation

import org.llvm.circt.scalalib.capi.dialect.hw.{TypeApi as HWTypeApi, given}
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
  TypeApi,
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

given InitialApi with
  def op(
    location: Location
  )(
    using Arena,
    Context
  ): Initial =
    new Initial(
      summon[OperationApi].operationCreate(
        name = "sv.initial",
        location = location,
        regionBlockTypeLocations = Seq(Seq((Seq.empty, Seq.empty))),
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: Initial)
    def operation: Operation = ref._operation
    def block(
      using Arena
    ): Block = ref.operation.getFirstRegion.getFirstBlock

given IfApi with
  def op(
    condition: Value,
    location:  Location
  )(
    using Arena,
    Context
  ): If =
    require(condition.getType.equal(1.integerTypeGet), "sv.if condition must be i1")
    new If(
      summon[OperationApi].operationCreate(
        name = "sv.if",
        location = location,
        operands = Seq(condition),
        regionBlockTypeLocations = Seq.fill(2)(Seq((Seq.empty, Seq.empty))),
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: If)
    def operation: Operation = ref._operation
    def thenBlock(
      using Arena
    ): Block = ref.operation.getRegion(0).getFirstBlock
    def elseBlock(
      using Arena
    ): Block = ref.operation.getRegion(1).getFirstBlock

given CaseApi with
  def op(
    selector: Value,
    patterns: Seq[BigInt],
    location: Location
  )(
    using Arena,
    Context
  ): Case =
    val width = selector.getType.integerTypeGetWidth
    require(patterns.distinct.size == patterns.size, "duplicate switch case")
    val attrs: Seq[Attribute] = patterns.map: value =>
      require(value >= 0 && value < (BigInt(1) << width), "case value does not fit selector")
      // SV case patterns use two bits per selector bit: 00 = zero, 01 = one.
      val encoded = (0 until width).foldLeft(BigInt(0))((bits, index) =>
        if value.testBit(index) then bits.setBit(2 * index) else bits
      )
      encoded.integerAttrGet((2 * width).integerTypeGet)
    new Case(
      summon[OperationApi].operationCreate(
        name = "sv.case",
        location = location,
        operands = Seq(selector),
        namedAttributes = Seq(
          named("caseStyle", 0L.integerAttrGet(32.integerTypeGet)),
          named("casePatterns", (attrs :+ summon[AttributeApi].unitAttrGet).arrayAttrGet)
        ),
        regionBlockTypeLocations = Seq.fill(patterns.size + 1)(Seq((Seq.empty, Seq.empty))),
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: Case)
    def operation: Operation = ref._operation
    def block(
      index: Int
    )(
      using Arena
    ): Block = ref.operation.getRegion(index).getFirstBlock

given WireApi with
  def op(
    elementType: Type,
    name:        String,
    location:    Location
  )(
    using Arena,
    Context
  ): Wire =
    new Wire(
      summon[OperationApi].operationCreate(
        name = "sv.wire",
        location = location,
        namedAttributes = Seq(named("name", name.stringAttrGet)),
        resultsTypes = Some(Seq(elementType.inOutTypeGet()))
      )
    )
  extension (ref: Wire) def operation: Operation = ref._operation

given AssignApi with
  def op(
    destination: Value,
    source:      Value,
    location:    Location
  )(
    using Arena,
    Context
  ): Assign =
    new Assign(
      summon[OperationApi].operationCreate(
        name = "sv.assign",
        location = location,
        operands = Seq(destination, source),
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: Assign) def operation: Operation = ref._operation

given BPAssignApi with
  def op(
    destination: Value,
    source:      Value,
    location:    Location
  )(
    using Arena,
    Context
  ): BPAssign =
    new BPAssign(
      summon[OperationApi].operationCreate(
        name = "sv.bpassign",
        location = location,
        operands = Seq(destination, source),
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: BPAssign) def operation: Operation = ref._operation

given ConstantStrApi with
  def op(
    value:    String,
    location: Location
  )(
    using Arena,
    Context
  ): ConstantStr =
    new ConstantStr(
      summon[OperationApi].operationCreate(
        name = "sv.constantStr",
        location = location,
        namedAttributes = Seq(named("str", value.stringAttrGet)),
        resultsTypes = Some(Seq(summon[HWTypeApi].stringTypeGet))
      )
    )
  extension (ref: ConstantStr) def operation: Operation = ref._operation

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

given PAssignApi with
  def op(
    destination: Value,
    source:      Value,
    location:    Location
  )(
    using Arena,
    Context
  ): PAssign =
    PAssign(
      summon[OperationApi].operationCreate(
        name = "sv.passign",
        location = location,
        operands = Seq(destination, source),
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: PAssign) def operation: Operation = ref._operation
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
