// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.support

import org.llvm.mlir.raw.structs as MlirStructs
import scala.scalanative.unsafe.*

given TypeIDAllocatorApi with
  extension (typeIDAllocator: TypeIDAllocator)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirTypeIDAllocator].toInt
end given
