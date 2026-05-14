// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.hw

import java.lang.foreign.{Arena, MemorySegment}

given HWModulePortApi with
  extension (ref: HWModulePort)
    inline def segment:       MemorySegment = ref._segment
    inline def sizeOf:        Int           = org.llvm.circt.HWModulePort.sizeof().toInt
    // The struct-field accessors view the embedded MlirAttribute / MlirType
    // segments in place — they are slices of the HWModulePort segment, valid
    // as long as the enclosing HWModulePort arena is alive.
    inline def portName:      MemorySegment = org.llvm.circt.HWModulePort.name(ref._segment)
    inline def portType:      MemorySegment = org.llvm.circt.HWModulePort.`type`(ref._segment)
    // HWModulePortDirection: Input=0, Output=1, InOut=2.
    inline def portDirection: Int           = org.llvm.circt.HWModulePort.dir(ref._segment)
end given

given HWStructFieldInfoApi with
  extension (ref: HWStructFieldInfo)
    inline def segment: MemorySegment = ref._segment
    inline def sizeOf:  Int           = org.llvm.circt.HWStructFieldInfo.sizeof().toInt
end given

given HWInstanceGraphNodeCallbackApi with
  extension (hwInstanceGraphNodeCallback: HWInstanceGraphNode => Unit)
    inline def toHWInstanceGraphNodeCallback(
      using arena: Arena
    ): InstanceGraphNodeCallback =
      InstanceGraphNodeCallback(
        org.llvm.circt.HWInstanceGraphNodeCallback.allocate(
          (hwInstanceGraphNode: MemorySegment, userData: MemorySegment) =>
            try hwInstanceGraphNodeCallback(HWInstanceGraphNode(hwInstanceGraphNode))
            catch
              case t: Throwable =>
                System.err.println(s"[zaozi:upcall:instance-graph-node] ${t.getClass.getName}: ${t.getMessage}")
                t.printStackTrace(System.err)
          ,
          arena
        )
      )
  extension (hwInstanceGraphNodeCallback: InstanceGraphNodeCallback)
    inline def segment: MemorySegment = hwInstanceGraphNodeCallback._segment
end given

given HWInstanceGraphApi with
  extension (ref: HWInstanceGraph)
    inline def segment: MemorySegment = ref._segment
    inline def sizeOf:  Int           = org.llvm.circt.HWInstanceGraph.sizeof().toInt
end given

given HWInstanceGraphNodeApi with
  extension (ref: HWInstanceGraphNode)
    inline def segment: MemorySegment = ref._segment
    inline def sizeOf:  Int           = org.llvm.circt.HWInstanceGraphNode.sizeof().toInt
end given
