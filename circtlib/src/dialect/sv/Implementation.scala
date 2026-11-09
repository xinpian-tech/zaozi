// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sv.operation

import org.llvm.circt.scalalib.dialect.sv.SVEncoding
import org.llvm.circt.scalalib.dialect.sv.attr.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{
  Attribute,
  Block,
  Context,
  Location,
  NamedAttributeApi,
  Operation,
  OperationApi,
  Type,
  Value,
  given
}
import org.llvm.circt.scalalib.capi.dialect.hw.given
import java.lang.foreign.Arena

given AliasApi with
  def op(
    aliases:     Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Alias =
    Alias(
      summon[OperationApi].operationCreate(
        name = "sv.alias",
        location = location,
        operands = aliases
      )
    )
  extension (ref: Alias) def operation: Operation = ref._operation
end given

given AlwaysApi with
  def op(
    events:      Seq[EventControl],
    clocks:      Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Always =
    require(events.size == clocks.size, "each sensitivity event needs a clock")
    Always(
      summon[OperationApi].operationCreate(
        name = "sv.always",
        location = location,
        operands = clocks,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("events".identifierGet, events.map(_.toAttribute).arrayAttrGet))
        ,
        regionBlockTypeLocations = Seq(Seq((Seq.empty, Seq.empty)))
      )
    )
  extension (ref: Always)
    def operation: Operation = ref._operation
    def block(
      using Arena
    ): Block = ref.operation.getRegion(0).getFirstBlock
end given

given AlwaysCombApi with
  def op(
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): AlwaysComb =
    AlwaysComb(
      summon[OperationApi].operationCreate(
        name = "sv.alwayscomb",
        location = location,
        regionBlockTypeLocations = Seq(Seq((Seq.empty, Seq.empty)))
      )
    )
  extension (ref: AlwaysComb)
    def operation: Operation = ref._operation
    def block(
      using Arena
    ): Block = ref.operation.getRegion(0).getFirstBlock
end given

given AlwaysFFApi with
  def op(
    clockEdge:   EventControl,
    clock:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): AlwaysFF =
    AlwaysFF(
      summon[OperationApi].operationCreate(
        name = "sv.alwaysff",
        location = location,
        operands = Seq(clock),
        namedAttributes = Seq(
          summon[NamedAttributeApi].namedAttributeGet("clockEdge".identifierGet, clockEdge.toAttribute),
          summon[NamedAttributeApi].namedAttributeGet("resetStyle".identifierGet, ResetStyle.NoReset.toAttribute)
        ),
        regionBlockTypeLocations = Seq(Seq((Seq.empty, Seq.empty)), Seq.empty)
      )
    )
  def op(
    clockEdge:   EventControl,
    clock:       Value,
    resetStyle:  ResetStyle,
    resetEdge:   EventControl,
    reset:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): AlwaysFF =
    require(resetStyle != ResetStyle.NoReset, "use the clock-only builder for an alwaysff without reset")
    AlwaysFF(
      summon[OperationApi].operationCreate(
        name = "sv.alwaysff",
        location = location,
        operands = Seq(clock, reset),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("clockEdge".identifierGet, clockEdge.toAttribute)) ++ Seq(
            namedAttributeApi.namedAttributeGet("resetStyle".identifierGet, resetStyle.toAttribute)
          ) ++ Seq(namedAttributeApi.namedAttributeGet("resetEdge".identifierGet, resetEdge.toAttribute))
        ,
        regionBlockTypeLocations = Seq.fill(2)(Seq((Seq.empty, Seq.empty)))
      )
    )
  extension (ref: AlwaysFF)
    def operation: Operation = ref._operation
    def block(
      using Arena
    ): Block = ref.operation.getRegion(0).getFirstBlock
    def resetBlock(
      using Arena
    ): Block = ref.operation.getRegion(1).getFirstBlock
end given

given ArrayIndexInOutApi with
  def op(
    input:       Value,
    index:       Value,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): ArrayIndexInOut =
    ArrayIndexInOut(
      summon[OperationApi].operationCreate(
        name = "sv.array_index_inout",
        location = location,
        operands = Seq(input) ++ Seq(index),
        resultsTypes = Some(Seq(resultType))
      )
    )
  extension (ref: ArrayIndexInOut)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given AssertApi with
  def op(
    expression:    Value,
    defer:         DeferAssert,
    label:         Option[String] = None,
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Assert =
    Assert(
      summon[OperationApi].operationCreate(
        name = "sv.assert",
        location = location,
        operands = Seq(expression) ++ substitutions,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("defer".identifierGet, defer.toAttribute)) ++ label
            .map(value => namedAttributeApi.namedAttributeGet("label".identifierGet, value.stringAttrGet))
            .toSeq ++ message
            .map(value => namedAttributeApi.namedAttributeGet("message".identifierGet, value.stringAttrGet))
            .toSeq
      )
    )
  extension (ref: Assert) def operation: Operation = ref._operation
end given

given AssertConcurrentApi with
  def op(
    event:         EventControl,
    clock:         Value,
    property:      Value,
    label:         Option[String] = None,
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): AssertConcurrent =
    AssertConcurrent(
      summon[OperationApi].operationCreate(
        name = "sv.assert.concurrent",
        location = location,
        operands = Seq(clock) ++ Seq(property) ++ substitutions,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("event".identifierGet, event.toAttribute)) ++ label
            .map(value => namedAttributeApi.namedAttributeGet("label".identifierGet, value.stringAttrGet))
            .toSeq ++ message
            .map(value => namedAttributeApi.namedAttributeGet("message".identifierGet, value.stringAttrGet))
            .toSeq
      )
    )
  extension (ref: AssertConcurrent) def operation: Operation = ref._operation
end given

given AssertPropertyApi with
  def op(
    property:    Value,
    event:       Option[EventControl] = None,
    clock:       Option[Value] = None,
    disable:     Option[Value] = None,
    label:       Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): AssertProperty =
    require(event.isDefined == clock.isDefined, "a property clock and event must be supplied together")
    AssertProperty(
      summon[OperationApi].operationCreate(
        name = "sv.assert_property",
        location = location,
        operands = Seq(property) ++ clock.toSeq ++ disable.toSeq,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          event
            .map(value => namedAttributeApi.namedAttributeGet("event".identifierGet, value.toAttribute))
            .toSeq ++ label
            .map(value => namedAttributeApi.namedAttributeGet("label".identifierGet, value.stringAttrGet))
            .toSeq ++ Seq(
            namedAttributeApi.namedAttributeGet(
              "operandSegmentSizes".identifierGet,
              Seq(1, clock.size, disable.size).denseI32ArrayGet
            )
          )
      )
    )
  extension (ref: AssertProperty) def operation: Operation = ref._operation
end given

given AssignApi with
  def op(
    dest:        Value,
    src:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Assign =
    Assign(
      summon[OperationApi].operationCreate(
        name = "sv.assign",
        location = location,
        operands = Seq(dest) ++ Seq(src)
      )
    )
  extension (ref: Assign) def operation: Operation = ref._operation
end given

given AssumeApi with
  def op(
    expression:    Value,
    defer:         DeferAssert,
    label:         Option[String] = None,
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Assume =
    Assume(
      summon[OperationApi].operationCreate(
        name = "sv.assume",
        location = location,
        operands = Seq(expression) ++ substitutions,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("defer".identifierGet, defer.toAttribute)) ++ label
            .map(value => namedAttributeApi.namedAttributeGet("label".identifierGet, value.stringAttrGet))
            .toSeq ++ message
            .map(value => namedAttributeApi.namedAttributeGet("message".identifierGet, value.stringAttrGet))
            .toSeq
      )
    )
  extension (ref: Assume) def operation: Operation = ref._operation
end given

given AssumeConcurrentApi with
  def op(
    event:         EventControl,
    clock:         Value,
    property:      Value,
    label:         Option[String] = None,
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): AssumeConcurrent =
    AssumeConcurrent(
      summon[OperationApi].operationCreate(
        name = "sv.assume.concurrent",
        location = location,
        operands = Seq(clock) ++ Seq(property) ++ substitutions,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("event".identifierGet, event.toAttribute)) ++ label
            .map(value => namedAttributeApi.namedAttributeGet("label".identifierGet, value.stringAttrGet))
            .toSeq ++ message
            .map(value => namedAttributeApi.namedAttributeGet("message".identifierGet, value.stringAttrGet))
            .toSeq
      )
    )
  extension (ref: AssumeConcurrent) def operation: Operation = ref._operation
end given

given AssumePropertyApi with
  def op(
    property:    Value,
    event:       Option[EventControl] = None,
    clock:       Option[Value] = None,
    disable:     Option[Value] = None,
    label:       Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): AssumeProperty =
    require(event.isDefined == clock.isDefined, "a property clock and event must be supplied together")
    AssumeProperty(
      summon[OperationApi].operationCreate(
        name = "sv.assume_property",
        location = location,
        operands = Seq(property) ++ clock.toSeq ++ disable.toSeq,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          event
            .map(value => namedAttributeApi.namedAttributeGet("event".identifierGet, value.toAttribute))
            .toSeq ++ label
            .map(value => namedAttributeApi.namedAttributeGet("label".identifierGet, value.stringAttrGet))
            .toSeq ++ Seq(
            namedAttributeApi.namedAttributeGet(
              "operandSegmentSizes".identifierGet,
              Seq(1, clock.size, disable.size).denseI32ArrayGet
            )
          )
      )
    )
  extension (ref: AssumeProperty) def operation: Operation = ref._operation
end given

given BindApi with
  def op(
    instance:    Attribute,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Bind =
    Bind(
      summon[OperationApi].operationCreate(
        name = "sv.bind",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("instance".identifierGet, instance))
      )
    )
  extension (ref: Bind) def operation: Operation = ref._operation
end given

given BindInterfaceApi with
  def op(
    instance:    Attribute,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BindInterface =
    BindInterface(
      summon[OperationApi].operationCreate(
        name = "sv.bind.interface",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("instance".identifierGet, instance))
      )
    )
  extension (ref: BindInterface) def operation: Operation = ref._operation
end given

given BPAssignApi with
  def op(
    dest:        Value,
    src:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BPAssign =
    BPAssign(
      summon[OperationApi].operationCreate(
        name = "sv.bpassign",
        location = location,
        operands = Seq(dest) ++ Seq(src)
      )
    )
  extension (ref: BPAssign) def operation: Operation = ref._operation
end given

given CaseApi with
  def op(
    caseStyle:           CaseStyle = CaseStyle.Case,
    cond:                Value,
    casePatterns:        Seq[Attribute],
    caseValues:          Seq[Value] = Seq.empty,
    validationQualifier: ValidationQualifier = ValidationQualifier.Plain,
    location:            Location
  )(
    using arena:         Arena,
    context:             Context
  ): Case =
    val expressionPattern = summon[org.llvm.circt.scalalib.dialect.sv.attr.AttributeApi].getCaseExprPattern
    require(
      casePatterns.count(pattern =>
        org.llvm.mlir.CAPI.mlirAttributeEqual(
          pattern.segment,
          expressionPattern.segment
        )
      ) == caseValues.size,
      "each expression case pattern needs a case value"
    )
    Case(
      summon[OperationApi].operationCreate(
        name = "sv.case",
        location = location,
        operands = Seq(cond) ++ caseValues,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("caseStyle".identifierGet, caseStyle.toAttribute)) ++ Seq(
            namedAttributeApi.namedAttributeGet("casePatterns".identifierGet, casePatterns.arrayAttrGet)
          ) ++ Seq(
            namedAttributeApi.namedAttributeGet("validationQualifier".identifierGet, validationQualifier.toAttribute)
          )
        ,
        regionBlockTypeLocations = casePatterns.map(_ => Seq((Seq.empty, Seq.empty)))
      )
    )
  extension (ref: Case)
    def operation: Operation = ref._operation
    def caseBlock(
      index: Int
    )(
      using Arena
    ): Block = ref.operation.getRegion(index).getFirstBlock
end given

given ConcatStrApi with
  def op(
    inputs:      Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): ConcatStr =
    ConcatStr(
      summon[OperationApi].operationCreate(
        name = "sv.concat_str",
        location = location,
        operands = inputs,
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: ConcatStr)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ConstantStrApi with
  def op(
    str:         String,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): ConstantStr =
    ConstantStr(
      summon[OperationApi].operationCreate(
        name = "sv.constantStr",
        location = location,
        inferredResultsTypes = Some(1),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("str".identifierGet, str.stringAttrGet))
      )
    )
  extension (ref: ConstantStr)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ConstantXApi with
  def op(
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): ConstantX =
    ConstantX(
      summon[OperationApi].operationCreate(
        name = "sv.constantX",
        location = location,
        resultsTypes = Some(Seq(resultType))
      )
    )
  extension (ref: ConstantX)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ConstantZApi with
  def op(
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): ConstantZ =
    ConstantZ(
      summon[OperationApi].operationCreate(
        name = "sv.constantZ",
        location = location,
        resultsTypes = Some(Seq(resultType))
      )
    )
  extension (ref: ConstantZ)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given CoverApi with
  def op(
    expression:  Value,
    defer:       DeferAssert,
    label:       Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Cover =
    Cover(
      summon[OperationApi].operationCreate(
        name = "sv.cover",
        location = location,
        operands = Seq(expression),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("defer".identifierGet, defer.toAttribute)) ++ label
            .map(value => namedAttributeApi.namedAttributeGet("label".identifierGet, value.stringAttrGet))
            .toSeq
      )
    )
  extension (ref: Cover) def operation: Operation = ref._operation
end given

given CoverConcurrentApi with
  def op(
    event:       EventControl,
    clock:       Value,
    property:    Value,
    label:       Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): CoverConcurrent =
    CoverConcurrent(
      summon[OperationApi].operationCreate(
        name = "sv.cover.concurrent",
        location = location,
        operands = Seq(clock) ++ Seq(property),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("event".identifierGet, event.toAttribute)) ++ label
            .map(value => namedAttributeApi.namedAttributeGet("label".identifierGet, value.stringAttrGet))
            .toSeq
      )
    )
  extension (ref: CoverConcurrent) def operation: Operation = ref._operation
end given

given CoverPropertyApi with
  def op(
    property:    Value,
    event:       Option[EventControl] = None,
    clock:       Option[Value] = None,
    disable:     Option[Value] = None,
    label:       Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): CoverProperty =
    require(event.isDefined == clock.isDefined, "a property clock and event must be supplied together")
    CoverProperty(
      summon[OperationApi].operationCreate(
        name = "sv.cover_property",
        location = location,
        operands = Seq(property) ++ clock.toSeq ++ disable.toSeq,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          event
            .map(value => namedAttributeApi.namedAttributeGet("event".identifierGet, value.toAttribute))
            .toSeq ++ label
            .map(value => namedAttributeApi.namedAttributeGet("label".identifierGet, value.stringAttrGet))
            .toSeq ++ Seq(
            namedAttributeApi.namedAttributeGet(
              "operandSegmentSizes".identifierGet,
              Seq(1, clock.size, disable.size).denseI32ArrayGet
            )
          )
      )
    )
  extension (ref: CoverProperty) def operation: Operation = ref._operation
end given

given ErrorApi with
  def op(
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Error =
    new Error(
      summon[OperationApi].operationCreate(
        name = "sv.error",
        location = location,
        operands = substitutions,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          message.map(value => namedAttributeApi.namedAttributeGet("message".identifierGet, value.stringAttrGet)).toSeq
      )
    )
  extension (ref: Error) def operation: Operation = ref._operation
end given

given ErrorProceduralApi with
  def op(
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): ErrorProcedural =
    ErrorProcedural(
      summon[OperationApi].operationCreate(
        name = "sv.error.procedural",
        location = location,
        operands = substitutions,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          message.map(value => namedAttributeApi.namedAttributeGet("message".identifierGet, value.stringAttrGet)).toSeq
      )
    )
  extension (ref: ErrorProcedural) def operation: Operation = ref._operation
end given

given ExitApi with
  def op(
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Exit =
    Exit(
      summon[OperationApi].operationCreate(
        name = "sv.exit",
        location = location
      )
    )
  extension (ref: Exit) def operation: Operation = ref._operation
end given

given FatalApi with
  def op(
    verbosity:     Verbosity = Verbosity.One,
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Fatal =
    Fatal(
      summon[OperationApi].operationCreate(
        name = "sv.fatal",
        location = location,
        operands = substitutions,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("verbosity".identifierGet, verbosity.toAttribute)) ++ message
            .map(value => namedAttributeApi.namedAttributeGet("message".identifierGet, value.stringAttrGet))
            .toSeq
      )
    )
  extension (ref: Fatal) def operation: Operation = ref._operation
end given

given FatalProceduralApi with
  def op(
    verbosity:     Verbosity = Verbosity.One,
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): FatalProcedural =
    FatalProcedural(
      summon[OperationApi].operationCreate(
        name = "sv.fatal.procedural",
        location = location,
        operands = substitutions,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("verbosity".identifierGet, verbosity.toAttribute)) ++ message
            .map(value => namedAttributeApi.namedAttributeGet("message".identifierGet, value.stringAttrGet))
            .toSeq
      )
    )
  extension (ref: FatalProcedural) def operation: Operation = ref._operation
end given

given FCloseApi with
  def op(
    fd:          Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): FClose =
    FClose(
      summon[OperationApi].operationCreate(
        name = "sv.fclose",
        location = location,
        operands = Seq(fd)
      )
    )
  extension (ref: FClose) def operation: Operation = ref._operation
end given

given FFlushApi with
  def op(
    fd:          Option[Value] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): FFlush =
    FFlush(
      summon[OperationApi].operationCreate(
        name = "sv.fflush",
        location = location,
        operands = fd.toSeq
      )
    )
  extension (ref: FFlush) def operation: Operation = ref._operation
end given

given FinishApi with
  def op(
    verbosity:   Verbosity = Verbosity.One,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Finish =
    Finish(
      summon[OperationApi].operationCreate(
        name = "sv.finish",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("verbosity".identifierGet, verbosity.toAttribute))
      )
    )
  extension (ref: Finish) def operation: Operation = ref._operation
end given

given ForApi with
  def op(
    lowerBound:       Value,
    upperBound:       Value,
    step:             Value,
    inductionVarName: String = "",
    location:         Location
  )(
    using arena:      Arena,
    context:          Context
  ): For =
    For(
      summon[OperationApi].operationCreate(
        name = "sv.for",
        location = location,
        operands = Seq(lowerBound) ++ Seq(upperBound) ++ Seq(step),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("inductionVarName".identifierGet, inductionVarName.stringAttrGet))
        ,
        regionBlockTypeLocations = Seq(Seq((Seq(lowerBound.getType), Seq(location))))
      )
    )
  extension (ref: For)
    def operation: Operation = ref._operation
    def block(
      using Arena
    ): Block = ref.operation.getFirstRegion.getFirstBlock
    def inductionVar(
      using Arena
    ): Value = ref.block.getArgument(0)
end given

given ForceApi with
  def op(
    dest:        Value,
    src:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Force =
    Force(
      summon[OperationApi].operationCreate(
        name = "sv.force",
        location = location,
        operands = Seq(dest) ++ Seq(src)
      )
    )
  extension (ref: Force) def operation: Operation = ref._operation
end given

given FuncApi with
  def op(
    symName:          String,
    symVisibility:    Option[String] = None,
    moduleType:       Type,
    perArgumentAttrs: Option[Seq[Attribute]] = None,
    inputLocs:        Option[Seq[Location]] = None,
    resultLocs:       Option[Seq[Location]] = None,
    verilogName:      Option[String] = None,
    isDeclaration:    Boolean = true,
    location:         Location
  )(
    using arena:      Arena,
    context:          Context
  ): Func =
    val inputs  = moduleType.moduleTypeGetNumInputs()
    val outputs = moduleType.moduleTypeGetNumOutputs()
    require(
      perArgumentAttrs.forall(_.size == inputs + outputs),
      "function attributes must follow all ports in declaration order"
    )
    require(inputLocs.forall(_.size == inputs), "function input locations must match its inputs")
    require(resultLocs.forall(_.size == outputs), "function result locations must match its outputs")
    Func(
      summon[OperationApi].operationCreate(
        name = "sv.func",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("sym_name".identifierGet, symName.stringAttrGet)) ++ symVisibility
            .map(value => namedAttributeApi.namedAttributeGet("sym_visibility".identifierGet, value.stringAttrGet))
            .toSeq ++ Seq(
            namedAttributeApi.namedAttributeGet("module_type".identifierGet, moduleType.typeAttrGet)
          ) ++ perArgumentAttrs
            .map(value => namedAttributeApi.namedAttributeGet("per_argument_attrs".identifierGet, value.arrayAttrGet))
            .toSeq ++ inputLocs
            .filter(_ => isDeclaration)
            .map(value =>
              namedAttributeApi.namedAttributeGet(
                "input_locs".identifierGet,
                value
                  .map(location => Attribute(org.llvm.mlir.CAPI.mlirLocationGetAttribute(arena, location.segment)))
                  .arrayAttrGet
              )
            )
            .toSeq ++ resultLocs
            .map(value =>
              namedAttributeApi.namedAttributeGet(
                "result_locs".identifierGet,
                value
                  .map(location => Attribute(org.llvm.mlir.CAPI.mlirLocationGetAttribute(arena, location.segment)))
                  .arrayAttrGet
              )
            )
            .toSeq ++ verilogName
            .map(value => namedAttributeApi.namedAttributeGet("verilogName".identifierGet, value.stringAttrGet))
            .toSeq
        ,
        regionBlockTypeLocations = Seq(
          if isDeclaration then Seq.empty
          else
            val inputTypes =
              Seq.tabulate(moduleType.moduleTypeGetNumInputs())(i => moduleType.moduleTypeGetInputType(i))
            Seq((inputTypes, inputLocs.getOrElse(inputTypes.map(_ => location))))
        )
      )
    )
  extension (ref: Func)
    def operation: Operation = ref._operation
    def block(
      using Arena
    ): Block = ref.operation.getFirstRegion.getFirstBlock
end given

given FuncCallApi with
  def op(
    callee:      String,
    inputs:      Seq[Value] = Seq.empty,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): FuncCall =
    FuncCall(
      summon[OperationApi].operationCreate(
        name = "sv.func.call",
        location = location,
        operands = inputs,
        resultsTypes = Some(Seq(resultType)),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("callee".identifierGet, callee.flatSymbolRefAttrGet))
      )
    )
  extension (ref: FuncCall)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given FuncCallProceduralApi with
  def op(
    callee:      String,
    inputs:      Seq[Value] = Seq.empty,
    resultTypes: Seq[Type] = Seq.empty,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): FuncCallProcedural =
    FuncCallProcedural(
      summon[OperationApi].operationCreate(
        name = "sv.func.call.procedural",
        location = location,
        operands = inputs,
        resultsTypes = Some(resultTypes),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("callee".identifierGet, callee.flatSymbolRefAttrGet))
      )
    )
  extension (ref: FuncCallProcedural)
    def operation: Operation = ref._operation
    def result(
      index: Int
    )(
      using Arena
    ): Value = ref.operation.getResult(index)
    def results(
      using Arena
    ): Seq[Value] = Seq.tabulate(ref.operation.getNumResults.toInt)(i => ref.operation.getResult(i))
end given

given FuncDPIImportApi with
  def op(
    callee:      String,
    linkageName: Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): FuncDPIImport =
    FuncDPIImport(
      summon[OperationApi].operationCreate(
        name = "sv.func.dpi.import",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("callee".identifierGet, callee.flatSymbolRefAttrGet)) ++ linkageName
            .map(value => namedAttributeApi.namedAttributeGet("linkage_name".identifierGet, value.stringAttrGet))
            .toSeq
      )
    )
  extension (ref: FuncDPIImport) def operation: Operation = ref._operation
end given

given FWriteApi with
  def op(
    fd:            Value,
    formatString:  String,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): FWrite =
    FWrite(
      summon[OperationApi].operationCreate(
        name = "sv.fwrite",
        location = location,
        operands = Seq(fd) ++ substitutions,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("format_string".identifierGet, formatString.stringAttrGet))
      )
    )
  extension (ref: FWrite) def operation: Operation = ref._operation
end given

given GenerateApi with
  def op(
    symName:     String,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Generate =
    Generate(
      summon[OperationApi].operationCreate(
        name = "sv.generate",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("sym_name".identifierGet, symName.stringAttrGet))
        ,
        regionBlockTypeLocations = Seq(Seq((Seq.empty, Seq.empty)))
      )
    )
  extension (ref: Generate)
    def operation: Operation = ref._operation
    def block(
      using Arena
    ): Block = ref.operation.getRegion(0).getFirstBlock
end given

given GenerateCaseApi with
  def op(
    cond:         Attribute,
    casePatterns: Seq[Attribute],
    caseNames:    Seq[String],
    location:     Location
  )(
    using arena:  Arena,
    context:      Context
  ): GenerateCase =
    require(casePatterns.size == caseNames.size, "each generate case needs a name")
    GenerateCase(
      summon[OperationApi].operationCreate(
        name = "sv.generate.case",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("cond".identifierGet, cond)) ++ Seq(
            namedAttributeApi.namedAttributeGet("casePatterns".identifierGet, casePatterns.arrayAttrGet)
          ) ++ Seq(
            namedAttributeApi.namedAttributeGet("caseNames".identifierGet, caseNames.map(_.stringAttrGet).arrayAttrGet)
          )
        ,
        regionBlockTypeLocations = casePatterns.map(_ => Seq((Seq.empty, Seq.empty)))
      )
    )
  extension (ref: GenerateCase)
    def operation: Operation = ref._operation
    def caseBlock(
      index: Int
    )(
      using Arena
    ): Block = ref.operation.getRegion(index).getFirstBlock
end given

given GenerateForApi with
  def op(
    lowerBound:       Attribute,
    upperBound:       Attribute,
    step:             Attribute,
    genBlockName:     String,
    inductionVarName: Option[String] = None,
    location:         Location
  )(
    using arena:      Arena,
    context:          Context
  ): GenerateFor =
    GenerateFor(
      summon[OperationApi].operationCreate(
        name = "sv.generate.for",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("lowerBound".identifierGet, lowerBound)) ++ Seq(
            namedAttributeApi.namedAttributeGet("upperBound".identifierGet, upperBound)
          ) ++ Seq(namedAttributeApi.namedAttributeGet("step".identifierGet, step)) ++ Seq(
            namedAttributeApi.namedAttributeGet("genBlockName".identifierGet, genBlockName.stringAttrGet)
          ) ++ inductionVarName
            .map(value => namedAttributeApi.namedAttributeGet("inductionVarName".identifierGet, value.stringAttrGet))
            .toSeq
        ,
        regionBlockTypeLocations = Seq(Seq((Seq(lowerBound.getType), Seq(location))))
      )
    )
  extension (ref: GenerateFor)
    def operation: Operation = ref._operation
    def block(
      using Arena
    ): Block = ref.operation.getFirstRegion.getFirstBlock
    def inductionVar(
      using Arena
    ): Value = ref.block.getArgument(0)
end given

given IfApi with
  def op(
    cond:        Value,
    hasElse:     Boolean = false,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): If =
    If(
      summon[OperationApi].operationCreate(
        name = "sv.if",
        location = location,
        operands = Seq(cond),
        regionBlockTypeLocations =
          Seq(Seq((Seq.empty, Seq.empty))) ++ Seq(if hasElse then Seq((Seq.empty, Seq.empty)) else Seq.empty)
      )
    )
  extension (ref: If)
    def operation: Operation = ref._operation
    def condBlock(
      using Arena
    ): Block = ref.operation.getRegion(0).getFirstBlock
    def elseBlock(
      using Arena
    ): Block = ref.operation.getRegion(1).getFirstBlock
end given

given IfDefApi with
  def op(
    cond:        Attribute,
    hasElse:     Boolean = false,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): IfDef =
    IfDef(
      summon[OperationApi].operationCreate(
        name = "sv.ifdef",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("cond".identifierGet, cond))
        ,
        regionBlockTypeLocations =
          Seq(Seq((Seq.empty, Seq.empty))) ++ Seq(if hasElse then Seq((Seq.empty, Seq.empty)) else Seq.empty)
      )
    )
  extension (ref: IfDef)
    def operation: Operation = ref._operation
    def condBlock(
      using Arena
    ): Block = ref.operation.getRegion(0).getFirstBlock
    def elseBlock(
      using Arena
    ): Block = ref.operation.getRegion(1).getFirstBlock
end given

given IfDefProceduralApi with
  def op(
    cond:        Attribute,
    hasElse:     Boolean = false,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): IfDefProcedural =
    IfDefProcedural(
      summon[OperationApi].operationCreate(
        name = "sv.ifdef.procedural",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("cond".identifierGet, cond))
        ,
        regionBlockTypeLocations =
          Seq(Seq((Seq.empty, Seq.empty))) ++ Seq(if hasElse then Seq((Seq.empty, Seq.empty)) else Seq.empty)
      )
    )
  extension (ref: IfDefProcedural)
    def operation: Operation = ref._operation
    def condBlock(
      using Arena
    ): Block = ref.operation.getRegion(0).getFirstBlock
    def elseBlock(
      using Arena
    ): Block = ref.operation.getRegion(1).getFirstBlock
end given

given IncludeApi with
  def op(
    style:       IncludeStyle,
    target:      String,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Include =
    Include(
      summon[OperationApi].operationCreate(
        name = "sv.include",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("style".identifierGet, style.toAttribute)) ++ Seq(
            namedAttributeApi.namedAttributeGet("target".identifierGet, target.stringAttrGet)
          )
      )
    )
  extension (ref: Include) def operation: Operation = ref._operation
end given

given IndexedPartSelectApi with
  def op(
    input:       Value,
    base:        Value,
    width:       Int,
    decrement:   Boolean = false,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): IndexedPartSelect =
    IndexedPartSelect(
      summon[OperationApi].operationCreate(
        name = "sv.indexed_part_select",
        location = location,
        operands = Seq(input) ++ Seq(base),
        inferredResultsTypes = Some(1),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(
            namedAttributeApi.namedAttributeGet("width".identifierGet, width.toLong.integerAttrGet(32.integerTypeGet))
          ) ++ Option
            .when(decrement)(
              namedAttributeApi.namedAttributeGet(
                "decrement".identifierGet,
                summon[org.llvm.mlir.scalalib.capi.ir.AttributeApi].unitAttrGet
              )
            )
            .toSeq
      )
    )
  extension (ref: IndexedPartSelect)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given IndexedPartSelectInOutApi with
  def op(
    input:       Value,
    base:        Value,
    width:       Int,
    decrement:   Boolean = false,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): IndexedPartSelectInOut =
    IndexedPartSelectInOut(
      summon[OperationApi].operationCreate(
        name = "sv.indexed_part_select_inout",
        location = location,
        operands = Seq(input) ++ Seq(base),
        inferredResultsTypes = Some(1),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(
            namedAttributeApi.namedAttributeGet("width".identifierGet, width.toLong.integerAttrGet(32.integerTypeGet))
          ) ++ Option
            .when(decrement)(
              namedAttributeApi.namedAttributeGet(
                "decrement".identifierGet,
                summon[org.llvm.mlir.scalalib.capi.ir.AttributeApi].unitAttrGet
              )
            )
            .toSeq
      )
    )
  extension (ref: IndexedPartSelectInOut)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given InfoApi with
  def op(
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Info =
    Info(
      summon[OperationApi].operationCreate(
        name = "sv.info",
        location = location,
        operands = substitutions,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          message.map(value => namedAttributeApi.namedAttributeGet("message".identifierGet, value.stringAttrGet)).toSeq
      )
    )
  extension (ref: Info) def operation: Operation = ref._operation
end given

given InfoProceduralApi with
  def op(
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): InfoProcedural =
    InfoProcedural(
      summon[OperationApi].operationCreate(
        name = "sv.info.procedural",
        location = location,
        operands = substitutions,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          message.map(value => namedAttributeApi.namedAttributeGet("message".identifierGet, value.stringAttrGet)).toSeq
      )
    )
  extension (ref: InfoProcedural) def operation: Operation = ref._operation
end given

given InitialApi with
  def op(
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Initial =
    Initial(
      summon[OperationApi].operationCreate(
        name = "sv.initial",
        location = location,
        regionBlockTypeLocations = Seq(Seq((Seq.empty, Seq.empty)))
      )
    )
  extension (ref: Initial)
    def operation: Operation = ref._operation
    def block(
      using Arena
    ): Block = ref.operation.getRegion(0).getFirstBlock
end given

given InterfaceApi with
  def op(
    symName:       String,
    symVisibility: Option[String] = None,
    comment:       Option[String] = None,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Interface =
    Interface(
      summon[OperationApi].operationCreate(
        name = "sv.interface",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("sym_name".identifierGet, symName.stringAttrGet)) ++ symVisibility
            .map(value => namedAttributeApi.namedAttributeGet("sym_visibility".identifierGet, value.stringAttrGet))
            .toSeq ++ comment
            .map(value => namedAttributeApi.namedAttributeGet("comment".identifierGet, value.stringAttrGet))
            .toSeq
        ,
        regionBlockTypeLocations = Seq(Seq((Seq.empty, Seq.empty)))
      )
    )
  extension (ref: Interface)
    def operation: Operation = ref._operation
    def block(
      using Arena
    ): Block = ref.operation.getRegion(0).getFirstBlock
end given

given InterfaceInstanceApi with
  def op(
    name:        String,
    innerSym:    Option[Attribute] = None,
    doNotPrint:  Boolean = false,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): InterfaceInstance =
    InterfaceInstance(
      summon[OperationApi].operationCreate(
        name = "sv.interface.instance",
        location = location,
        resultsTypes = Some(Seq(resultType)),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("name".identifierGet, name.stringAttrGet)) ++ innerSym
            .map(value => namedAttributeApi.namedAttributeGet("inner_sym".identifierGet, value))
            .toSeq ++ Option
            .when(doNotPrint)(
              namedAttributeApi.namedAttributeGet(
                "doNotPrint".identifierGet,
                summon[org.llvm.mlir.scalalib.capi.ir.AttributeApi].unitAttrGet
              )
            )
            .toSeq
      )
    )
  extension (ref: InterfaceInstance)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given InterfaceModportApi with
  def op(
    symName:       String,
    symVisibility: Option[String] = None,
    ports:         Seq[Attribute],
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): InterfaceModport =
    InterfaceModport(
      summon[OperationApi].operationCreate(
        name = "sv.interface.modport",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("sym_name".identifierGet, symName.stringAttrGet)) ++ symVisibility
            .map(value => namedAttributeApi.namedAttributeGet("sym_visibility".identifierGet, value.stringAttrGet))
            .toSeq ++ Seq(namedAttributeApi.namedAttributeGet("ports".identifierGet, ports.arrayAttrGet))
      )
    )
  extension (ref: InterfaceModport) def operation: Operation = ref._operation
end given

given InterfaceSignalApi with
  def op(
    symName:       String,
    symVisibility: Option[String] = None,
    tpe:           Type,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): InterfaceSignal =
    InterfaceSignal(
      summon[OperationApi].operationCreate(
        name = "sv.interface.signal",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("sym_name".identifierGet, symName.stringAttrGet)) ++ symVisibility
            .map(value => namedAttributeApi.namedAttributeGet("sym_visibility".identifierGet, value.stringAttrGet))
            .toSeq ++ Seq(namedAttributeApi.namedAttributeGet("type".identifierGet, tpe.typeAttrGet))
      )
    )
  extension (ref: InterfaceSignal) def operation: Operation = ref._operation
end given

given AssignInterfaceSignalApi with
  def op(
    iface:       Value,
    signalName:  String,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): AssignInterfaceSignal =
    AssignInterfaceSignal(
      summon[OperationApi].operationCreate(
        name = "sv.interface.signal.assign",
        location = location,
        operands = Seq(iface) ++ Seq(rhs),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("signalName".identifierGet, signalName.flatSymbolRefAttrGet))
      )
    )
  extension (ref: AssignInterfaceSignal) def operation: Operation = ref._operation
end given

given ReadInterfaceSignalApi with
  def op(
    iface:       Value,
    signalName:  String,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): ReadInterfaceSignal =
    ReadInterfaceSignal(
      summon[OperationApi].operationCreate(
        name = "sv.interface.signal.read",
        location = location,
        operands = Seq(iface),
        resultsTypes = Some(Seq(resultType)),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("signalName".identifierGet, signalName.flatSymbolRefAttrGet))
      )
    )
  extension (ref: ReadInterfaceSignal)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given LocalParamApi with
  def op(
    value:       Attribute,
    name:        String,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): LocalParam =
    LocalParam(
      summon[OperationApi].operationCreate(
        name = "sv.localparam",
        location = location,
        resultsTypes = Some(Seq(value.getType)),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("value".identifierGet, value)) ++ Seq(
            namedAttributeApi.namedAttributeGet("name".identifierGet, name.stringAttrGet)
          )
      )
    )
  extension (ref: LocalParam)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given LogicApi with
  def op(
    elementType: Type,
    name:        String = "",
    innerSym:    Option[Attribute] = None,
    comment:     Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Logic =
    Logic(
      summon[OperationApi].operationCreate(
        name = "sv.logic",
        location = location,
        resultsTypes = Some(Seq(elementType.inOutTypeGet())),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("name".identifierGet, name.stringAttrGet)) ++ innerSym
            .map(value => namedAttributeApi.namedAttributeGet("inner_sym".identifierGet, value))
            .toSeq ++ comment
            .map(value => namedAttributeApi.namedAttributeGet("comment".identifierGet, value.stringAttrGet))
            .toSeq
      )
    )
  extension (ref: Logic)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given MacroDeclApi with
  def op(
    symName:       String,
    symVisibility: Option[String] = None,
    args:          Option[Seq[String]] = None,
    verilogName:   Option[String] = None,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): MacroDecl =
    MacroDecl(
      summon[OperationApi].operationCreate(
        name = "sv.macro.decl",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("sym_name".identifierGet, symName.stringAttrGet)) ++ symVisibility
            .map(value => namedAttributeApi.namedAttributeGet("sym_visibility".identifierGet, value.stringAttrGet))
            .toSeq ++ args
            .map(value =>
              namedAttributeApi.namedAttributeGet("args".identifierGet, value.map(_.stringAttrGet).arrayAttrGet)
            )
            .toSeq ++ verilogName
            .map(value => namedAttributeApi.namedAttributeGet("verilogName".identifierGet, value.stringAttrGet))
            .toSeq
      )
    )
  extension (ref: MacroDecl) def operation: Operation = ref._operation
end given

given MacroDefApi with
  def op(
    macroName:    String,
    formatString: String,
    symbols:      Seq[Attribute] = Seq.empty,
    location:     Location
  )(
    using arena:  Arena,
    context:      Context
  ): MacroDef =
    MacroDef(
      summon[OperationApi].operationCreate(
        name = "sv.macro.def",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("macroName".identifierGet, macroName.flatSymbolRefAttrGet)) ++ Seq(
            namedAttributeApi.namedAttributeGet("format_string".identifierGet, formatString.stringAttrGet)
          ) ++ Seq(namedAttributeApi.namedAttributeGet("symbols".identifierGet, symbols.arrayAttrGet))
      )
    )
  extension (ref: MacroDef) def operation: Operation = ref._operation
end given

given MacroErrorApi with
  def op(
    message:     Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): MacroError =
    MacroError(
      summon[OperationApi].operationCreate(
        name = "sv.macro.error",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          message.map(value => namedAttributeApi.namedAttributeGet("message".identifierGet, value.stringAttrGet)).toSeq
      )
    )
  extension (ref: MacroError) def operation: Operation = ref._operation
end given

given MacroRefApi with
  def op(
    macroName:   String,
    inputs:      Seq[Value] = Seq.empty,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): MacroRef =
    MacroRef(
      summon[OperationApi].operationCreate(
        name = "sv.macro.ref",
        location = location,
        operands = inputs,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("macroName".identifierGet, macroName.flatSymbolRefAttrGet))
      )
    )
  extension (ref: MacroRef) def operation: Operation = ref._operation
end given

given MacroRefExprApi with
  def op(
    macroName:   String,
    inputs:      Seq[Value] = Seq.empty,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): MacroRefExpr =
    MacroRefExpr(
      summon[OperationApi].operationCreate(
        name = "sv.macro.ref.expr",
        location = location,
        operands = inputs,
        resultsTypes = Some(Seq(resultType)),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("macroName".identifierGet, macroName.flatSymbolRefAttrGet))
      )
    )
  extension (ref: MacroRefExpr)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given MacroRefExprSEApi with
  def op(
    macroName:   String,
    inputs:      Seq[Value] = Seq.empty,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): MacroRefExprSE =
    MacroRefExprSE(
      summon[OperationApi].operationCreate(
        name = "sv.macro.ref.expr.se",
        location = location,
        operands = inputs,
        resultsTypes = Some(Seq(resultType)),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("macroName".identifierGet, macroName.flatSymbolRefAttrGet))
      )
    )
  extension (ref: MacroRefExprSE)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given GetModportApi with
  def op(
    iface:       Value,
    field:       String,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): GetModport =
    GetModport(
      summon[OperationApi].operationCreate(
        name = "sv.modport.get",
        location = location,
        operands = Seq(iface),
        resultsTypes = Some(Seq(resultType)),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("field".identifierGet, field.flatSymbolRefAttrGet))
      )
    )
  extension (ref: GetModport)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given DepositApi with
  def op(
    dest:        Value,
    src:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Deposit =
    Deposit(
      summon[OperationApi].operationCreate(
        name = "sv.nonstandard.deposit",
        location = location,
        operands = Seq(dest) ++ Seq(src)
      )
    )
  extension (ref: Deposit) def operation: Operation = ref._operation
end given

given OrderedOutputApi with
  def op(
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): OrderedOutput =
    OrderedOutput(
      summon[OperationApi].operationCreate(
        name = "sv.ordered",
        location = location,
        regionBlockTypeLocations = Seq(Seq((Seq.empty, Seq.empty)))
      )
    )
  extension (ref: OrderedOutput)
    def operation: Operation = ref._operation
    def block(
      using Arena
    ): Block = ref.operation.getRegion(0).getFirstBlock
end given

given PackageApi with
  def op(
    symName:       String,
    symVisibility: Option[String] = None,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Package =
    new Package(
      summon[OperationApi].operationCreate(
        name = "sv.package",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("sym_name".identifierGet, symName.stringAttrGet)) ++ symVisibility
            .map(value => namedAttributeApi.namedAttributeGet("sym_visibility".identifierGet, value.stringAttrGet))
            .toSeq
        ,
        regionBlockTypeLocations = Seq(Seq((Seq.empty, Seq.empty)))
      )
    )
  extension (ref: Package)
    def operation: Operation = ref._operation
    def block(
      using Arena
    ): Block = ref.operation.getRegion(0).getFirstBlock
end given

given PAssignApi with
  def op(
    dest:        Value,
    src:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): PAssign =
    PAssign(
      summon[OperationApi].operationCreate(
        name = "sv.passign",
        location = location,
        operands = Seq(dest) ++ Seq(src)
      )
    )
  extension (ref: PAssign) def operation: Operation = ref._operation
end given

given ReadInOutApi with
  def op(
    input:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): ReadInOut =
    ReadInOut(
      summon[OperationApi].operationCreate(
        name = "sv.read_inout",
        location = location,
        operands = Seq(input),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: ReadInOut)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ReadMemApi with
  def op(
    dest:        Value,
    filename:    String,
    base:        MemoryBase,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): ReadMem =
    ReadMem(
      summon[OperationApi].operationCreate(
        name = "sv.readmem",
        location = location,
        operands = Seq(dest),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("filename".identifierGet, filename.stringAttrGet)) ++ Seq(
            namedAttributeApi.namedAttributeGet("base".identifierGet, base.toAttribute)
          )
      )
    )
  extension (ref: ReadMem) def operation: Operation = ref._operation
end given

given RegApi with
  def op(
    elementType: Type,
    name:        String = "",
    init:        Option[Value] = None,
    innerSym:    Option[Attribute] = None,
    comment:     Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Reg =
    Reg(
      summon[OperationApi].operationCreate(
        name = "sv.reg",
        location = location,
        operands = init.toSeq,
        resultsTypes = Some(Seq(elementType.inOutTypeGet())),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("name".identifierGet, name.stringAttrGet)) ++ innerSym
            .map(value => namedAttributeApi.namedAttributeGet("inner_sym".identifierGet, value))
            .toSeq ++ comment
            .map(value => namedAttributeApi.namedAttributeGet("comment".identifierGet, value.stringAttrGet))
            .toSeq
      )
    )
  extension (ref: Reg)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ReleaseApi with
  def op(
    dest:        Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Release =
    Release(
      summon[OperationApi].operationCreate(
        name = "sv.release",
        location = location,
        operands = Seq(dest)
      )
    )
  extension (ref: Release) def operation: Operation = ref._operation
end given

given ReserveNamesApi with
  def op(
    reservedNames: Seq[String],
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): ReserveNames =
    ReserveNames(
      summon[OperationApi].operationCreate(
        name = "sv.reserve_names",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(
            namedAttributeApi.namedAttributeGet(
              "reservedNames".identifierGet,
              reservedNames.map(_.stringAttrGet).arrayAttrGet
            )
          )
      )
    )
  extension (ref: ReserveNames) def operation: Operation = ref._operation
end given

given ReturnApi with
  def op(
    values:      Seq[Value] = Seq.empty,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Return =
    Return(
      summon[OperationApi].operationCreate(
        name = "sv.return",
        location = location,
        operands = values
      )
    )
  extension (ref: Return) def operation: Operation = ref._operation
end given

given SFormatFApi with
  def op(
    formatString:  String,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): SFormatF =
    SFormatF(
      summon[OperationApi].operationCreate(
        name = "sv.sformatf",
        location = location,
        operands = substitutions,
        inferredResultsTypes = Some(1),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("format_string".identifierGet, formatString.stringAttrGet))
      )
    )
  extension (ref: SFormatF)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given StopApi with
  def op(
    verbosity:   Verbosity = Verbosity.One,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Stop =
    Stop(
      summon[OperationApi].operationCreate(
        name = "sv.stop",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("verbosity".identifierGet, verbosity.toAttribute))
      )
    )
  extension (ref: Stop) def operation: Operation = ref._operation
end given

given StructFieldInOutApi with
  def op(
    input:       Value,
    field:       String,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): StructFieldInOut =
    StructFieldInOut(
      summon[OperationApi].operationCreate(
        name = "sv.struct_field_inout",
        location = location,
        operands = Seq(input),
        inferredResultsTypes = Some(1),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("field".identifierGet, field.stringAttrGet))
      )
    )
  extension (ref: StructFieldInOut)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given SystemFunctionApi with
  def op(
    fnName:      String,
    args:        Seq[Value] = Seq.empty,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): SystemFunction =
    SystemFunction(
      summon[OperationApi].operationCreate(
        name = "sv.system",
        location = location,
        operands = args,
        resultsTypes = Some(Seq(resultType)),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("fnName".identifierGet, fnName.stringAttrGet))
      )
    )
  extension (ref: SystemFunction)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given SampledApi with
  def op(
    expression:  Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Sampled =
    Sampled(
      summon[OperationApi].operationCreate(
        name = "sv.system.sampled",
        location = location,
        operands = Seq(expression),
        resultsTypes = Some(Seq(expression.getType))
      )
    )
  extension (ref: Sampled)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given STimeApi with
  def op(
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): STime =
    STime(
      summon[OperationApi].operationCreate(
        name = "sv.system.stime",
        location = location,
        resultsTypes = Some(Seq(32.integerTypeGet))
      )
    )
  extension (ref: STime)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given TimeApi with
  def op(
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Time =
    Time(
      summon[OperationApi].operationCreate(
        name = "sv.system.time",
        location = location,
        resultsTypes = Some(Seq(64.integerTypeGet))
      )
    )
  extension (ref: Time)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given UnpackedArrayCreateApi with
  def op(
    inputs:      Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): UnpackedArrayCreate =
    require(inputs.nonEmpty, "an unpacked array constructor needs at least one element")
    op(inputs, SVEncoding.tpe(s"!hw.uarray<${inputs.size}x${SVEncoding.show(inputs.head.getType)}>"), location)

  def op(
    inputs:      Seq[Value],
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): UnpackedArrayCreate =
    UnpackedArrayCreate(
      summon[OperationApi].operationCreate(
        name = "sv.unpacked_array_create",
        location = location,
        operands = inputs,
        resultsTypes = Some(Seq(resultType))
      )
    )
  extension (ref: UnpackedArrayCreate)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given UnpackedOpenArrayCastApi with
  def op(
    input:       Value,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): UnpackedOpenArrayCast =
    UnpackedOpenArrayCast(
      summon[OperationApi].operationCreate(
        name = "sv.unpacked_open_array_cast",
        location = location,
        operands = Seq(input),
        resultsTypes = Some(Seq(resultType))
      )
    )
  extension (ref: UnpackedOpenArrayCast)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given VerbatimApi with
  def op(
    formatString:  String,
    substitutions: Seq[Value] = Seq.empty,
    symbols:       Seq[Attribute] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Verbatim =
    Verbatim(
      summon[OperationApi].operationCreate(
        name = "sv.verbatim",
        location = location,
        operands = substitutions,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("format_string".identifierGet, formatString.stringAttrGet)) ++ Seq(
            namedAttributeApi.namedAttributeGet("symbols".identifierGet, symbols.arrayAttrGet)
          )
      )
    )
  extension (ref: Verbatim) def operation: Operation = ref._operation
end given

given VerbatimExprApi with
  def op(
    formatString:  String,
    substitutions: Seq[Value] = Seq.empty,
    symbols:       Seq[Attribute] = Seq.empty,
    resultType:    Type,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): VerbatimExpr =
    VerbatimExpr(
      summon[OperationApi].operationCreate(
        name = "sv.verbatim.expr",
        location = location,
        operands = substitutions,
        resultsTypes = Some(Seq(resultType)),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("format_string".identifierGet, formatString.stringAttrGet)) ++ Seq(
            namedAttributeApi.namedAttributeGet("symbols".identifierGet, symbols.arrayAttrGet)
          )
      )
    )
  extension (ref: VerbatimExpr)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given VerbatimExprSEApi with
  def op(
    formatString:  String,
    substitutions: Seq[Value] = Seq.empty,
    symbols:       Seq[Attribute] = Seq.empty,
    resultType:    Type,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): VerbatimExprSE =
    VerbatimExprSE(
      summon[OperationApi].operationCreate(
        name = "sv.verbatim.expr.se",
        location = location,
        operands = substitutions,
        resultsTypes = Some(Seq(resultType)),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("format_string".identifierGet, formatString.stringAttrGet)) ++ Seq(
            namedAttributeApi.namedAttributeGet("symbols".identifierGet, symbols.arrayAttrGet)
          )
      )
    )
  extension (ref: VerbatimExprSE)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given SVVerbatimModuleApi with
  def op(
    symName:       String,
    symVisibility: Option[String] = None,
    moduleType:    Type,
    perPortAttrs:  Option[Seq[Attribute]] = None,
    portLocs:      Option[Seq[Location]] = None,
    parameters:    Seq[Attribute],
    source:        String,
    verilogName:   Option[String] = None,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): SVVerbatimModule =
    val ports = moduleType.moduleTypeGetNumInputs() + moduleType.moduleTypeGetNumOutputs()
    require(perPortAttrs.forall(_.size == ports), "module attributes must follow all ports in declaration order")
    require(portLocs.forall(_.size == ports), "module locations must follow all ports in declaration order")
    SVVerbatimModule(
      summon[OperationApi].operationCreate(
        name = "sv.verbatim.module",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("sym_name".identifierGet, symName.stringAttrGet)) ++ symVisibility
            .map(value => namedAttributeApi.namedAttributeGet("sym_visibility".identifierGet, value.stringAttrGet))
            .toSeq ++ Seq(
            namedAttributeApi.namedAttributeGet("module_type".identifierGet, moduleType.typeAttrGet)
          ) ++ perPortAttrs
            .map(value => namedAttributeApi.namedAttributeGet("per_port_attrs".identifierGet, value.arrayAttrGet))
            .toSeq ++ portLocs
            .map(value =>
              namedAttributeApi.namedAttributeGet(
                "port_locs".identifierGet,
                value
                  .map(location => Attribute(org.llvm.mlir.CAPI.mlirLocationGetAttribute(arena, location.segment)))
                  .arrayAttrGet
              )
            )
            .toSeq ++ Seq(
            namedAttributeApi.namedAttributeGet("parameters".identifierGet, parameters.arrayAttrGet)
          ) ++ Seq(
            namedAttributeApi.namedAttributeGet("source".identifierGet, source.flatSymbolRefAttrGet)
          ) ++ verilogName
            .map(value => namedAttributeApi.namedAttributeGet("verilogName".identifierGet, value.stringAttrGet))
            .toSeq
      )
    )
  extension (ref: SVVerbatimModule) def operation: Operation = ref._operation
end given

given SVVerbatimSourceApi with
  def op(
    symName:         String,
    symVisibility:   Option[String] = None,
    content:         String,
    outputFile:      Attribute,
    parameters:      Seq[Attribute],
    additionalFiles: Option[Seq[Attribute]] = None,
    verilogName:     String,
    location:        Location
  )(
    using arena:     Arena,
    context:         Context
  ): SVVerbatimSource =
    SVVerbatimSource(
      summon[OperationApi].operationCreate(
        name = "sv.verbatim.source",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("sym_name".identifierGet, symName.stringAttrGet)) ++ symVisibility
            .map(value => namedAttributeApi.namedAttributeGet("sym_visibility".identifierGet, value.stringAttrGet))
            .toSeq ++ Seq(namedAttributeApi.namedAttributeGet("content".identifierGet, content.stringAttrGet)) ++ Seq(
            namedAttributeApi.namedAttributeGet("output_file".identifierGet, outputFile)
          ) ++ Seq(
            namedAttributeApi.namedAttributeGet("parameters".identifierGet, parameters.arrayAttrGet)
          ) ++ additionalFiles
            .map(value => namedAttributeApi.namedAttributeGet("additional_files".identifierGet, value.arrayAttrGet))
            .toSeq ++ Seq(namedAttributeApi.namedAttributeGet("verilogName".identifierGet, verilogName.stringAttrGet))
      )
    )
  extension (ref: SVVerbatimSource) def operation: Operation = ref._operation
end given

given WarningApi with
  def op(
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Warning =
    Warning(
      summon[OperationApi].operationCreate(
        name = "sv.warning",
        location = location,
        operands = substitutions,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          message.map(value => namedAttributeApi.namedAttributeGet("message".identifierGet, value.stringAttrGet)).toSeq
      )
    )
  extension (ref: Warning) def operation: Operation = ref._operation
end given

given WarningProceduralApi with
  def op(
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): WarningProcedural =
    WarningProcedural(
      summon[OperationApi].operationCreate(
        name = "sv.warning.procedural",
        location = location,
        operands = substitutions,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          message.map(value => namedAttributeApi.namedAttributeGet("message".identifierGet, value.stringAttrGet)).toSeq
      )
    )
  extension (ref: WarningProcedural) def operation: Operation = ref._operation
end given

given WireApi with
  def op(
    elementType: Type,
    name:        String = "",
    innerSym:    Option[Attribute] = None,
    comment:     Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Wire =
    Wire(
      summon[OperationApi].operationCreate(
        name = "sv.wire",
        location = location,
        resultsTypes = Some(Seq(elementType.inOutTypeGet())),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("name".identifierGet, name.stringAttrGet)) ++ innerSym
            .map(value => namedAttributeApi.namedAttributeGet("inner_sym".identifierGet, value))
            .toSeq ++ comment
            .map(value => namedAttributeApi.namedAttributeGet("comment".identifierGet, value.stringAttrGet))
            .toSeq
      )
    )
  extension (ref: Wire)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given WriteApi with
  def op(
    formatString:  String,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Write =
    Write(
      summon[OperationApi].operationCreate(
        name = "sv.write",
        location = location,
        operands = substitutions,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("format_string".identifierGet, formatString.stringAttrGet))
      )
    )
  extension (ref: Write) def operation: Operation = ref._operation
end given

given XMRApi with
  def op(
    isRooted:    Boolean = false,
    path:        Seq[String],
    terminal:    String,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): XMR =
    XMR(
      summon[OperationApi].operationCreate(
        name = "sv.xmr",
        location = location,
        resultsTypes = Some(Seq(resultType)),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Option
            .when(isRooted)(
              namedAttributeApi.namedAttributeGet(
                "isRooted".identifierGet,
                summon[org.llvm.mlir.scalalib.capi.ir.AttributeApi].unitAttrGet
              )
            )
            .toSeq ++ Seq(
            namedAttributeApi.namedAttributeGet("path".identifierGet, path.map(_.stringAttrGet).arrayAttrGet)
          ) ++ Seq(namedAttributeApi.namedAttributeGet("terminal".identifierGet, terminal.stringAttrGet))
      )
    )
  extension (ref: XMR)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given XMRRefApi with
  def op(
    reference:      String,
    verbatimSuffix: String = "",
    resultType:     Type,
    location:       Location
  )(
    using arena:    Arena,
    context:        Context
  ): XMRRef =
    XMRRef(
      summon[OperationApi].operationCreate(
        name = "sv.xmr.ref",
        location = location,
        resultsTypes = Some(Seq(resultType)),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(namedAttributeApi.namedAttributeGet("ref".identifierGet, reference.flatSymbolRefAttrGet)) ++ Seq(
            namedAttributeApi.namedAttributeGet("verbatimSuffix".identifierGet, verbatimSuffix.stringAttrGet)
          )
      )
    )
  extension (ref: XMRRef)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given
