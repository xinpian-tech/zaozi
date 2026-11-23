// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import scala.scalanative.unsafe.*

given OpOperandApi with
  extension (opOperandApi: OpOperand)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirOpOperand].toInt
end given
