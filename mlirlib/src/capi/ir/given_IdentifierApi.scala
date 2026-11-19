// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}

given IdentifierApi with
  extension (identifierString: String)
    inline def identifierGet(
      using zone: Zone,
      context:    Context
    ): Identifier =
      Identifier(
        nativeResult[MlirStructs.MlirIdentifier](out =>
          mlirApi.mlirIdentifierGet(context.native, identifierString.toStringRef.native)(out)
        )
      )
  extension (identifier:       Identifier)
    inline def getContext(
      using zone: Zone
    ): Context = Context(
      nativeResult[MlirStructs.MlirContext](out => mlirApi.mlirIdentifierGetContext(identifier.native)(out))
    )
    inline def str(
      using zone: Zone
    ): String = StringRef(
      nativeResult[MlirStructs.MlirStringRef](out => mlirApi.mlirIdentifierStr(identifier.native)(out))
    ).toScalaString

    inline def sizeOf: Int = sizeof[MlirStructs.MlirIdentifier].toInt
end given
