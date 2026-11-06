// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sv

import org.llvm.mlir.{CAPI, MlirAttribute, MlirType}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import java.lang.foreign.{Arena, MemorySegment}
import java.nio.charset.StandardCharsets

/** CIRCT exposes no C constructors for these SV types/attributes. Use the existing MLIR type/attribute parser only at
  * this boundary. Operations and regions are always constructed with the operation C API; no textual operation IR is
  * built.
  */
private[sv] object SVEncoding:
  def quote(value: String): String =
    "\"" + value
      .getBytes(StandardCharsets.UTF_8)
      .map { byte =>
        val unsigned = byte & 0xff
        if unsigned >= 32 && unsigned <= 126 && unsigned != 34 && unsigned != 92 then unsigned.toChar.toString
        else f"\\$unsigned%02X"
      }
      .mkString + "\""

  def show(
    tpe: Type
  )(
    using Arena
  ): String =
    val out = StringBuilder()
    tpe.print(out ++= _)
    out.toString

  def tpe(
    text:        String
  )(
    using arena: Arena,
    context:     Context
  ): Type =
    val raw = CAPI.mlirTypeParseGet(arena, context.segment, text.toStringRef.segment)
    require(MlirType.ptr(raw) != MemorySegment.NULL, s"cannot construct SV type: $text")
    Type(raw)

  def attribute(
    text:        String
  )(
    using arena: Arena,
    context:     Context
  ): Attribute =
    val raw = CAPI.mlirAttributeParseGet(arena, context.segment, text.toStringRef.segment)
    require(MlirAttribute.ptr(raw) != MemorySegment.NULL, s"cannot construct SV attribute: $text")
    Attribute(raw)
