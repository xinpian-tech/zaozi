// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.support

import org.llvm.mlir.*

import java.lang.foreign.MemorySegment

given LogicalResultApi with
  extension (logicalResult: LogicalResult)
    inline def segment:   MemorySegment = logicalResult._segment
    inline def sizeOf:    Int           = MlirLogicalResult.sizeof().toInt
    // MlirLogicalResult.value: int8_t per MLIR convention — 1 = success,
    // 0 = failure. FFM zero-initialises new struct allocations, so a freshly
    // allocated MlirLogicalResult is the failure encoding by default.
    inline def succeeded: Boolean       = MlirLogicalResult.value(logicalResult._segment) == 1.toByte
    inline def failed:    Boolean       = MlirLogicalResult.value(logicalResult._segment) != 1.toByte
end given
