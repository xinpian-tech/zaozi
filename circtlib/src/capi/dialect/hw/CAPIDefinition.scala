// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.hw

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Context, Module, Operation, given}

import java.lang.foreign.{Arena, MemorySegment}

class InstanceGraphNodeCallback(val _segment: MemorySegment)
trait HWInstanceGraphNodeCallbackApi extends HasSegment[InstanceGraphNodeCallback]:
  extension (hwInstanceGraphNodeCallback: HWInstanceGraphNode => Unit)
    inline def toHWInstanceGraphNodeCallback(
      using arena: Arena
    ): InstanceGraphNodeCallback

class HWInstanceGraph(val _segment: MemorySegment)
trait HWInstanceGraphApi extends HasSegment[HWInstanceGraph] with HasSizeOf[HWInstanceGraph]

class HWInstanceGraphNode(val _segment: MemorySegment)
trait HWInstanceGraphNodeApi extends HasSegment[HWInstanceGraphNode] with HasSizeOf[HWInstanceGraphNode]

class HWModulePort(val _segment: MemorySegment)
trait HWModulePortApi extends HasSegment[HWModulePort] with HasSizeOf[HWModulePort]

class HWStructFieldInfo(val _segment: MemorySegment)
trait HWStructFieldInfoApi extends HasSegment[HWStructFieldInfo] with HasSizeOf[HWStructFieldInfo]

/** HW Dialect API
  * {{{
  * mlirGetDialectHandle__hw__
  * registerHWPasses
  * }}}
  */
trait DialectApi:
  inline def loadDialect(
    using arena: Arena,
    context:     Context
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
      using arena: Arena
    ):                  HWInstanceGraphNode
    inline def forEachNode(
      callback:    HWInstanceGraphNode => Unit
    )(
      using arena: Arena
    ):                  Unit
  extension (operation:           Operation)
    inline def instanceGraphGet(
      using arena: Arena
    ):     HWInstanceGraph
  extension (hwInstanceGraphNode: HWInstanceGraphNode)
    inline def equal(that: HWInstanceGraphNode): Boolean
    inline def getModule(
      using arena: Arena
    ):                                           Module
    inline def getModuleOp(
      using arena: Arena
    ):                                           Operation
end InstanceGraphApi
