// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import scala.scalanative.unsafe.*

given SymbolTableApi with
  extension (symbolTableApi: SymbolTable)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirSymbolTable].toInt
end given
