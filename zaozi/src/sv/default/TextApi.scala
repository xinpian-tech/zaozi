// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.default

import me.jiuyang.zaozi.InstanceContext
import me.jiuyang.zaozi.default.{locate, valName}
import me.jiuyang.zaozi.sv.*
import me.jiuyang.zaozi.sv.valuetpe.*
import me.jiuyang.zaozi.sv.reftpe.*
import org.llvm.circt.scalalib.dialect.sv.operation.{MacroRef as _, *, given}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import java.lang.foreign.Arena

given TextApi with
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
  ): Unit =
    summon[VerbatimApi].op(text, values.map(_.refer), symbols.map(_.toAttribute), locate).operation.appendToBlock()
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
  ): Node[T] =
    val op =
      if sideEffects then
        summon[VerbatimExprSEApi]
          .op(text, values.map(_.refer), symbols.map(_.toAttribute), tpe.toMlirType, locate)
          .operation
      else
        summon[VerbatimExprApi]
          .op(text, values.map(_.refer), symbols.map(_.toAttribute), tpe.toMlirType, locate)
          .operation
    emitted(tpe, op)
  def include(
    path:   String,
    system: Boolean = false
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    import org.llvm.circt.scalalib.dialect.sv.attr.IncludeStyle
    summon[IncludeApi]
      .op(if system then IncludeStyle.System else IncludeStyle.Local, path, locate)
      .operation
      .appendToBlock()
  def macroError(
    message: String
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    summon[MacroErrorApi].op(Some(message), locate).operation.appendToBlock()
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
    ): Unit =
      declareMacro(macroRef)
      summon[MacroDefApi].op(macroRef.symbolName, body, symbols.map(_.toAttribute), locate).operation.appendToBlock()
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
    ): Unit =
      declareMacro(macroRef)
      summon[VerbatimApi]
        .op(
          macroCallText(macroRef, values.size),
          values.map(_.refer),
          Seq(macroRef.symbolName.flatSymbolRefAttrGet),
          locate
        )
        .operation
        .appendToBlock()
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
    ): Node[T] =
      declareMacro(macroRef)
      // The pinned exporter recursively re-enters emitExpression for macro arguments and aborts.
      // Verbatim expressions preserve the macro symbol, arguments and side-effect contract.
      verbatimExpr(
        tpe,
        macroCallText(macroRef, values.size),
        values,
        Seq(VerbatimSymbol(macroRef.symbolName)),
        sideEffects
      )

private def macroCallText(macroRef: MacroRef, count: Int): String =
  require(macroRef.arguments.fold(count == 0)(_.size == count), s"Wrong argument count for macro ${macroRef.name}")
  val name = "`{{" + count + "}}"
  macroRef.arguments.fold(name)(_ => name + (0 until count).map(i => "{{" + i + "}}").mkString("(", ", ", ")"))
