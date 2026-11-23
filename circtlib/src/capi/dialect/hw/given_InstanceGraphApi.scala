// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.hw

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.structs as CirctStructs
import org.llvm.circt.raw.functions as circtApi
import org.llvm.mlir.scalalib.capi.support.{nativeCopy, nativeResult, NativeCallbacks}
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.ir.{Module, Operation, given}

given InstanceGraphApi with
  extension (hwInstanceGraph:     HWInstanceGraph)
    inline def destroy: Unit = circtApi.hwInstanceGraphDestroy(hwInstanceGraph.native)
    inline def getTopLevelNode(
      using zone: Zone
    ): HWInstanceGraphNode = HWInstanceGraphNode(
      nativeResult[CirctStructs.HWInstanceGraphNode](out =>
        circtApi.hwInstanceGraphGetTopLevelNode(hwInstanceGraph.native)(out)
      )
    )
    inline def forEachNode(
      callback:   HWInstanceGraphNode => Unit
    )(
      using zone: Zone
    ): Unit =
      val visit = (handle: Ptr[CirctStructs.HWInstanceGraphNode]) => callback(HWInstanceGraphNode(nativeCopy(!handle)))
      NativeCallbacks.scoped(visit) { data =>
        circtApi.hwInstanceGraphForEachNode(hwInstanceGraph.native, GraphCallbacks.function(), data)
      }
  extension (operation:           Operation)
    inline def instanceGraphGet(
      using zone: Zone
    ): HWInstanceGraph = HWInstanceGraph(
      nativeResult[CirctStructs.HWInstanceGraph](out => circtApi.hwInstanceGraphGet(operation.native)(out))
    )
  extension (hwInstanceGraphNode: HWInstanceGraphNode)
    inline def equal(that: HWInstanceGraphNode): Boolean =
      circtApi.hwInstanceGraphNodeEqual(hwInstanceGraphNode.native, that.native)
    inline def getModule(
      using zone: Zone
    ): Module = new Module(
      nativeResult[MlirStructs.MlirModule](out =>
        circtApi.hwInstanceGraphNodeGetModule(hwInstanceGraphNode.native)(out)
      )
    )
    inline def getModuleOp(
      using zone: Zone
    ): Operation = Operation(
      nativeResult[MlirStructs.MlirOperation](out =>
        circtApi.hwInstanceGraphNodeGetModuleOp(hwInstanceGraphNode.native)(out)
      )
    )
end given

@extern
private object GraphCallbacks:
  @name("zaozi_graph_callback")
  def function(): org.llvm.circt.raw.aliases.HWInstanceGraphNodeCallback = extern

private object GraphVisitor:
  @exported("zaozi_graph_visit")
  def visit(node: Ptr[CirctStructs.HWInstanceGraphNode], id: Ptr[Byte]): Unit =
    val entry = NativeCallbacks.entry[Ptr[CirctStructs.HWInstanceGraphNode] => Unit](id)
    if entry.failure == null then
      try entry.value(node)
      catch case t: Throwable => entry.failure = t
