// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package org.llvm.mlir.scalalib.capi.dialect.func

import org.llvm.mlir.scalalib.capi.ir.{
  AttributeApi,
  Block,
  Context,
  LocationApi,
  Module,
  NamedAttributeApi,
  Operation,
  OperationApi,
  Type,
  given
}

import java.lang.foreign.Arena
import org.llvm.mlir.scalalib.capi.support.given

// Structure
given FuncApi with
  inline def op(
    symName:     String
    // funcType: Type,
    // symVisibility: String,
    // argAttrs: Seq[NamedAttribute],
    // resAttrs: Seq[NamedAttribute],
    // noInline: Boolean,
  )(
    using arena: Arena,
    context:     Context
  ): Func = Func(
    summon[OperationApi].operationCreate(
      name = "func.func",
      location = summon[LocationApi].locationUnknownGet,
      regionBlockTypeLocations = Seq(
        Seq(
          (Seq.empty, Seq.empty)
        )
      ),
      namedAttributes =
        val namedAttributeApi = summon[NamedAttributeApi]
        // val noInlineAttr = noInline match
        //   case true =>
        //     Seq(
        //       // ::mlir::UnitAttr
        //       namedAttributeApi.namedAttributeGet("noInline".identifierGet, summon[AttributeApi].unitAttrGet)
        //     )
        //   case false => Seq.empty
        Seq(
          // ::mlir::StringAttr
          namedAttributeApi.namedAttributeGet("symName".identifierGet, symName.stringAttrGet)
          // ::mlir::TypeAttr
          // namedAttributeApi.namedAttributeGet("funcType".identifierGet, ???),
          // ::mlir::StringAttr
          // namedAttributeApi.namedAttributeGet("symVisibility".identifierGet, ???),
          // ::mlir::ArrayAttr
          // namedAttributeApi.namedAttributeGet("argAttrs".identifierGet, ???),
          // ::mlir::ArrayAttr
          // namedAttributeApi.namedAttributeGet("resAttrs".identifierGet, ???),
        )
        // ++ noInlineAttr
    )
  )
  def op(
    symName:        String,
    inputs:         Seq[Type],
    results:        Seq[Type],
    emitCInterface: Boolean
  )(
    using arena:    Arena,
    context:        Context
  ): Func =
    val location     = summon[LocationApi].locationUnknownGet
    val functionType = Type(
      org.llvm.mlir.CAPI
        .mlirFunctionTypeGet(arena, context.segment, inputs.size, inputs.toMlirArray, results.size, results.toMlirArray)
    )
    val named        = summon[NamedAttributeApi]
    Func(
      summon[OperationApi].operationCreate(
        name = "func.func",
        location = location,
        regionBlockTypeLocations = Seq(Seq((inputs, inputs.map(_ => location)))),
        resultsTypes = Some(Seq.empty),
        namedAttributes = Seq(
          named.namedAttributeGet("sym_name".identifierGet, symName.stringAttrGet),
          named.namedAttributeGet("function_type".identifierGet, functionType.typeAttrGet)
        ) ++ Option.when(emitCInterface)(
          named.namedAttributeGet("llvm.emit_c_interface".identifierGet, summon[AttributeApi].unitAttrGet)
        )
      )
    )

  extension (c:   Func)
    inline def block(
      using Arena
    ): Block = c.operation.getFirstRegion.getFirstBlock
    inline def appendToModule(
    )(
      using arena: Arena,
      module:      Module
    ): Unit =
      module.getBody.appendOwnedOperation(c.operation)
  extension (ref: Func) def operation: Operation = ref._operation
end given
