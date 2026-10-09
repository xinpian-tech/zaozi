// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sv.operation

import org.llvm.circt.{CAPI, HWModulePort as NativeHWModulePort}
import org.llvm.circt.scalalib.capi.dialect.hw.{HWModulePort, TypeApi as HWTypeApi, given}
import org.llvm.circt.scalalib.dialect.hw.operation.{Port, PortDirection}
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

given AlwaysApi with
  def op(
    events:   Seq[EventControl],
    clocks:   Seq[Value],
    location: Location
  )(
    using Arena,
    Context
  ): Always =
    Always(
      summon[OperationApi].operationCreate(
        name = "sv.always",
        location = location,
        operands = clocks,
        namedAttributes = Seq(
          named("events", events.map(_.ordinal.toLong.integerAttrGet(32.integerTypeGet)).arrayAttrGet)
        ),
        regionBlockTypeLocations = Seq(Seq((Seq.empty, Seq.empty))),
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: Always)
    def operation: Operation = ref._operation
    def block(
      using Arena
    ): Block = ref.operation.getFirstRegion.getFirstBlock
end given

given FuncApi with
  def op(
    symbol:      String,
    ports:       Seq[Port],
    returnPort:  Option[Int],
    verilogName: Option[String],
    location:    Location
  )(
    using Arena,
    Context
  ): Func =
    val nativePorts = ports.map: port =>
      val raw = NativeHWModulePort.allocate(summon[Arena])
      NativeHWModulePort.name(raw, port.name.stringAttrGet.segment)
      NativeHWModulePort.`type`(raw, port.tpe.segment)
      NativeHWModulePort.dir(
        raw,
        port.direction match
          case PortDirection.Input  => CAPI.Input()
          case PortDirection.Output => CAPI.Output()
          case PortDirection.InOut  => CAPI.InOut()
      )
      HWModulePort(raw)
    val argumentAttrs = ports.indices.map: index =>
      val attributes =
        if returnPort.contains(index) then
          Map("sv.func.explicitly_returned" -> summon[AttributeApi].unitAttrGet)
        else Map.empty[String, Attribute]
      attributes.directoryAttrGet
    Func(
      summon[OperationApi].operationCreate(
        name = "sv.func",
        location = location,
        regionBlockTypeLocations = Seq(Seq.empty),
        namedAttributes = Seq(
          named("sym_name", symbol.stringAttrGet),
          named("sym_visibility", "private".stringAttrGet),
          named("module_type", summon[HWTypeApi].moduleTypeGet(ports.size, nativePorts).typeAttrGet),
          named("per_argument_attrs", argumentAttrs.arrayAttrGet)
        ) ++ verilogName.toSeq.map(name => named("verilogName", name.stringAttrGet)),
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: Func) def operation: Operation = ref._operation
end given

given FuncDPIImportApi with
  def op(
    callee:      String,
    linkageName: Option[String],
    location:    Location
  )(
    using Arena,
    Context
  ): FuncDPIImport =
    FuncDPIImport(
      summon[OperationApi].operationCreate(
        name = "sv.func.dpi.import",
        location = location,
        namedAttributes = Seq(named("callee", callee.flatSymbolRefAttrGet)) ++
          linkageName.toSeq.map(name => named("linkage_name", name.stringAttrGet)),
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: FuncDPIImport) def operation: Operation = ref._operation
end given

given FuncCallProceduralApi with
  def op(
    callee:      String,
    inputs:      Seq[Value],
    resultTypes: Seq[Type],
    location:    Location
  )(
    using Arena,
    Context
  ): FuncCallProcedural =
    FuncCallProcedural(
      summon[OperationApi].operationCreate(
        name = "sv.func.call.procedural",
        location = location,
        namedAttributes = Seq(named("callee", callee.flatSymbolRefAttrGet)),
        operands = inputs,
        resultsTypes = Some(resultTypes)
      )
    )
  extension (ref: FuncCallProcedural) def operation: Operation = ref._operation
end given

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
