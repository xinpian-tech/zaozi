// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sv.attr

import org.llvm.circt.scalalib.dialect.sv.SVEncoding
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, given}
import java.lang.foreign.{Arena, ValueLayout}

given AttributeApi with
  extension (ref: EventControl)
    def toAttribute(
      using Arena,
      Context
    ): Attribute = ref.attrValue.integerAttrGet(32.integerTypeGet)
  extension (ref: ResetStyle)
    def toAttribute(
      using Arena,
      Context
    ): Attribute = ref.attrValue.integerAttrGet(32.integerTypeGet)
  extension (ref: CaseStyle)
    def toAttribute(
      using Arena,
      Context
    ): Attribute = ref.attrValue.integerAttrGet(32.integerTypeGet)
  extension (ref: DeferAssert)
    def toAttribute(
      using Arena,
      Context
    ): Attribute = ref.attrValue.integerAttrGet(32.integerTypeGet)
  extension (ref: MemoryBase)
    def toAttribute(
      using Arena,
      Context
    ): Attribute = ref.attrValue.integerAttrGet(32.integerTypeGet)
  extension (ref: Verbosity)
    def toAttribute(
      using Arena,
      Context
    ): Attribute = ref.attrValue.integerAttrGet(8.integerTypeGet)
  extension (ref: ValidationQualifier)
    def toAttribute(
      using Arena,
      Context
    ): Attribute = SVEncoding.attribute(s"#sv<validation_qualifier ${ref.attrValue}>")
  extension (ref: IncludeStyle)
    def toAttribute(
      using Arena,
      Context
    ): Attribute = SVEncoding.attribute(s"#sv<include_style ${ref.attrValue}>")
  extension (ref: ModportDirection)
    def toAttribute(
      using Arena,
      Context
    ): Attribute = SVEncoding.attribute(s"#sv<modport_direction ${ref.attrValue}>")
  def getMacroIdent(
    name: String
  )(
    using Arena,
    Context
  ): Attribute = SVEncoding.attribute(s"#sv<macro.ident @${SVEncoding.quote(name)}>")
  def getModportStruct(
    direction: ModportDirection,
    signal:    String
  )(
    using Arena,
    Context
  ): Attribute =
    SVEncoding.attribute(s"#sv.mod_port<direction = ${direction.attrValue}, signal = @${SVEncoding.quote(signal)}>")
  def getCaseExprPattern(
    using Arena,
    Context
  ): Attribute = SVEncoding.attribute("#sv.expr")
  def getCaseDefault(
    using Arena,
    Context
  ): Attribute = summon[org.llvm.mlir.scalalib.capi.ir.AttributeApi].unitAttrGet
  def getCasePattern(
    bits:        String
  )(
    using arena: Arena,
    context:     Context
  ): Attribute =
    require(bits.nonEmpty, "a case bit pattern must have a width")
    val encoded = bits.toLowerCase.foldLeft(BigInt(0)) { (value, bit) =>
      val code = bit match
        case '0' => 0
        case '1' => 1
        case 'x' => 2
        case 'z' => 3
        case _   => throw new IllegalArgumentException(s"invalid case bit: $bit")
      (value << 2) | code
    }
    val width   = bits.length * 2
    val count   = (width + 63) / 64
    val words   = arena.allocate(ValueLayout.JAVA_LONG, count.toLong)
    (0 until count).foreach(i => words.setAtIndex(ValueLayout.JAVA_LONG, i.toLong, (encoded >> (64 * i)).toLong))
    Attribute(org.llvm.mlir.CAPI.mlirIntegerAttrGetFromWords(arena, width.integerTypeGet.segment, count, words))
end given
