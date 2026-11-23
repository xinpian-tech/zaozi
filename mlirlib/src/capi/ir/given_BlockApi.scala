// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import org.llvm.mlir.scalalib.capi.support.{*, given}

given BlockApi with
  inline def blockCreate(
    args:       Seq[Type],
    locs:       Seq[Location]
  )(
    using zone: Zone
  ): Block =
    Block(
      nativeResult[MlirStructs.MlirBlock](out =>
        mlirApi.mlirBlockCreate(args.length.toSize, args.toMlirArray, locs.toMlirArray)(out)
      )
    )

  extension (block: Block)
    inline def getParentOperation(
      using zone: Zone
    ): Operation = Operation(
      nativeResult[MlirStructs.MlirOperation](out => mlirApi.mlirBlockGetParentOperation(block.native)(out))
    )
    inline def getNextInRegion(
      using zone: Zone
    ): Block = Block(nativeResult[MlirStructs.MlirBlock](out => mlirApi.mlirBlockGetNextInRegion(block.native)(out)))
    inline def getFirstOperation(
      using zone: Zone
    ): Operation = Operation(
      nativeResult[MlirStructs.MlirOperation](out => mlirApi.mlirBlockGetFirstOperation(block.native)(out))
    )
    inline def getTerminator(
      using zone: Zone
    ): Operation = Operation(
      nativeResult[MlirStructs.MlirOperation](out => mlirApi.mlirBlockGetTerminator(block.native)(out))
    )
    inline def addArgument(
      tpe:        Type,
      loc:        Location
    )(
      using zone: Zone
    ): Value = Value(
      nativeResult[MlirStructs.MlirValue](out =>
        mlirApi.mlirBlockAddArgument(block.native, tpe.native, loc.native)(out)
      )
    )
    inline def insertArgument(
      pos:        Int,
      tpe:        Type,
      loc:        Location
    )(
      using zone: Zone
    ): Value = Value(
      nativeResult[MlirStructs.MlirValue](out =>
        mlirApi.mlirBlockInsertArgument(block.native, pos.toSize, tpe.native, loc.native)(out)
      )
    )
    inline def getArgument(
      pos:        Long
    )(
      using zone: Zone
    ): Value = Value(
      nativeResult[MlirStructs.MlirValue](out => mlirApi.mlirBlockGetArgument(block.native, pos.toSize)(out))
    )
    inline def destroy():                                                              Unit = mlirApi.mlirBlockDestroy(block.native)
    inline def detach():                                                               Unit = mlirApi.mlirBlockDetach(block.native)
    inline def appendOwnedOperation(operation: Operation):                             Unit =
      mlirApi.mlirBlockAppendOwnedOperation(block.native, operation.native)
    inline def insertOwnedOperation(pos: Long, operation: Operation):                  Unit =
      mlirApi.mlirBlockInsertOwnedOperation(block.native, pos.toSize, operation.native)
    inline def insertOwnedOperationAfter(reference: Operation, operation: Operation):  Unit =
      mlirApi.mlirBlockInsertOwnedOperationAfter(block.native, reference.native, operation.native)
    inline def insertOwnedOperationBefore(reference: Operation, operation: Operation): Unit =
      mlirApi.mlirBlockInsertOwnedOperationBefore(block.native, reference.native, operation.native)
    inline def eraseArgument(index: Int):                                              Unit = mlirApi.mlirBlockEraseArgument(block.native, index.toUInt)
    inline def print(
      callBack:   String => Unit
    )(
      using zone: Zone
    ): Unit = callBack.stringToStringCallback.use { (fn, data) => mlirApi.mlirBlockPrint(block.native, fn, data) }

    inline def sizeOf: Int = sizeof[MlirStructs.MlirBlock].toInt

end given
