// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}

given ModuleApi with
  extension (module: Module) inline def isNull: Boolean = (!module.native).ptr == null

  inline def moduleCreateEmpty(
    location:   Location
  )(
    using zone: Zone
  ): Module = new Module(
    nativeResult[MlirStructs.MlirModule](out => mlirApi.mlirModuleCreateEmpty(location.native)(out))
  )

  inline def moduleCreateParse(
    module:     String | Array[Byte]
  )(
    using zone: Zone,
    context:    Context
  ): Module = new Module(
    nativeResult[MlirStructs.MlirModule](out =>
      mlirApi.mlirModuleCreateParse(
        context.native,
        (module match
          case string: String      => string.toStringRef
          case bytes:  Array[Byte] => bytes.toStringRef
        ).native
      )(out)
    )
  )

  inline def moduleFromOperation(
    op:         Operation
  )(
    using zone: Zone
  ): Module = new Module(nativeResult[MlirStructs.MlirModule](out => mlirApi.mlirModuleFromOperation(op.native)(out)))

  extension (module: Module)
    inline def getContext(
      using zone: Zone
    ): Context = Context(nativeResult[MlirStructs.MlirContext](out => mlirApi.mlirModuleGetContext(module.native)(out)))
    inline def getBody(
      using zone: Zone
    ) = Block(nativeResult[MlirStructs.MlirBlock](out => mlirApi.mlirModuleGetBody(module.native)(out)))
    inline def getOperation(
      using zone: Zone
    ): Operation = Operation(
      nativeResult[MlirStructs.MlirOperation](out => mlirApi.mlirModuleGetOperation(module.native)(out))
    )
    inline def destroy(): Unit = mlirApi.mlirModuleDestroy(module.native)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirModule].toInt
end given
