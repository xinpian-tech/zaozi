// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.hw

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.functions as circtApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, Type, given}

given AttributeApi with
  extension (attr: Attribute)
    inline def isInnerRefAttr:      Boolean = circtApi.hwAttrIsAInnerRefAttr(attr.native)
    inline def isInnerSymAttr:      Boolean = circtApi.hwAttrIsAInnerSymAttr(attr.native)
    inline def isOutputFileAttr:    Boolean = circtApi.hwAttrIsAOutputFileAttr(attr.native)
    inline def isParamDeclAttr:     Boolean = circtApi.hwAttrIsAParamDeclAttr(attr.native)
    inline def isParamDeclRefAttr:  Boolean = circtApi.hwAttrIsAParamDeclRefAttr(attr.native)
    inline def isParamVerbatimAttr: Boolean = circtApi.hwAttrIsAParamVerbatimAttr(attr.native)
  inline def innerRefAttrGet(
    moduleName: Attribute,
    innerSym:   Attribute
  )(
    using zone: Zone,
    context:    Context
  ): Attribute =
    Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        circtApi.hwInnerRefAttrGet(moduleName.native, innerSym.native)(out)
      )
    )
  inline def innerRefAttrGet(
    moduleName: String,
    innerSym:   String
  )(
    using zone: Zone,
    context:    Context
  ): Attribute = innerRefAttrGet(
    moduleName.stringAttrGet,
    innerSym.stringAttrGet
  )
  extension (attr: Attribute)
    inline def innerRefAttrGetModule(
      using zone: Zone
    ): Attribute =
      Attribute(nativeResult[MlirStructs.MlirAttribute](out => circtApi.hwInnerRefAttrGetModule(attr.native)(out)))
    inline def innerRefAttrGetName(
      using zone: Zone
    ): Attribute =
      Attribute(nativeResult[MlirStructs.MlirAttribute](out => circtApi.hwInnerRefAttrGetName(attr.native)(out)))
  inline def innerSymAttrGet(
    symName:    Attribute
  )(
    using zone: Zone
  ): Attribute =
    Attribute(
      nativeResult[MlirStructs.MlirAttribute](out => circtApi.hwInnerSymAttrGet(symName.native)(out))
    )
  inline def innerSymAttrGet(
    symName:    String
  )(
    using zone: Zone,
    context:    Context
  ): Attribute = innerSymAttrGet(symName.stringAttrGet)
  inline def innerSymAttrGetEmpty(
    using zone: Zone,
    context:    Context
  ): Attribute =
    Attribute(nativeResult[MlirStructs.MlirAttribute](out => circtApi.hwInnerSymAttrGetEmpty(context.native)(out)))
  extension (attr: Attribute)
    inline def innerSymAttrGetSymName(
      using zone: Zone
    ): Attribute =
      Attribute(nativeResult[MlirStructs.MlirAttribute](out => circtApi.hwInnerSymAttrGetSymName(attr.native)(out)))
  inline def outputFileGetFileName(
    outputFile: Attribute
  )(
    using zone: Zone
  ): String =
    StringRef(
      nativeResult[MlirStructs.MlirStringRef](out => circtApi.hwOutputFileGetFileName(outputFile.native)(out))
    ).toScalaString
  inline def outputFileGetFromFileName(
    text:                Attribute,
    excludeFromFileList: Boolean,
    includeReplicatedOp: Boolean
  )(
    using zone:          Zone
  ): Attribute =
    Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        circtApi.hwOutputFileGetFromFileName(text.native, excludeFromFileList, includeReplicatedOp)(out)
      )
    )
  def paramDeclAttrGet(
    name:       String,
    tpe:        Type,
    value:      Attribute
  )(
    using zone: Zone
  ): Attribute =
    Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        circtApi.hwParamDeclAttrGet(name.toStringRef.native, tpe.native, value.native)(out)
      )
    )
  def paramDeclAttrGetName(
    decl:       Attribute
  )(
    using zone: Zone
  ): String = StringRef(
    nativeResult[MlirStructs.MlirStringRef](out => circtApi.hwParamDeclAttrGetName(decl.native)(out))
  ).toScalaString
  def paramDeclAttrGetType(
    decl:       Attribute
  )(
    using zone: Zone
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.hwParamDeclAttrGetType(decl.native)(out)))
  def paramDeclAttrGetValue(
    decl:       Attribute
  )(
    using zone: Zone
  ): Attribute = Attribute(
    nativeResult[MlirStructs.MlirAttribute](out => circtApi.hwParamDeclAttrGetValue(decl.native)(out))
  )
  def paramDeclRefAttrGet(
    cName:      String
  )(
    using zone: Zone,
    context:    Context
  ): Attribute = Attribute(
    nativeResult[MlirStructs.MlirAttribute](out =>
      circtApi.hwParamDeclRefAttrGet(context.native, cName.toStringRef.native)(out)
    )
  )
  def paramDeclRefAttrGetName(
    decl:       Attribute
  )(
    using zone: Zone
  ): String = StringRef(
    nativeResult[MlirStructs.MlirStringRef](out => circtApi.hwParamDeclRefAttrGetName(decl.native)(out))
  ).toScalaString
  def paramDeclRefAttrGetType(
    decl:       Attribute
  )(
    using zone: Zone
  ): Type = Type(nativeResult[MlirStructs.MlirType](out => circtApi.hwParamDeclRefAttrGetType(decl.native)(out)))
  def paramVerbatimAttrGet(
    text:       Attribute
  )(
    using zone: Zone
  ): Attribute = Attribute(
    nativeResult[MlirStructs.MlirAttribute](out => circtApi.hwParamVerbatimAttrGet(text.native)(out))
  )
end given
