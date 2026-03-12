// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.constraints

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.tpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Location, LocationApi, Operation, Type, Value, given}
import org.llvm.mlir.scalalib.dialect.smt.operation.{given_NotApi, NotApi}

import java.lang.foreign.Arena
import scala.annotation.targetName

// Base type for all constraints
opaque type Constraint <: Referable[Bool] = Ref[Bool]

object Constraint:
  def apply(ref: Ref[Bool]): Constraint = ref

// Intermediate type for Instruction Selection (Opcode + Sets)
opaque type InstConstraint <: Constraint = Ref[Bool]

object InstConstraint:
  def apply(ref: Ref[Bool]): InstConstraint = ref

// Specific Instruction Constraint (e.g., nameId === 5)
opaque type OpcodeConstraint <: InstConstraint = Ref[Bool]

object OpcodeConstraint:
  def apply(ref: Ref[Bool]): OpcodeConstraint = ref

opaque type SetConstraint <: InstConstraint = Ref[Bool]

object SetConstraint:
  def apply(ref: Ref[Bool]): SetConstraint = ref

// Argument/Value Constraints (e.g., hasImm12, rd != 0)
opaque type ArgConstraint <: Constraint = Ref[Bool]

object ArgConstraint:
  def apply(ref: Ref[Bool]): ArgConstraint = ref

// Extension methods at package level to ensure they are picked up
extension (self: Constraint)
  def toRef: Ref[Bool] = self

  @targetName("andConstraint")
  def &(
    other: Constraint
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Constraint =
    Constraint(smtAnd(self.toRef, other.toRef))
  @targetName("orConstraint")
  def |(
    other: Constraint
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Constraint =
    Constraint(smtOr(self.toRef, other.toRef))

extension (self: InstConstraint)
  @targetName("andInstType")
  def &(
    other: InstConstraint
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): InstConstraint =
    InstConstraint(smtAnd(self, other))
  @targetName("orInstType")
  def |(
    other: InstConstraint
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): InstConstraint =
    InstConstraint(smtOr(self, other))

extension (self: OpcodeConstraint)
  @targetName("andInst")
  def &(
    other: OpcodeConstraint | SetConstraint
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): InstConstraint =
    InstConstraint(smtAnd(self, other))
  @targetName("orInst")
  def |(
    other: OpcodeConstraint | SetConstraint
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): InstConstraint =
    InstConstraint(smtOr(self, other))

extension (self: SetConstraint)
  @targetName("andSet")
  def &(
    other: SetConstraint
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): SetConstraint =
    SetConstraint(smtAnd(self, other))
  @targetName("orSet")
  def |(
    other: SetConstraint
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): SetConstraint =
    SetConstraint(smtOr(self, other))

  @targetName("andSetInst")
  def &(
    other: OpcodeConstraint
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): InstConstraint =
    InstConstraint(smtAnd(self, other))
  @targetName("orSetInst")
  def |(
    other: OpcodeConstraint
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): InstConstraint =
    InstConstraint(smtOr(self, other))

extension (self: ArgConstraint)
  @targetName("notArg")
  def unary_!(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): ArgConstraint =
    val op = summon[NotApi].op(
      self.refer,
      summon[LocationApi].locationFileLineColGet(summon[sourcecode.File].value, summon[sourcecode.Line].value, 0)
    )
    op.operation.appendToBlock()
    ArgConstraint(new Ref[Bool]:
      val _tpe: Bool = new Object with Bool
      val _operation = op.operation)
  @targetName("andArg")
  def &(
    other: ArgConstraint
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): ArgConstraint =
    ArgConstraint(smtAnd(self, other))
  @targetName("orArg")
  def |(
    other: ArgConstraint
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): ArgConstraint =
    ArgConstraint(smtOr(self, other))

// SpecFor[T] — typeclass that associates spec-mandated arg constraints with a
// specific instruction opaque type T (e.g. IsFence, IsAddi, …).
trait SpecFor[T <: InstConstraint]:
  def spec(using Arena, Context, Block, Index): Option[ArgConstraint]

// When no explicit given is provided for a type T, the low-priority
// `noSpec` instance is used and no additional constraint is injected.
object SpecFor:
  given noSpec[T <: InstConstraint]: SpecFor[T] with
    def spec(using Arena, Context, Block, Index): Option[ArgConstraint] = None
