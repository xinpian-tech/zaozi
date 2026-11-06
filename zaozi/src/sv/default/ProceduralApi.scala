// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.default

import me.jiuyang.zaozi.InstanceContext
import me.jiuyang.zaozi.default.{locate, valName}
import me.jiuyang.zaozi.sv.*
import me.jiuyang.zaozi.sv.valuetpe.*
import me.jiuyang.zaozi.sv.reftpe.*
import org.llvm.circt.scalalib.dialect.sv.operation.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import java.lang.foreign.Arena

given ProceduralApi with
  def initial(
    body: Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    val op = summon[InitialApi].op(locate)
    op.operation.appendToBlock()
    body(
      using op.block
    )
  def always(
    events: Event*
  )(body:   Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    val op = summon[AlwaysApi].op(events.map(_.edge), events.map(_.signal.refer), locate)
    op.operation.appendToBlock()
    body(
      using op.block
    )
  def alwaysFF(
    clock:   Event,
    reset:   Event,
    style:   ResetStyle
  )(body:    Block ?=> Unit
  )(onReset: Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    val op = summon[AlwaysFFApi].op(clock.edge, clock.signal.refer, style, reset.edge, reset.signal.refer, locate)
    op.operation.appendToBlock()
    body(
      using op.block
    )
    onReset(
      using op.resetBlock
    )
  def svIf(
    condition: Referable[Bits]
  )(body:      Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): ProceduralBranch =
    val op = summon[IfApi].op(condition.refer, location = locate)
    op.operation.appendToBlock()
    body(
      using op.condBlock
    )
    new ProceduralBranch(op.operation)
  extension (branch: ProceduralBranch)
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
  def forLoop(
    lower: Referable[Bits],
    upper: Referable[Bits],
    step:  Referable[Bits]
  )(body:  Node[Bits] => Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line,
    InstanceContext,
    sourcecode.Name.Machine
  ): Unit =
    val op    = summon[ForApi].op(lower.refer, upper.refer, step.refer, valName, locate)
    op.operation.appendToBlock()
    val index = new Node[Bits]:
      private[zaozi] val _tpe   = lower.getType
      private[zaozi] val _refer = op.inductionVar
    body(index)(
      using op.block
    )
  def svCase[T <: Data](
    selector:  Referable[T],
    style:     CaseStyle = CaseStyle.Case,
    qualifier: ValidationQualifier = ValidationQualifier.Plain
  )(body:      ProceduralCaseContext ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Unit =
    val cases    = new ProceduralCaseContext
    body(
      using cases
    )
    import org.llvm.circt.scalalib.dialect.sv.attr.given
    val attrs    = summon[org.llvm.circt.scalalib.dialect.sv.attr.AttributeApi]
    val patterns = cases.branches.map(_.pattern match
      case CasePattern.Default            => attrs.getCaseDefault
      case CasePattern.Bits(value)        => attrs.getCasePattern(value)
      case CasePattern.EnumMember(member) =>
        import org.llvm.circt.scalalib.dialect.hw.attr.given
        summon[org.llvm.circt.scalalib.dialect.hw.attr.AttributeApi]
          .getEnumField(member.name, member.enumType.toMlirType)
      case CasePattern.Expression(_)      => attrs.getCaseExprPattern)
    val values   = cases.branches.collect { case ProceduralCaseBranch(CasePattern.Expression(value), _) => value.refer }
    val op       = summon[CaseApi].op(style, selector.refer, patterns.toSeq, values.toSeq, qualifier, locate)
    op.operation.appendToBlock()
    cases.branches.zipWithIndex.foreach((branch, i) => branch.body(op.caseBlock(i)))
  def caseOf(
    pattern: CasePattern
  )(body:    Block ?=> Unit
  )(
    using ProceduralCaseContext
  ): Unit =
    summon[ProceduralCaseContext].branches += ProceduralCaseBranch(
      pattern,
      block =>
        body(
          using block
        )
    )
