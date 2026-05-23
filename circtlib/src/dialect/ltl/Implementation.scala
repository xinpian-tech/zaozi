// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.ltl.operation

import org.llvm.circt.scalalib.capi.dialect.ltl.{LTLClockEdge, TypeApi as LTLTypeApi, given}
import org.llvm.mlir.scalalib.capi.ir.{
  Attribute,
  Context,
  Location,
  NamedAttribute,
  NamedAttributeApi,
  Operation,
  OperationApi,
  TypeApi as MlirTypeApi,
  Value,
  given
}

import java.lang.foreign.Arena

private inline def i64Attr(
  value:       Long
)(
  using arena: Arena,
  context:     Context
): Attribute = value.integerAttrGet(64.integerTypeGet)

private inline def integerRangeAttrs(
  baseName:    String,
  base:        Long,
  moreName:    String,
  more:        Option[Long]
)(
  using arena: Arena,
  context:     Context
): Seq[NamedAttribute] =
  val namedAttributeApi = summon[NamedAttributeApi]
  Seq(namedAttributeApi.namedAttributeGet(baseName.identifierGet, i64Attr(base))) ++
    more.map(value => namedAttributeApi.namedAttributeGet(moreName.identifierGet, i64Attr(value))).toSeq

private inline def sequenceType(
  using Arena,
  Context
) = summon[LTLTypeApi].sequenceTypeGet

private inline def propertyType(
  using Arena,
  Context
) = summon[LTLTypeApi].propertyTypeGet

given AndApi with
  def op(
    inputs:      Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): And =
    And(
      summon[OperationApi].operationCreate(
        name = "ltl.and",
        location = location,
        operands = inputs,
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: And)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BooleanConstantApi with
  def op(
    value:       Boolean,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BooleanConstant =
    BooleanConstant(
      summon[OperationApi].operationCreate(
        name = "ltl.boolean_constant",
        location = location,
        namedAttributes = Seq(
          summon[NamedAttributeApi].namedAttributeGet("value".identifierGet, value.boolAttrGet)
        ),
        resultsTypes = Some(Seq(propertyType))
      )
    )
  extension (ref: BooleanConstant)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ClockApi with
  def op(
    input:       Value,
    edge:        LTLClockEdge,
    clock:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Clock =
    Clock(
      summon[OperationApi].operationCreate(
        name = "ltl.clock",
        location = location,
        namedAttributes = Seq(
          summon[NamedAttributeApi].namedAttributeGet("edge".identifierGet, edge.toAttribute)
        ),
        operands = Seq(input, clock),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: Clock)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ClockedDelayApi with
  def op(
    input:       Value,
    edge:        LTLClockEdge,
    clock:       Value,
    delay:       Long,
    length:      Option[Long],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): ClockedDelay =
    ClockedDelay(
      summon[OperationApi].operationCreate(
        name = "ltl.clocked_delay",
        location = location,
        namedAttributes = Seq(summon[NamedAttributeApi].namedAttributeGet("edge".identifierGet, edge.toAttribute)) ++
          integerRangeAttrs("delay", delay, "length", length),
        operands = Seq(input, clock),
        resultsTypes = Some(Seq(sequenceType))
      )
    )
  extension (ref: ClockedDelay)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ConcatApi with
  def op(
    inputs:      Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Concat =
    Concat(
      summon[OperationApi].operationCreate(
        name = "ltl.concat",
        location = location,
        operands = inputs,
        resultsTypes = Some(Seq(sequenceType))
      )
    )
  extension (ref: Concat)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given DelayApi with
  def op(
    input:       Value,
    delay:       Long,
    length:      Option[Long],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Delay =
    Delay(
      summon[OperationApi].operationCreate(
        name = "ltl.delay",
        location = location,
        namedAttributes = integerRangeAttrs("delay", delay, "length", length),
        operands = Seq(input),
        resultsTypes = Some(Seq(sequenceType))
      )
    )
  extension (ref: Delay)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given EventuallyApi with
  def op(
    input:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Eventually =
    Eventually(
      summon[OperationApi].operationCreate(
        name = "ltl.eventually",
        location = location,
        operands = Seq(input),
        resultsTypes = Some(Seq(propertyType))
      )
    )
  extension (ref: Eventually)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given GoToRepeatApi with
  def op(
    input:       Value,
    base:        Long,
    more:        Long,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): GoToRepeat =
    GoToRepeat(
      summon[OperationApi].operationCreate(
        name = "ltl.goto_repeat",
        location = location,
        namedAttributes = integerRangeAttrs("base", base, "more", Some(more)),
        operands = Seq(input),
        resultsTypes = Some(Seq(sequenceType))
      )
    )
  extension (ref: GoToRepeat)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ImplicationApi with
  def op(
    antecedent:  Value,
    consequent:  Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Implication =
    Implication(
      summon[OperationApi].operationCreate(
        name = "ltl.implication",
        location = location,
        operands = Seq(antecedent, consequent),
        resultsTypes = Some(Seq(propertyType))
      )
    )
  extension (ref: Implication)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given IntersectApi with
  def op(
    inputs:      Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Intersect =
    Intersect(
      summon[OperationApi].operationCreate(
        name = "ltl.intersect",
        location = location,
        operands = inputs,
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: Intersect)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given NonConsecutiveRepeatApi with
  def op(
    input:       Value,
    base:        Long,
    more:        Long,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): NonConsecutiveRepeat =
    NonConsecutiveRepeat(
      summon[OperationApi].operationCreate(
        name = "ltl.non_consecutive_repeat",
        location = location,
        namedAttributes = integerRangeAttrs("base", base, "more", Some(more)),
        operands = Seq(input),
        resultsTypes = Some(Seq(sequenceType))
      )
    )
  extension (ref: NonConsecutiveRepeat)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given NotApi with
  def op(
    input:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Not =
    Not(
      summon[OperationApi].operationCreate(
        name = "ltl.not",
        location = location,
        operands = Seq(input),
        resultsTypes = Some(Seq(propertyType))
      )
    )
  extension (ref: Not)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given OrApi with
  def op(
    inputs:      Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Or =
    Or(
      summon[OperationApi].operationCreate(
        name = "ltl.or",
        location = location,
        operands = inputs,
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: Or)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given PastApi with
  def op(
    input:       Value,
    delay:       Long,
    clock:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Past =
    Past(
      summon[OperationApi].operationCreate(
        name = "ltl.past",
        location = location,
        namedAttributes = Seq(
          summon[NamedAttributeApi].namedAttributeGet("delay".identifierGet, i64Attr(delay))
        ),
        operands = Seq(input, clock),
        resultsTypes = Some(Seq(input.getType))
      )
    )
  extension (ref: Past)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given RepeatApi with
  def op(
    input:       Value,
    base:        Long,
    more:        Option[Long],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Repeat =
    Repeat(
      summon[OperationApi].operationCreate(
        name = "ltl.repeat",
        location = location,
        namedAttributes = integerRangeAttrs("base", base, "more", more),
        operands = Seq(input),
        resultsTypes = Some(Seq(sequenceType))
      )
    )
  extension (ref: Repeat)
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
        name = "ltl.sampled",
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

given UntilApi with
  def op(
    input:       Value,
    condition:   Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Until =
    Until(
      summon[OperationApi].operationCreate(
        name = "ltl.until",
        location = location,
        operands = Seq(input, condition),
        resultsTypes = Some(Seq(propertyType))
      )
    )
  extension (ref: Until)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given
