// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import org.llvm.mlir.scalalib.capi.support.nativeResult
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

given OpPrintingFlagsApi with
  inline def opPrintingFlagsCreate(
  )(
    using zone: Zone
  ): OpPrintingFlags = OpPrintingFlags(
    nativeResult[MlirStructs.MlirOpPrintingFlags](out => mlirApi.mlirOpPrintingFlagsCreate()(out))
  )

  extension (opPrintingFlags: OpPrintingFlags)
    inline def flagsDestroy():                                             Unit = mlirApi.mlirOpPrintingFlagsDestroy(opPrintingFlags.native)
    inline def flagsElideLargeElementsAttrs(largeElementLimit: Long):      Unit =
      mlirApi.mlirOpPrintingFlagsElideLargeElementsAttrs(opPrintingFlags.native, largeElementLimit.toSize)
    inline def flagsElideLargeResourceString(largeResourceLimit: Long):    Unit =
      mlirApi.mlirOpPrintingFlagsElideLargeResourceString(opPrintingFlags.native, largeResourceLimit.toSize)
    inline def flagsEnableDebugInfo(enable: Boolean, prettyForm: Boolean): Unit =
      mlirApi.mlirOpPrintingFlagsEnableDebugInfo(opPrintingFlags.native, enable, prettyForm)
    inline def flagsPrintGenericOpForm():                                  Unit = mlirApi.mlirOpPrintingFlagsPrintGenericOpForm(opPrintingFlags.native)
    inline def flagsUseLocalScope():                                       Unit = mlirApi.mlirOpPrintingFlagsUseLocalScope(opPrintingFlags.native)
    inline def flagsAssumeVerified():                                      Unit = mlirApi.mlirOpPrintingFlagsAssumeVerified(opPrintingFlags.native)
    inline def flagsSkipRegions():                                         Unit = mlirApi.mlirOpPrintingFlagsSkipRegions(opPrintingFlags.native)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirOpPrintingFlags].toInt
end given
