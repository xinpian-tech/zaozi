// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package org.llvm.mlir.scalalib.dialect.smt.operation

import org.llvm.mlir.scalalib.capi.ir.{
  AttributeApi,
  Block,
  Context,
  Location,
  LocationApi,
  NamedAttribute,
  NamedAttributeApi,
  Operation,
  OperationApi,
  Type,
  Value,
  given
}
import org.llvm.mlir.scalalib.capi.dialect.smt.{*, given}

import java.lang.foreign.Arena

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
        name = "smt.and",
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

given ApplyFuncApi with
  def op(
    func:        Value,
    args:        Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): ApplyFunc =
    ApplyFunc(
      summon[OperationApi].operationCreate(
        name = "smt.apply_func",
        location = location,
        operands = Seq(func) ++ args,
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: ApplyFunc)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ArrayBroadcastApi with
  def op(
    value:       Value,
    location:    Location,
    tpe:         Type
  )(
    using arena: Arena,
    context:     Context
  ): ArrayBroadcast =
    ArrayBroadcast(
      summon[OperationApi].operationCreate(
        name = "smt.array.broadcast",
        location = location,
        operands = Seq(value),
        resultsTypes = Some(Seq(tpe))
      )
    )
  extension (ref: ArrayBroadcast)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ArraySelectApi with
  def op(
    array:       Value,
    index:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): ArraySelect =
    ArraySelect(
      summon[OperationApi].operationCreate(
        name = "smt.array.select",
        location = location,
        operands = Seq(array, index),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: ArraySelect)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ArrayStoreApi with
  def op(
    array:       Value,
    index:       Value,
    value:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): ArrayStore =
    ArrayStore(
      summon[OperationApi].operationCreate(
        name = "smt.array.store",
        location = location,
        operands = Seq(array, index, value),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: ArrayStore)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given AssertApi with
  def op(
    input:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Assert =
    Assert(
      summon[OperationApi].operationCreate(
        name = "smt.assert",
        location = location,
        operands = Seq(input),
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: Assert)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BV2IntApi with
  def op(
    isSigned:    Boolean,
    input:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BV2Int =
    BV2Int(
      summon[OperationApi].operationCreate(
        name = "smt.bv2int",
        location = location,
        namedAttributes = isSigned match
          case true  =>
            val namedAttributeApi = summon[NamedAttributeApi]
            val attributeApi      = summon[AttributeApi]
            Seq(
              // ::mlir::UnitAttr
              namedAttributeApi.namedAttributeGet("is_signed".identifierGet, attributeApi.unitAttrGet)
            )
          case false => Seq.empty
        ,
        operands = Seq(input),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BV2Int)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BVAShrApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BVAShr =
    BVAShr(
      summon[OperationApi].operationCreate(
        name = "smt.bv.ashr",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BVAShr)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BVAddApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BVAdd =
    BVAdd(
      summon[OperationApi].operationCreate(
        name = "smt.bv.add",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BVAdd)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BVAndApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BVAnd =
    BVAnd(
      summon[OperationApi].operationCreate(
        name = "smt.bv.and",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BVAnd)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BVCmpApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    pred:        String,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BVCmp =
    BVCmp(
      summon[OperationApi].operationCreate(
        name = "smt.bv.cmp",
        location = location,
        operands = Seq(lhs, rhs),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(
            // ::circt::smt::BVCmpPredicateAttr
            namedAttributeApi.namedAttributeGet("pred".identifierGet, pred.getBVCmpPredicateAttribute)
          )
        ,
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BVCmp)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BVConstantApi with
  def op(
    value:       Int,
    width:       Int,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BVConstant =
    BVConstant(
      summon[OperationApi].operationCreate(
        name = "smt.bv.constant",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(
            // circt::smt::BitVectorAttr
            namedAttributeApi.namedAttributeGet("value".identifierGet, value.getBitVectorAttribute(width))
          )
        ,
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BVConstant)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BVLShrApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BVLShr =
    BVLShr(
      summon[OperationApi].operationCreate(
        name = "smt.bv.lshr",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BVLShr)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BVMulApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BVMul =
    BVMul(
      summon[OperationApi].operationCreate(
        name = "smt.bv.mul",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BVMul)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BVNegApi with
  def op(
    input:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BVNeg =
    BVNeg(
      summon[OperationApi].operationCreate(
        name = "smt.bv.neg",
        location = location,
        operands = Seq(input),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BVNeg)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BVNotApi with
  def op(
    input:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BVNot =
    BVNot(
      summon[OperationApi].operationCreate(
        name = "smt.bv.not",
        location = location,
        operands = Seq(input),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BVNot)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BVOrApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BVOr =
    BVOr(
      summon[OperationApi].operationCreate(
        name = "smt.bv.or",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BVOr)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BVSDivApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BVSDiv =
    BVSDiv(
      summon[OperationApi].operationCreate(
        name = "smt.bv.sdiv",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BVSDiv)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BVSModApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BVSMod =
    BVSMod(
      summon[OperationApi].operationCreate(
        name = "smt.bv.smod",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BVSMod)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BVSRemApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BVSRem =
    BVSRem(
      summon[OperationApi].operationCreate(
        name = "smt.bv.srem",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BVSRem)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BVShlApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BVShl =
    BVShl(
      summon[OperationApi].operationCreate(
        name = "smt.bv.shl",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BVShl)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BVUDivApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BVUDiv =
    BVUDiv(
      summon[OperationApi].operationCreate(
        name = "smt.bv.udiv",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BVUDiv)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BVURemApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BVURem =
    BVURem(
      summon[OperationApi].operationCreate(
        name = "smt.bv.urem",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BVURem)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BVXOrApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BVXOr =
    BVXOr(
      summon[OperationApi].operationCreate(
        name = "smt.bv.xor",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BVXOr)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given BoolConstantApi with
  def op(
    value:       Boolean,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BoolConstant =
    BoolConstant(
      summon[OperationApi].operationCreate(
        name = "smt.constant",
        location = location,
        namedAttributes = Seq(
          // ::mlir::BoolAttr
          summon[NamedAttributeApi].namedAttributeGet("value".identifierGet, value.boolAttrGet)
        ),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: BoolConstant)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given CheckApi with
  def op(
    location:    Location,
    resultTypes: Seq[Type] = Seq.empty
  )(
    using arena: Arena,
    context:     Context
  ): Check =
    Check(
      summon[OperationApi].operationCreate(
        name = "smt.check",
        location = location,
        regionBlockTypeLocations = Seq(
          // sat
          Seq((Seq.empty, Seq.empty)),
          // unknown
          Seq((Seq.empty, Seq.empty)),
          // unsat
          Seq((Seq.empty, Seq.empty))
        ),
        resultsTypes = Some(resultTypes)
      )
    )
  extension (ref: Check)
    def operation: Operation = ref._operation
    def satBlock(
      using Arena
    ): Block = operation.getRegion(0).getFirstBlock
    def unknownBlock(
      using Arena
    ): Block = operation.getRegion(1).getFirstBlock
    def unsatBlock(
      using Arena
    ): Block = operation.getRegion(2).getFirstBlock
end given

given ConcatApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Concat =
    Concat(
      summon[OperationApi].operationCreate(
        name = "smt.bv.concat",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: Concat)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given DeclareFunApi with
  def op(
    namePrefix:  String,
    location:    Location,
    tpe:         Type
  )(
    using arena: Arena,
    context:     Context
  ): DeclareFun =
    DeclareFun(
      summon[OperationApi].operationCreate(
        name = "smt.declare_fun",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(
            // ::mlir::StringAttr
            namedAttributeApi.namedAttributeGet("namePrefix".identifierGet, namePrefix.stringAttrGet)
          )
        ,
        resultsTypes = Some(Seq(tpe))
      )
    )
  extension (ref: DeclareFun)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given DistinctApi with
  def op(
    inputs:      Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Distinct =
    Distinct(
      summon[OperationApi].operationCreate(
        name = "smt.distinct",
        location = location,
        operands = inputs,
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: Distinct)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given EqApi with
  def op(
    inputs:      Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Eq =
    Eq(
      summon[OperationApi].operationCreate(
        name = "smt.eq",
        location = location,
        operands = inputs,
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: Eq)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

// TODO: check it in the future
given ExistsApi with
  def op(
    weight:        BigInt,
    noPattern:     Boolean,
    boundVarNames: Seq[String],
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Exists =
    Exists(
      summon[OperationApi].operationCreate(
        name = "smt.exists",
        location = location,
        regionBlockTypeLocations = Seq(
          Seq(
            // body
            (Seq.empty, Seq.empty)
          )
        ),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          val attributeApi      = summon[AttributeApi]
          val noPatternAttr     = noPattern match
            case true  =>
              // ::mlir::UnitAttr
              Seq(namedAttributeApi.namedAttributeGet("noPattern".identifierGet, attributeApi.unitAttrGet))
            case false => Seq.empty
          Seq(
            // ::mlir::IntegerAttr
            namedAttributeApi.namedAttributeGet(
              "weight".identifierGet,
              weight.toLong.integerAttrGet(32.integerTypeGet)
            ),
            // ::mlir::ArrayAttr
            namedAttributeApi.namedAttributeGet(
              "boundVarNames".identifierGet,
              boundVarNames.map(_.stringAttrGet).arrayAttrGet
            )
          ) ++ noPatternAttr
        ,
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: Exists)
    def operation: Operation = ref._operation
    def bodyBlock(
      using Arena
    ): Block = operation.getRegion(0).getFirstBlock
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ExtractApi with
  def op(
    lowBit:      BigInt,
    input:       Value,
    location:    Location,
    tpe:         Type
  )(
    using arena: Arena,
    context:     Context
  ): Extract =
    Extract(
      summon[OperationApi].operationCreate(
        name = "smt.bv.extract",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(
            // ::mlir::IntegerAttr
            namedAttributeApi.namedAttributeGet("lowBit".identifierGet, lowBit.toLong.integerAttrGet(32.integerTypeGet))
          )
        ,
        operands = Seq(input),
        resultsTypes = Some(Seq(tpe))
      )
    )
  extension (ref: Extract)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ForallApi with
  def op(
    weight:        BigInt,
    noPattern:     Boolean,
    boundVarNames: Seq[String],
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Forall =
    Forall(
      summon[OperationApi].operationCreate(
        name = "smt.forall",
        location = location,
        regionBlockTypeLocations = Seq(
          Seq(
            // body
            (Seq.empty, Seq.empty)
          )
        ),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          val attributeApi      = summon[AttributeApi]
          val noPatternAttr     = noPattern match
            case true  =>
              // ::mlir::UnitAttr
              Seq(namedAttributeApi.namedAttributeGet("noPattern".identifierGet, attributeApi.unitAttrGet))
            case false => Seq.empty
          Seq(
            // ::mlir::IntegerAttr
            namedAttributeApi.namedAttributeGet(
              "weight".identifierGet,
              weight.toLong.integerAttrGet(32.integerTypeGet)
            ),
            // ::mlir::ArrayAttr
            namedAttributeApi.namedAttributeGet(
              "boundVarNames".identifierGet,
              boundVarNames.map(_.stringAttrGet).arrayAttrGet
            )
          ) ++ noPatternAttr
        ,
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: Forall)
    def operation: Operation = ref._operation
    def bodyBlock(
      using Arena
    ): Block = operation.getRegion(0).getFirstBlock
    def patternBlock(
      using Arena
    ): Block = operation.getRegion(1).getFirstBlock
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ImpliesApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Implies =
    Implies(
      summon[OperationApi].operationCreate(
        name = "smt.implies",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: Implies)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given Int2BVApi with
  def op(
    input:       Value,
    location:    Location,
    tpe:         Type
  )(
    using arena: Arena,
    context:     Context
  ): Int2BV =
    Int2BV(
      summon[OperationApi].operationCreate(
        name = "smt.int2bv",
        location = location,
        operands = Seq(input),
        resultsTypes = Some(Seq(tpe))
      )
    )
  extension (ref: Int2BV)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given IntAbsApi with
  def op(
    input:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): IntAbs =
    IntAbs(
      summon[OperationApi].operationCreate(
        name = "smt.int.abs",
        location = location,
        operands = Seq(input),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: IntAbs)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given IntAddApi with
  def op(
    input:       Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): IntAdd =
    IntAdd(
      summon[OperationApi].operationCreate(
        name = "smt.int.add",
        location = location,
        operands = input,
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: IntAdd)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given IntCmpApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    pred:        String,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): IntCmp =
    IntCmp(
      summon[OperationApi].operationCreate(
        name = "smt.int.cmp",
        location = location,
        operands = Seq(lhs, rhs),
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(
            // ::circt::smt::IntPredicateAttr
            namedAttributeApi.namedAttributeGet("pred".identifierGet, pred.getIntPredicateAttribute)
          )
        ,
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: IntCmp)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given IntConstantApi with
  def op(
    value:       BigInt,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): IntConstant =
    IntConstant(
      summon[OperationApi].operationCreate(
        name = "smt.int.constant",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(
            // ::mlir::IntegerAttr
            namedAttributeApi.namedAttributeGet("value".identifierGet, value.toLong.integerAttrGet(32.integerTypeGet))
          )
        ,
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: IntConstant)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given IntDivApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): IntDiv =
    IntDiv(
      summon[OperationApi].operationCreate(
        name = "smt.int.div",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: IntDiv)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given IntModApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): IntMod =
    IntMod(
      summon[OperationApi].operationCreate(
        name = "smt.int.mod",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: IntMod)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given IntMulApi with
  def op(
    inputs:      Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): IntMul =
    IntMul(
      summon[OperationApi].operationCreate(
        name = "smt.int.mul",
        location = location,
        operands = inputs,
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: IntMul)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given IntSubApi with
  def op(
    lhs:         Value,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): IntSub =
    IntSub(
      summon[OperationApi].operationCreate(
        name = "smt.int.sub",
        location = location,
        operands = Seq(lhs, rhs),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: IntSub)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given IteApi with
  def op(
    cond:        Value,
    thenValue:   Value,
    elseValue:   Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Ite =
    Ite(
      summon[OperationApi].operationCreate(
        name = "smt.ite",
        location = location,
        operands = Seq(cond, thenValue, elseValue),
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: Ite)
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
        name = "smt.not",
        location = location,
        operands = Seq(input),
        inferredResultsTypes = Some(1)
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
        name = "smt.or",
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

given PopApi with
  def op(
    count:       BigInt,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Pop =
    Pop(
      summon[OperationApi].operationCreate(
        name = "smt.pop",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(
            // ::mlir::IntegerAttr
            namedAttributeApi.namedAttributeGet("count".identifierGet, count.toLong.integerAttrGet(32.integerTypeGet))
          )
        ,
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: Pop) def operation: Operation = ref._operation
end given

given PushApi with
  def op(
    count:       BigInt,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Push =
    Push(
      summon[OperationApi].operationCreate(
        name = "smt.push",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(
            // ::mlir::IntegerAttr
            namedAttributeApi.namedAttributeGet("count".identifierGet, count.toLong.integerAttrGet(32.integerTypeGet))
          )
        ,
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: Push) def operation: Operation = ref._operation
end given

given RepeatApi with
  def op(
    input:       Value,
    location:    Location,
    tpe:         Type
  )(
    using arena: Arena,
    context:     Context
  ): Repeat =
    Repeat(
      summon[OperationApi].operationCreate(
        name = "smt.bv.repeat",
        location = location,
        operands = Seq(input),
        resultsTypes = Some(Seq(tpe))
      )
    )
  extension (ref: Repeat)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given ResetApi with
  def op(
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Reset =
    Reset(
      summon[OperationApi].operationCreate(
        name = "smt.reset",
        location = location,
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: Reset) def operation: Operation = ref._operation
end given

given SetLogicApi with
  def op(
    logic:       String,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): SetLogic =
    SetLogic(
      summon[OperationApi].operationCreate(
        name = "smt.set_logic",
        location = location,
        namedAttributes =
          val namedAttributeApi = summon[NamedAttributeApi]
          Seq(
            // ::mlir::StringAttr
            namedAttributeApi.namedAttributeGet("logic".identifierGet, logic.stringAttrGet)
          )
        ,
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: SetLogic) def operation: Operation = ref._operation
end given

given SolverApi with
  def op(
    location:    Location,
    inputs:      Seq[Value] = Seq.empty
  )(
    using arena: Arena,
    context:     Context
  ): Solver =
    Solver(
      summon[OperationApi].operationCreate(
        name = "smt.solver",
        location = location,
        operands = inputs,
        regionBlockTypeLocations = Seq(
          Seq((inputs.map(_.getType), inputs.map(_ => location)))
        ),
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: Solver)
    def operation: Operation = ref._operation
    def bodyBlock(
      using Arena
    ): Block = operation.getRegion(0).getFirstBlock
end given

given XOrApi with
  def op(
    inputs:      Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): XOr =
    XOr(
      summon[OperationApi].operationCreate(
        name = "smt.xor",
        location = location,
        operands = inputs,
        inferredResultsTypes = Some(1)
      )
    )
  extension (ref: XOr)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given YieldApi with
  def op(
    values:      Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Yield =
    Yield(
      summon[OperationApi].operationCreate(
        name = "smt.yield",
        location = location,
        operands = values,
        resultsTypes = Some(Seq.empty)
      )
    )
  extension (ref: Yield)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)
end given
