// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.support

import org.llvm.mlir.raw.structs as MlirStructs
import scala.scalanative.unsafe.*

given TypeIDApi with
  extension (typeID: TypeID)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirTypeID].toInt
end given
