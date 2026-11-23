// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.support

import org.llvm.mlir.raw.structs as MlirStructs
import scala.scalanative.unsafe.*

given LogicalResultApi with
  extension (logicalResult: LogicalResult)

    inline def sizeOf:    Int     = sizeof[MlirStructs.MlirLogicalResult].toInt
    inline def succeeded: Boolean = (!logicalResult.native).value != 0
    inline def failed:    Boolean = !succeeded
end given
