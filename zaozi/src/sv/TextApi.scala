// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv

import me.jiuyang.zaozi.InstanceContext
import me.jiuyang.zaozi.sv.valuetpe.*
import me.jiuyang.zaozi.sv.reftpe.*
import org.llvm.mlir.scalalib.capi.ir.*
import java.lang.foreign.Arena

trait TextApi:
  def verbatim(
    text:    String,
    values:  Seq[Referable[?]] = Seq.empty,
    symbols: Seq[VerbatimSymbol] = Seq.empty
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Unit
  def verbatimExpr[T <: Data](
    tpe:         T,
    text:        String,
    values:      Seq[Referable[?]] = Seq.empty,
    symbols:     Seq[VerbatimSymbol] = Seq.empty,
    sideEffects: Boolean = false
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Node[T]
  def include(
    path:   String,
    system: Boolean = false
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Unit
  def macroError(
    message: String
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Unit
  extension (macroRef: MacroRef)
    def define(
      body:    String,
      symbols: Seq[VerbatimSymbol] = Seq.empty
    )(
      using Arena,
      Context,
      Block,
      SVElaborationContext,
      sourcecode.File,
      sourcecode.Line
    ): Unit
    def call(
      values: Referable[?]*
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line,
      SVElaborationContext
    ): Unit
    def expr[T <: Data](
      tpe:         T,
      sideEffects: Boolean = false
    )(values:      Referable[?]*
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line,
      SVElaborationContext
    ): Node[T]

/** A global symbol, or a named HW inner symbol when member is supplied. */
case class VerbatimSymbol(name: String, member: Option[String] = None):
  private[sv] def toAttribute(
    using Arena,
    Context
  ): Attribute =
    import org.llvm.mlir.scalalib.capi.ir.given
    member.fold(name.flatSymbolRefAttrGet)(field =>
      org.llvm.circt.scalalib.capi.dialect.hw.given_AttributeApi.innerRefAttrGet(name, field)
    )

/** Source for one separately compiled module. Content is Verilog, never textual MLIR. */
case class VerilogSource(content: String, fileName: String)
