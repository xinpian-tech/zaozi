// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

// circt-c/Dialect/SV.h
package org.llvm.circt.scalalib.capi.dialect.sv

import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context}

import java.lang.foreign.Arena

/** SV Dialect API
  * {{{
  * mlirGetDialectHandle__sv__
  * registerSVPasses
  * }}}
  */
trait DialectApi:
  inline def loadDialect(
    using arena: Arena,
    context:     Context
  ):                  Unit
  def registerPasses: Unit
end DialectApi

/** SV Attribute API
  * {{{
  * svAttrIsASVAttributeAttr
  * svSVAttributeAttrGet
  * svSVAttributeAttrGetName
  * svSVAttributeAttrGetExpression
  * svSVAttributeAttrGetEmitAsComment
  * }}}
  */
trait AttributeApi:
  inline def getSVAttribute(
    name:          String,
    expression:    Option[String] = None,
    emitAsComment: Boolean = false
  )(
    using arena:   Arena,
    context:       Context
  ): Attribute
  extension (attribute: Attribute)
    inline def isSVAttribute: Boolean
    inline def getSVAttributeName(
      using arena: Arena
    ):                        String

    /** Maps the native null StringRef to None. CIRCT's getter also returns null for a present empty expression; this
      * getter cannot distinguish it from an absent expression, although the constructor preserves the distinction in
      * IR.
      */
    inline def getSVAttributeExpression(
      using arena: Arena
    ):                                      Option[String]
    inline def getSVAttributeEmitAsComment: Boolean

end AttributeApi
