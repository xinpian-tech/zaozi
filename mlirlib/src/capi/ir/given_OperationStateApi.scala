// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import org.llvm.mlir.scalalib.capi.support.{*, given}

given OperationStateApi with
  inline def operationStateGet(
    name:       String,
    loc:        Location
  )(
    using zone: Zone
  ): OperationState =
    OperationState(
      nativeResult[MlirStructs.MlirOperationState](out =>
        mlirApi.mlirOperationStateGet(name.toStringRef.native, loc.native)(out)
      )
    )

  extension (operationState: OperationState)
    inline def addResults(
      results:    Seq[Type]
    )(
      using zone: Zone
    ): Unit =
      mlirApi.mlirOperationStateAddResults(operationState.native, results.size.toSize, results.toMlirArray)
    inline def addOperands(
      operands:   Seq[Value]
    )(
      using zone: Zone
    ): Unit =
      mlirApi.mlirOperationStateAddOperands(operationState.native, operands.size.toSize, operands.toMlirArray)
    inline def addOwnedRegions(
      regions:    Seq[Region]
    )(
      using zone: Zone
    ): Unit =
      mlirApi.mlirOperationStateAddOwnedRegions(operationState.native, regions.size.toSize, regions.toMlirArray)
    inline def addSuccessors(
      blocks:     Seq[Block]
    )(
      using zone: Zone
    ): Unit =
      mlirApi.mlirOperationStateAddSuccessors(operationState.native, blocks.size.toSize, blocks.toMlirArray)
    inline def addAttributes(
      attrs:      Seq[NamedAttribute]
    )(
      using zone: Zone
    ): Unit =
      mlirApi.mlirOperationStateAddAttributes(operationState.native, attrs.size.toSize, attrs.toMlirArray)
    inline def enableResultTypeInference(): Unit =
      mlirApi.mlirOperationStateEnableResultTypeInference(operationState.native)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirOperationState].toInt
end given
