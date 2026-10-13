// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv

import me.jiuyang.zaozi.InstanceContext
import me.jiuyang.zaozi.default.bundleFieldName
import org.llvm.mlir.scalalib.capi.ir.*
import java.lang.foreign.Arena

/** Named object-like and function-like Verilog macros, including externally supplied definitions. */
abstract class VerilogMacroInterface:
  protected def VerilogMacro(
  )(
    using sourcecode.Name.Machine
  ): MacroRef = new MacroRef(bundleFieldName, None)

  protected def VerilogFunctionMacro(
    arguments: String*
  )(
    using sourcecode.Name.Machine
  ): MacroRef =
    new MacroRef(bundleFieldName, Some(arguments.toSeq))

final class MacroRef private[sv] (val name: String, private[sv] val arguments: Option[Seq[String]]):
  /** Interpret the external macro expansion as an integer constant expression. */
  def value: ConstExpr = ConstExpr.MacroValue(name)

  /** Bind a preprocessing result; this does not emit a Verilog define. */
  def bind(definition: MacroDefinition): MacroBinding = MacroBinding(name, definition)
  def bind(value:      Int):             MacroBinding = bind(MacroDefinition.Integer(value))
  private[sv] def symbolName:            String       = name

final class ConditionalCompilation private[sv] (private[sv] val operation: Operation)

final class GenerateCaseContext private[sv] ():
  private[sv] val branches = collection.mutable.ArrayBuffer.empty[GenerateBranch]
private[sv] case class GenerateBranch(pattern: Option[ConstExpr], name: String, body: Block => Unit)

trait CompileTimeApi:
  /** Generate a Verilog loop with an exclusive upper bound; the Scala body builds one iteration region. */
  def generateFor(
    lower: ConstExpr,
    upper: ConstExpr,
    step:  ConstExpr = ConstExpr(1)
  )(body:  me.jiuyang.zaozi.sv.reftpe.Node[me.jiuyang.zaozi.sv.valuetpe.Bits] => Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    InstanceContext,
    sourcecode.Name.Machine,
    sourcecode.File,
    sourcecode.Line
  ):   Unit
  def ifdef(
    macroRef: MacroRef
  )(body:     Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    SVElaborationContext,
    sourcecode.File,
    sourcecode.Line
  ):   ConditionalCompilation
  extension (branch: ConditionalCompilation)
    def otherwise(
      body: Block ?=> Unit
    )(
      using Arena,
      Context
    ): Unit
  def generateCase(
    selector: ConstExpr
  )(body:     GenerateCaseContext ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    InstanceContext,
    sourcecode.Name.Machine,
    sourcecode.File,
    sourcecode.Line
  ):   Unit
  def is(
    pattern: ConstExpr
  )(body:    Block ?=> Unit
  )(
    using GenerateCaseContext,
    InstanceContext,
    sourcecode.Name.Machine
  ):   Unit
  def defaultCase(
    body: Block ?=> Unit
  )(
    using GenerateCaseContext,
    InstanceContext,
    sourcecode.Name.Machine
  ):   Unit
