// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv

import me.jiuyang.zaozi.InstanceContext
import me.jiuyang.zaozi.sv.valuetpe.*
import me.jiuyang.zaozi.sv.reftpe.*
import org.llvm.mlir.scalalib.capi.ir.*
import java.lang.foreign.Arena
export org.llvm.circt.scalalib.dialect.sv.attr.{CaseStyle, EventControl, ResetStyle, ValidationQualifier}

trait ProceduralApi:
  def initial(
    body: Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ):   Unit
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
  ):   Unit
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
  ):   Unit
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
  ):   ProceduralBranch
  extension (branch: ProceduralBranch)
    def otherwise(
      body: Block ?=> Unit
    )(
      using Arena,
      Context
    ): Unit
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
  ):   Unit
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
  ):   Unit
  def caseOf(
    pattern: CasePattern
  )(body:    Block ?=> Unit
  )(
    using ProceduralCaseContext
  ):   Unit

case class Event(signal: Referable[Bits], edge: EventControl = EventControl.PosEdge)
final class ProceduralBranch private[sv] (private[sv] val operation: Operation)
enum CasePattern:
  case Bits(value: String)
  case Expression(value: Referable[?])
  case EnumMember(member: me.jiuyang.zaozi.sv.reftpe.EnumMember[?])
  case Default
private[sv] case class ProceduralCaseBranch(pattern: CasePattern, body: Block => Unit)
final class ProceduralCaseContext private[sv] ():
  private[sv] val branches = collection.mutable.ArrayBuffer.empty[ProceduralCaseBranch]
