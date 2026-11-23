// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.hw

import org.llvm.circt.raw.structs as CirctStructs
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Context, Module, Operation, given}

class InstanceGraphNodeCallback(val run: HWInstanceGraphNode => Unit)
trait HWInstanceGraphNodeCallbackApi:
  extension (hwInstanceGraphNodeCallback: HWInstanceGraphNode => Unit)
    inline def toHWInstanceGraphNodeCallback(
      using zone: Zone
    ): InstanceGraphNodeCallback

class HWInstanceGraph(handle: Ptr[CirctStructs.HWInstanceGraph]) extends NativeHandle(handle)
trait HWInstanceGraphApi                                         extends HasSizeOf[HWInstanceGraph]

class HWInstanceGraphNode(handle: Ptr[CirctStructs.HWInstanceGraphNode]) extends NativeHandle(handle)
trait HWInstanceGraphNodeApi                                             extends HasSizeOf[HWInstanceGraphNode]

class HWModulePort(handle: Ptr[CirctStructs.HWModulePort]) extends NativeHandle(handle)
trait HWModulePortApi                                      extends HasSizeOf[HWModulePort]

class HWStructFieldInfo(handle: Ptr[CirctStructs.HWStructFieldInfo]) extends NativeHandle(handle)
trait HWStructFieldInfoApi                                           extends HasSizeOf[HWStructFieldInfo]

/** HW Dialect API
  * {{{
  * mlirGetDialectHandle__hw__
  * registerHWPasses
  * }}}
  */
trait DialectApi:
  inline def loadDialect(
    using zone: Zone,
    context:    Context
  ):                  Unit
  def registerPasses: Unit
end DialectApi

/** HW Type API
  * {{{
  * hwArrayTypeGet
  * hwArrayTypeGetElementType
  * hwArrayTypeGetSize
  * hwGetBitWidth
  * hwInOutTypeGet
  * hwInOutTypeGetElementType
  * hwModuleTypeGet
  * hwModuleTypeGetInputName
  * hwModuleTypeGetInputType
  * hwModuleTypeGetNumInputs
  * hwModuleTypeGetNumOutputs
  * hwModuleTypeGetOutputName
  * hwModuleTypeGetOutputType
  * hwParamIntTypeGet
  * hwParamIntTypeGetWidthAttr
  * hwStructTypeGet
  * hwStructTypeGetField
  * hwStructTypeGetNumFields
  * hwTypeAliasTypeGet
  * hwTypeAliasTypeGetCanonicalType
  * hwTypeAliasTypeGetInnerType
  * hwTypeAliasTypeGetName
  * hwTypeAliasTypeGetScope
  * hwTypeIsAArrayType
  * hwTypeIsAInOut
  * hwTypeIsAIntType
  * hwTypeIsAModuleType
  * hwTypeIsAStructType
  * hwTypeIsATypeAliasType
  * hwTypeIsAValueType
  * }}}
  */
trait TypeApi:
end TypeApi

/** HW Attribute API
  * {{{
  * hwAttrIsAInnerRefAttr
  * hwAttrIsAInnerSymAttr
  * hwAttrIsAOutputFileAttr
  * hwAttrIsAParamDeclAttr
  * hwAttrIsAParamDeclRefAttr
  * hwAttrIsAParamVerbatimAttr
  * hwInnerRefAttrGet
  * hwInnerRefAttrGetModule
  * hwInnerRefAttrGetName
  * hwInnerSymAttrGet
  * hwInnerSymAttrGetEmpty
  * hwInnerSymAttrGetSymName
  * hwOutputFileGetFileName
  * hwOutputFileGetFromFileName
  * hwParamDeclAttrGet
  * hwParamDeclAttrGetName
  * hwParamDeclAttrGetType
  * hwParamDeclAttrGetValue
  * hwParamDeclRefAttrGet
  * hwParamDeclRefAttrGetName
  * hwParamDeclRefAttrGetType
  * hwParamVerbatimAttrGet
  * }}}
  */
trait AttributeApi:
end AttributeApi

/** InstanceGraph API
  * {{{
  * hwInstanceGraphGet
  * hwInstanceGraphDestroy
  * hwInstanceGraphGetTopLevelNode
  * hwInstanceGraphForEachNode
  * hwInstanceGraphNodeEqual
  * hwInstanceGraphNodeGetModule
  * hwInstanceGraphNodeGetModuleOp
  * }}}
  */
trait InstanceGraphApi:
  extension (hwInstanceGraph:     HWInstanceGraph)
    inline def destroy: Unit
    inline def getTopLevelNode(
      using zone: Zone
    ):                  HWInstanceGraphNode
    inline def forEachNode(
      callback:   HWInstanceGraphNode => Unit
    )(
      using zone: Zone
    ):                  Unit
  extension (operation:           Operation)
    inline def instanceGraphGet(
      using zone: Zone
    ):     HWInstanceGraph
  extension (hwInstanceGraphNode: HWInstanceGraphNode)
    inline def equal(that: HWInstanceGraphNode): Boolean
    inline def getModule(
      using zone: Zone
    ):                                           Module
    inline def getModuleOp(
      using zone: Zone
    ):                                           Operation
end InstanceGraphApi
