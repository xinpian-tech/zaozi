// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.default

import me.jiuyang.zaozi.InstanceContext
import me.jiuyang.zaozi.default.{locate, valName}
import me.jiuyang.zaozi.sv.*
import org.llvm.circt.scalalib.dialect.sv.operation.{
  GenerateApi,
  GenerateCaseApi,
  GenerateForApi,
  IfDefApi,
  MacroDeclApi,
  given
}
import org.llvm.circt.scalalib.dialect.sv.attr.{AttributeApi as SVAttributes, given}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import java.lang.foreign.Arena

given CompileTimeApi with
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
  ): Unit =
    val name  = valName
    val scope = summon[GenerateApi].op(name + "_scope", locate)
    scope.operation.appendToBlock()
    val loop  = org.llvm.circt.scalalib.dialect.sv.operation.given_GenerateForApi
      .op(lower.toAttribute, upper.toAttribute, step.toAttribute, name, Some(name + "_index"), locate)
    loop.operation.appendToBlock()(
      using scope.block
    )
    val index = new me.jiuyang.zaozi.sv.reftpe.Node[me.jiuyang.zaozi.sv.valuetpe.Bits]:
      private[zaozi] val _tpe   = Bits(32)
      private[zaozi] val _refer = loop.inductionVar
    body(index)(
      using loop.block
    )
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
  ): ConditionalCompilation =
    declareMacro(macroRef)
    val condition = summon[SVAttributes].getMacroIdent(macroRef.symbolName)
    val op        =
      if procedural(summon[Block]) then
        summon[org.llvm.circt.scalalib.dialect.sv.operation.IfDefProceduralApi]
          .op(condition, location = locate)
          .operation
      else summon[IfDefApi].op(condition, location = locate).operation
    op.appendToBlock()
    body(
      using op.getRegion(0).getFirstBlock
    )
    new ConditionalCompilation(op)

  extension (branch: ConditionalCompilation)
    def otherwise(
      body: Block ?=> Unit
    )(
      using Arena,
      Context
    ): Unit =
      val region = branch.operation.getRegion(1)
      region.appendOwnedBlock(summon[BlockApi].blockCreate(Seq.empty, Seq.empty))
      body(
        using region.getFirstBlock
      )

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
  ): Unit =
    val cases    = new GenerateCaseContext
    body(
      using cases
    )
    val generate = summon[GenerateApi].op(valName, locate)
    generate.operation.appendToBlock()
    val op       = summon[GenerateCaseApi].op(
      selector.toAttribute,
      cases.branches.map(_.pattern.fold(summon[SVAttributes].getCaseDefault)(_.toAttribute)).toSeq,
      cases.branches.map(_.name).toSeq,
      locate
    )
    op.operation.appendToBlock()(
      using generate.block
    )
    cases.branches.zipWithIndex.foreach((branch, i) => branch.body(op.caseBlock(i)))

  def is(
    pattern: ConstExpr
  )(body:    Block ?=> Unit
  )(
    using GenerateCaseContext,
    InstanceContext,
    sourcecode.Name.Machine
  ): Unit =
    summon[GenerateCaseContext].branches += GenerateBranch(
      Some(pattern),
      valName,
      block =>
        body(
          using block
        )
    )

  def defaultCase(
    body: Block ?=> Unit
  )(
    using GenerateCaseContext,
    InstanceContext,
    sourcecode.Name.Machine
  ): Unit =
    summon[GenerateCaseContext].branches += GenerateBranch(
      None,
      valName,
      block =>
        body(
          using block
        )
    )

private[sv] def declareMacro(
  macroRef: MacroRef
)(
  using Arena,
  Context,
  SVElaborationContext,
  sourcecode.File,
  sourcecode.Line
): Unit =
  val root = summon[SVElaborationContext]
  if root.modules.add(macroRef.symbolName) then
    summon[MacroDeclApi]
      .op(macroRef.symbolName, args = macroRef.arguments, verilogName = Some(macroRef.name), location = locate)
      .operation
      .appendToBlock()(
        using root.module.getBody
      )
