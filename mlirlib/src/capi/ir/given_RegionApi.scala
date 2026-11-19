// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import org.llvm.mlir.scalalib.capi.support.nativeResult
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

given RegionApi with
  inline def regionCreate(
    using zone: Zone
  ): Region =
    Region(nativeResult[MlirStructs.MlirRegion](out => mlirApi.mlirRegionCreate()(out)))

  extension (region: Region)
    inline def getFirstBlock(
      using zone: Zone
    ): Block = Block(nativeResult[MlirStructs.MlirBlock](out => mlirApi.mlirRegionGetFirstBlock(region.native)(out)))
    inline def getNextInOperation(
      using zone: Zone
    ): Region = Region(
      nativeResult[MlirStructs.MlirRegion](out => mlirApi.mlirRegionGetNextInOperation(region.native)(out))
    )
    inline def destroy(
    )(
      using zone: Zone
    ): Unit = mlirApi.mlirRegionDestroy(region.native)
    inline def appendOwnedBlock(block: Block):                         Unit =
      mlirApi.mlirRegionAppendOwnedBlock(region.native, block.native)
    inline def insertOwnedBlock(pos: Long, block: Block):              Unit =
      mlirApi.mlirRegionInsertOwnedBlock(region.native, pos.toSize, block.native)
    inline def insertOwnedBlockAfter(reference: Block, block: Block):  Unit =
      mlirApi.mlirRegionInsertOwnedBlockAfter(region.native, reference.native, block.native)
    inline def insertOwnedBlockBefore(reference: Block, block: Block): Unit =
      mlirApi.mlirRegionInsertOwnedBlockBefore(region.native, reference.native, block.native)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirRegion].toInt

end given
