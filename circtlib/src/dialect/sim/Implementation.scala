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

private inline def outputStreamType(
  using Arena,
  Context
): Type = summon[SimTypeApi].outputStreamTypeGet

given StdoutStreamApi with
  def op(
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): StdoutStream =
    StdoutStream(
      summon[OperationApi].operationCreate(
        name = "sim.stdout_stream",
        location = location,
        resultsTypes = Some(Seq(outputStreamType))
      )
    )
  extension (ref: StdoutStream)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given StderrStreamApi with
  def op(
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): StderrStream =
    StderrStream(
      summon[OperationApi].operationCreate(
        name = "sim.stderr_stream",
        location = location,
        resultsTypes = Some(Seq(outputStreamType))
      )
    )
  extension (ref: StderrStream)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given PrintFormattedApi with
  def op(
    input:       Value,
    clock:       Value,
    condition:   Value,
    stream:      Option[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): PrintFormatted =
    PrintFormatted(
      summon[OperationApi].operationCreate(
        name = "sim.print",
        location = location,
        operands = Seq(input, clock, condition) ++ stream.toSeq
      )
    )
  extension (ref: PrintFormatted) def operation: Operation = ref._operation
end given

given PrintFormattedProcApi with
  def op(
    input:       Value,
    stream:      Option[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): PrintFormattedProc =
    PrintFormattedProc(
      summon[OperationApi].operationCreate(
        name = "sim.proc.print",
        location = location,
        operands = Seq(input) ++ stream.toSeq
      )
    )
  extension (ref: PrintFormattedProc) def operation: Operation = ref._operation
end given

given TriggeredApi with
  def op(
    clock:       Value,
    condition:   Option[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Triggered =
    Triggered(
      summon[OperationApi].operationCreate(
        name = "sim.triggered",
        location = location,
        regionBlockTypeLocations = Seq(Seq((Seq.empty, Seq.empty))),
        operands = Seq(clock) ++ condition.toSeq
      )
    )
  extension (ref: Triggered)
    def operation: Operation = ref._operation
    def block(
      using Arena
    ): Block = ref.operation.getFirstRegion.getFirstBlock
end given

given TerminateApi with
  def op(
    success:     Boolean,
    verbose:     Boolean,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Terminate =
    Terminate(
      summon[OperationApi].operationCreate(
        name = "sim.terminate",
        location = location,
        namedAttributes = Seq(
          named("success", success.boolAttrGet),
          named("verbose", verbose.boolAttrGet)
        )
      )
    )
  extension (ref: Terminate) def operation: Operation = ref._operation
end given

given ClockedTerminateApi with
  def op(
    clock:       Value,
    condition:   Value,
    success:     Boolean,
    verbose:     Boolean,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): ClockedTerminate =
    ClockedTerminate(
      summon[OperationApi].operationCreate(
        name = "sim.clocked_terminate",
        location = location,
        namedAttributes = Seq(
          named("success", success.boolAttrGet),
          named("verbose", verbose.boolAttrGet)
        ),
        operands = Seq(clock, condition)
      )
    )
  extension (ref: ClockedTerminate) def operation: Operation = ref._operation
end given

given PauseApi with
  def op(
    verbose:     Boolean,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Pause =
    Pause(
      summon[OperationApi].operationCreate(
        name = "sim.pause",
        location = location,
        namedAttributes = Seq(named("verbose", verbose.boolAttrGet))
      )
    )
  extension (ref: Pause) def operation: Operation = ref._operation
end given

given ClockedPauseApi with
  def op(
    clock:       Value,
    condition:   Value,
    verbose:     Boolean,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): ClockedPause =
    ClockedPause(
      summon[OperationApi].operationCreate(
        name = "sim.clocked_pause",
        location = location,
        namedAttributes = Seq(named("verbose", verbose.boolAttrGet)),
        operands = Seq(clock, condition)
      )
    )
  extension (ref: ClockedPause) def operation: Operation = ref._operation
end given

given PlusArgsTestApi with
  def op(
    formatString: String,
    location:     Location
  )(
    using arena:  Arena,
    context:      Context
  ): PlusArgsTest =
    PlusArgsTest(
      summon[OperationApi].operationCreate(
        name = "sim.plusargs.test",
        location = location,
        namedAttributes = Seq(named("formatString", formatString.stringAttrGet)),
        resultsTypes = Some(Seq(1.integerTypeGet))
      )
    )
  extension (ref: PlusArgsTest)
    def operation: Operation = ref._operation
    def found(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given PlusArgsValueApi with
  def op(
    formatString: String,
    resultType:   Type,
    location:     Location
  )(
    using arena:  Arena,
    context:      Context
  ): PlusArgsValue =
    PlusArgsValue(
      summon[OperationApi].operationCreate(
        name = "sim.plusargs.value",
        location = location,
        namedAttributes = Seq(named("formatString", formatString.stringAttrGet)),
        resultsTypes = Some(Seq(1.integerTypeGet, resultType))
      )
    )
  extension (ref: PlusArgsValue)
    def operation: Operation = ref._operation
    def found(
      using Arena
    ): Value = ref.operation.getResult(0)
    def value(
      using Arena
    ): Value = ref.operation.getResult(1)
end given
