// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.hw.operation

import org.llvm.mlir.scalalib.capi.ir.{Module as MlirModule, ModuleApi as MlirModuleApi, *, given}
import org.llvm.mlir.scalalib.capi.support.HasOperation
import java.lang.foreign.Arena
import org.llvm.circt.scalalib.dialect.hw.tpe.{ModulePort, PortDirection, TypeApi as HWTypes, given}
import org.llvm.circt.scalalib.capi.dialect.hw.given

given InstanceApi with
  def op(
    name:        String,
    module:      String,
    inputs:      Seq[Value],
    inputNames:  Seq[String],
    resultTypes: Seq[Type],
    resultNames: Seq[String],
    parameters:  Seq[Attribute],
    location:    Location
  )(
    using Arena,
    Context
  ): Instance =
    Instance(
      summon[OperationApi].operationCreate(
        "hw.instance",
        location,
        operands = inputs,
        resultsTypes = Some(resultTypes),
        namedAttributes = Seq(
          "instanceName" -> name.stringAttrGet,
          "moduleName"   -> module.flatSymbolRefAttrGet,
          "argNames"     -> inputNames.map(_.stringAttrGet).arrayAttrGet,
          "resultNames"  -> resultNames.map(_.stringAttrGet).arrayAttrGet,
          "parameters"   -> parameters.arrayAttrGet
        )
          .map((name, attr) => summon[NamedAttributeApi].namedAttributeGet(name.identifierGet, attr))
      )
    )
  extension (ref: Instance)
    def operation: Operation = ref._operation
    def result(
      index: Int
    )(
      using Arena
    ): Value = ref.operation.getResult(index)

given ConstantApi with
  def op(
    value:    Attribute,
    location: Location
  )(
    using Arena,
    Context
  ): Constant =
    Constant(
      summon[OperationApi].operationCreate(
        "hw.constant",
        location,
        namedAttributes = Seq(summon[NamedAttributeApi].namedAttributeGet("value".identifierGet, value)),
        resultsTypes = Some(Seq(value.getType))
      )
    )
  extension (ref: Constant)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)

given ModuleApi with
  def op(
    name:       String,
    ports:      Seq[ModulePort],
    parameters: Seq[Attribute],
    location:   Location
  )(
    using Arena,
    Context
  ): Module =
    val inputs = ports
      .filter(_.direction != PortDirection.Output)
      .map: port =>
        if port.direction == PortDirection.InOut then port.tpe.inOutTypeGet() else port.tpe
    new Module(
      summon[OperationApi].operationCreate(
        "hw.module",
        location,
        namedAttributes = Seq(
          "sym_name"    -> name.stringAttrGet,
          "module_type" -> summon[HWTypes].getModule(ports).typeAttrGet,
          "parameters"  -> parameters.arrayAttrGet
        ).map((name, attr) => summon[NamedAttributeApi].namedAttributeGet(name.identifierGet, attr)),
        regionBlockTypeLocations = Seq(Seq((inputs, inputs.map(_ => location))))
      )
    )
  extension (ref: Module)
    def operation: Operation = ref._operation
    def block(
      using Arena
    ): Block = ref.operation.getRegion(0).getFirstBlock

given ModuleExternApi with
  def op(
    name:       String,
    ports:      Seq[ModulePort],
    parameters: Seq[Attribute],
    location:   Location
  )(
    using Arena,
    Context
  ): ModuleExtern =
    ModuleExtern(
      summon[OperationApi].operationCreate(
        "hw.module.extern",
        location,
        namedAttributes = Seq(
          "sym_name"    -> name.stringAttrGet,
          "module_type" -> summon[HWTypes].getModule(ports).typeAttrGet,
          "parameters"  -> parameters.arrayAttrGet
        ).map((name, attr) => summon[NamedAttributeApi].namedAttributeGet(name.identifierGet, attr)),
        regionBlockTypeLocations = Seq(Seq.empty)
      )
    )
  extension (ref: ModuleExtern) def operation: Operation = ref._operation

given OutputApi with
  def op(
    values:   Seq[Value],
    location: Location
  )(
    using Arena,
    Context
  ): Output =
    Output(summon[OperationApi].operationCreate("hw.output", location, operands = values))
  extension (ref: Output) def operation: Operation = ref._operation

given ParamValueApi with
  def op(
    value:    Attribute,
    location: Location
  )(
    using Arena,
    Context
  ): ParamValue =
    ParamValue(
      summon[OperationApi].operationCreate(
        "hw.param.value",
        location,
        namedAttributes = Seq("value" -> value).map((name, attr) =>
          summon[NamedAttributeApi].namedAttributeGet(name.identifierGet, attr)
        ),
        resultsTypes = Some(Seq(value.getType))
      )
    )
  extension (ref: ParamValue)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)

given EnumConstantApi with
  def op(
    field:      Attribute,
    resultType: Type,
    location:   Location
  )(
    using Arena,
    Context
  ): EnumConstant =
    EnumConstant(
      summon[OperationApi].operationCreate(
        "hw.enum.constant",
        location,
        namedAttributes = Seq("field" -> field).map((name, attr) =>
          summon[NamedAttributeApi].namedAttributeGet(name.identifierGet, attr)
        ),
        resultsTypes = Some(Seq(resultType))
      )
    )
  extension (ref: EnumConstant)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)

given TypeScopeApi with
  def op(
    name:     String,
    location: Location
  )(
    using Arena,
    Context
  ): TypeScope =
    TypeScope(
      summon[OperationApi].operationCreate(
        "hw.type_scope",
        location,
        namedAttributes =
          Seq(summon[NamedAttributeApi].namedAttributeGet("sym_name".identifierGet, name.stringAttrGet)),
        regionBlockTypeLocations = Seq(Seq((Seq.empty, Seq.empty)))
      )
    )
  extension (ref: TypeScope)
    def operation: Operation = ref._operation
    def block(
      using Arena
    ): Block = ref.operation.getRegion(0).getFirstBlock

given TypeDeclApi with
  def op(
    name:     String,
    tpe:      Type,
    location: Location
  )(
    using Arena,
    Context
  ): TypeDecl =
    TypeDecl(
      summon[OperationApi].operationCreate(
        "hw.typedecl",
        location,
        namedAttributes = Seq("sym_name" -> name.stringAttrGet, "type" -> tpe.typeAttrGet)
          .map((name, attr) => summon[NamedAttributeApi].namedAttributeGet(name.identifierGet, attr))
      )
    )
  extension (ref: TypeDecl) def operation: Operation = ref._operation

given EnumCmpApi with
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ): EnumCmp =
    EnumCmp(
      summon[OperationApi]
        .operationCreate("hw.enum.cmp", location, operands = Seq(lhs, rhs), resultsTypes = Some(Seq(1.integerTypeGet)))
    )
  extension (ref: EnumCmp)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)

given StructCreateApi with
  def op(
    values:     Seq[Value],
    resultType: Type,
    location:   Location
  )(
    using Arena,
    Context
  ): StructCreate =
    StructCreate(
      summon[OperationApi].operationCreate(
        "hw.struct_create",
        location,
        operands = values,
        resultsTypes = Some(Seq(resultType)),
        namedAttributes = (Seq.empty: Seq[(String, Attribute)]).map((name, attr) =>
          summon[NamedAttributeApi].namedAttributeGet(name.identifierGet, attr)
        )
      )
    )
  extension (ref: StructCreate)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)

given StructExtractApi with
  def op(
    input:      Value,
    fieldIndex: Int,
    resultType: Type,
    location:   Location
  )(
    using Arena,
    Context
  ): StructExtract =
    StructExtract(
      summon[OperationApi].operationCreate(
        "hw.struct_extract",
        location,
        operands = Seq(input),
        resultsTypes = Some(Seq(resultType)),
        namedAttributes =
          (Seq("fieldIndex" -> fieldIndex.toLong.integerAttrGet(32.integerTypeGet)): Seq[(String, Attribute)]).map(
            (name, attr) => summon[NamedAttributeApi].namedAttributeGet(name.identifierGet, attr)
          )
      )
    )
  extension (ref: StructExtract)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)

given UnionCreateApi with
  def op(
    input:      Value,
    fieldIndex: Int,
    resultType: Type,
    location:   Location
  )(
    using Arena,
    Context
  ): UnionCreate =
    UnionCreate(
      summon[OperationApi].operationCreate(
        "hw.union_create",
        location,
        operands = Seq(input),
        resultsTypes = Some(Seq(resultType)),
        namedAttributes =
          (Seq("fieldIndex" -> fieldIndex.toLong.integerAttrGet(32.integerTypeGet)): Seq[(String, Attribute)]).map(
            (name, attr) => summon[NamedAttributeApi].namedAttributeGet(name.identifierGet, attr)
          )
      )
    )
  extension (ref: UnionCreate)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)

given UnionExtractApi with
  def op(
    input:      Value,
    fieldIndex: Int,
    resultType: Type,
    location:   Location
  )(
    using Arena,
    Context
  ): UnionExtract =
    UnionExtract(
      summon[OperationApi].operationCreate(
        "hw.union_extract",
        location,
        operands = Seq(input),
        resultsTypes = Some(Seq(resultType)),
        namedAttributes =
          (Seq("fieldIndex" -> fieldIndex.toLong.integerAttrGet(32.integerTypeGet)): Seq[(String, Attribute)]).map(
            (name, attr) => summon[NamedAttributeApi].namedAttributeGet(name.identifierGet, attr)
          )
      )
    )
  extension (ref: UnionExtract)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)

given ArrayGetApi with
  def op(
    input:      Value,
    index:      Value,
    resultType: Type,
    location:   Location
  )(
    using Arena,
    Context
  ): ArrayGet =
    ArrayGet(
      summon[OperationApi].operationCreate(
        "hw.array_get",
        location,
        operands = Seq(input, index),
        resultsTypes = Some(Seq(resultType)),
        namedAttributes = (Seq.empty: Seq[(String, Attribute)]).map((name, attr) =>
          summon[NamedAttributeApi].namedAttributeGet(name.identifierGet, attr)
        )
      )
    )
  extension (ref: ArrayGet)
    def operation: Operation = ref._operation
    def result(
      using Arena
    ): Value = ref.operation.getResult(0)

given ArrayCreateApi with
  def op(
    inputs:     Seq[Value],
    resultType: Type,
    location:   Location
  )(
    using Arena,
    Context
  ): ArrayCreate =
    ArrayCreate(
      summon[OperationApi]
        .operationCreate("hw.array_create", location, operands = inputs, resultsTypes = Some(Seq(resultType)))
    )
  extension (ref: ArrayCreate) def operation: Operation = ref._operation
