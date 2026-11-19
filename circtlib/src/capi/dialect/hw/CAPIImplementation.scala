// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.hw

import org.llvm.circt.raw.structs as CirctStructs
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.nativeCopy
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Type}

given HWModulePortApi with
  extension (ref: HWModulePort)

    inline def sizeOf:        Int = sizeof[CirctStructs.HWModulePort].toInt
    inline def portName(
      using Zone
    ): Attribute = Attribute(nativeCopy((!ref.native).name))
    inline def portType(
      using Zone
    ): Type = Type(nativeCopy((!ref.native).`type`))
    inline def portDirection: Int = (!ref.native).dir.int
end given

given HWStructFieldInfoApi with
  extension (ref: HWStructFieldInfo)

    inline def sizeOf: Int = sizeof[CirctStructs.HWStructFieldInfo].toInt
end given

given HWInstanceGraphNodeCallbackApi with
  extension (hwInstanceGraphNodeCallback: HWInstanceGraphNode => Unit)
    inline def toHWInstanceGraphNodeCallback(
      using zone: Zone
    ): InstanceGraphNodeCallback =
      InstanceGraphNodeCallback(hwInstanceGraphNodeCallback)
end given

given HWInstanceGraphApi with
  extension (ref: HWInstanceGraph)

    inline def sizeOf: Int = sizeof[CirctStructs.HWInstanceGraph].toInt
end given

given HWInstanceGraphNodeApi with
  extension (ref: HWInstanceGraphNode)

    inline def sizeOf: Int = sizeof[CirctStructs.HWInstanceGraphNode].toInt
end given
