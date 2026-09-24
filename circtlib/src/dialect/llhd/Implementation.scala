// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.llhd.operation

import org.llvm.circt.scalalib.capi.dialect.llhd.{AttributeApi as LLHDAttributeApi, TypeApi as LLHDTypeApi, given}
import org.llvm.mlir.scalalib.capi.ir.{
  AttributeApi,
  Block,
  Context,
  Location,
  NamedAttributeApi,
  Operation,
  OperationApi,
  OperationStateApi,
  Value,
  given
}

import java.lang.foreign.Arena

given ConstantTimeApi with
  def op(
    timeUnit: String,
    amount:   Long,
    location: Location
  )(
    using Arena,
    Context
  ): ConstantTime =
    require(amount >= 0, "LLHD time must be nonnegative")
    val value = summon[LLHDAttributeApi].TimeAttrGet(timeUnit, BigInt(amount), BigInt(0), BigInt(0))
    ConstantTime(
      summon[OperationApi].operationCreate(
        name = "llhd.constant_time",
        location = location,
        namedAttributes = Seq(summon[NamedAttributeApi].namedAttributeGet("value".identifierGet, value)),
        resultsTypes = Some(Seq(summon[LLHDTypeApi].timeTypeGet))
      )
    )
  extension (ref: ConstantTime)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given SignalApi with
  def op(
    initial:  Value,
    name:     Option[String],
    location: Location
  )(
    using Arena,
    Context
  ): Signal =
    val attrs = name.toSeq.map(n => summon[NamedAttributeApi].namedAttributeGet("name".identifierGet, n.stringAttrGet))
    Signal(
      summon[OperationApi].operationCreate(
        name = "llhd.sig",
        location = location,
        namedAttributes = attrs,
        operands = Seq(initial),
        resultsTypes = Some(Seq(summon[LLHDTypeApi].refTypeGet(initial.getType)))
      )
    )
  extension (ref: Signal)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ProbeApi with
  def op(
    signal:     Value,
    resultType: org.llvm.mlir.scalalib.capi.ir.Type,
    location:   Location
  )(
    using Arena,
    Context
  ): Probe =
    Probe(
      summon[OperationApi].operationCreate(
        name = "llhd.prb",
        location = location,
        operands = Seq(signal),
        resultsTypes = Some(Seq(resultType))
      )
    )
  extension (ref: Probe)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given DriveApi with
  def op(
    signal:   Value,
    value:    Value,
    delay:    Value,
    enable:   Option[Value],
    location: Location
  )(
    using Arena,
    Context
  ): Drive =
    val segments = Seq(1, 1, 1, enable.size)
    Drive(
      summon[OperationApi].operationCreate(
        name = "llhd.drv",
        location = location,
        namedAttributes = Seq(
          summon[NamedAttributeApi].namedAttributeGet("operandSegmentSizes".identifierGet, segments.denseI32ArrayGet)
        ),
        operands = Seq(signal, value, delay) ++ enable.toSeq,
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: Drive) def operation: Operation = ref._operation
end given

given ProcessApi with
  def op(
    location: Location
  )(
    using Arena,
    Context
  ): Process =
    Process(
      summon[OperationApi].operationCreate(
        name = "llhd.process",
        location = location,
        regionBlockTypeLocations = Seq(Seq(Seq.empty -> Seq.empty)),
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: Process)
    def operation: Operation = ref._operation
    def body(
      using Arena
    ): Block = ref.operation.getFirstRegion.getFirstBlock
end given

given WaitApi with
  def op(
    delay:       Option[Value],
    observed:    Seq[Value],
    destination: Block,
    location:    Location
  )(
    using Arena,
    Context
  ): Wait =
    val state = summon[OperationStateApi].operationStateGet("llhd.wait", location)
    state.addOperands(delay.toSeq ++ observed)
    state.addSuccessors(Seq(destination))
    state.addAttributes(
      Seq(
        summon[NamedAttributeApi].namedAttributeGet(
          "operandSegmentSizes".identifierGet,
          Seq(0, delay.size, observed.size, 0).denseI32ArrayGet
        )
      )
    )
    Wait(summon[OperationApi].operationCreate(state))
  extension (ref: Wait) def operation: Operation = ref._operation
end given

given HaltApi with
  def op(
    location: Location
  )(
    using Arena,
    Context
  ): Halt =
    Halt(summon[OperationApi].operationCreate(name = "llhd.halt", location = location, resultsTypes = Some(Seq.empty)))
  extension (ref: Halt) def operation: Operation = ref._operation
end given
