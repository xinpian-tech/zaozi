// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.{InstanceContext, SVAApi, TypeImpl}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.capi.dialect.ltl.{given_AttributeApi, given_TypeApi, LTLClockEdge, TypeApi as LTLTypeApi}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{given_AsUIntPrimApi, AsUIntPrimApi}
import org.llvm.circt.scalalib.dialect.ltl.operation.{
  given_AndApi,
  given_ClockApi,
  given_ClockedDelayApi,
  given_ConcatApi,
  given_EventuallyApi,
  given_GoToRepeatApi,
  given_ImplicationApi,
  given_IntersectApi,
  given_NonConsecutiveRepeatApi,
  given_NotApi,
  given_OrApi,
  given_RepeatApi,
  given_UntilApi,
  AndApi,
  ClockApi,
  ClockedDelayApi,
  ConcatApi,
  EventuallyApi,
  GoToRepeatApi,
  ImplicationApi,
  IntersectApi,
  NonConsecutiveRepeatApi,
  NotApi,
  OrApi,
  RepeatApi,
  UntilApi
}
import org.llvm.circt.scalalib.dialect.verif.operation.{
  given_AssertApi,
  given_AssumeApi,
  given_CoverApi,
  AssertApi,
  AssumeApi,
  CoverApi
}
import org.llvm.mlir.scalalib.capi.ir.{
  given_AttributeApi,
  given_BlockApi,
  given_IdentifierApi,
  given_LocationApi,
  given_NamedAttributeApi,
  given_OperationApi,
  given_RegionApi,
  given_TypeApi,
  given_ValueApi,
  Block,
  Context,
  Operation,
  OperationApi
}

import java.lang.foreign.Arena

export given_SVAApi.{anyedge, negedge, posedge, Assert, Assume, Cover}

given SVAApi with
  def posedge(clock: Referable[Clock] & HasOperation): ClockEvent =
    ClockEvent(LTLClockEdge.Pos, clock)
  def negedge(clock: Referable[Clock] & HasOperation): ClockEvent =
    ClockEvent(LTLClockEdge.Neg, clock)
  def anyedge(clock: Referable[Clock] & HasOperation): ClockEvent =
    ClockEvent(LTLClockEdge.Both, clock)

  extension [T <: Referable[Bool] & HasOperation](ref: T)
    def S(
      using clock: ClockEvent
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence =
      val boolAsI1    = summon[OperationApi].operationCreate(
        name = "builtin.unrealized_conversion_cast",
        location = locate,
        operands = Seq(ref.refer),
        resultsTypes = Some(Seq(1.integerTypeGet))
      )
      boolAsI1.appendToBlock()
      val clockAsUInt = summon[AsUIntPrimApi].op(clock.clock.refer, locate)
      clockAsUInt.operation.appendToBlock()
      val clockAsI1   = summon[OperationApi].operationCreate(
        name = "builtin.unrealized_conversion_cast",
        location = locate,
        operands = Seq(clockAsUInt.result),
        resultsTypes = Some(Seq(1.integerTypeGet))
      )
      clockAsI1.appendToBlock()
      val seq         = summon[ClockApi].op(boolAsI1.getResult(0), clock.edge, clockAsI1.getResult(0), locate)
      seq.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = seq.operation
        private[zaozi] val _clockevent: ClockEvent = clock

    def I(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Immediate =
      val cast = summon[OperationApi].operationCreate(
        name = "builtin.unrealized_conversion_cast",
        location = locate,
        operands = Seq(ref.refer),
        resultsTypes = Some(Seq(1.integerTypeGet))
      )
      cast.appendToBlock()
      new Immediate:
        private[zaozi] val _operation: Operation = cast

    def throughout(
      that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence =
      // %repexpr = ltl.repeat %expr, 0 : !ltl.sequence
      // %res = ltl.intersect %repexpr, %s : !ltl.sequence
      val cast    = summon[OperationApi].operationCreate(
        name = "builtin.unrealized_conversion_cast",
        location = locate,
        operands = Seq(ref.refer),
        resultsTypes = Some(Seq(1.integerTypeGet))
      )
      cast.appendToBlock()
      val repexpr = summon[RepeatApi].op(cast.getResult(0), 0L, None, locate)
      repexpr.operation.appendToBlock()
      val res     = summon[IntersectApi].op(Seq(repexpr.result, that.refer), locate)
      res.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = res.operation
        private[zaozi] val _clockevent: ClockEvent = that._clockevent

  extension (ref: Immediate)
    // If any of the $inputs is of type !ltl.property, the result of the op is an !ltl.property. Otherwise it is an !ltl.sequence.
    // Immediate and Immediate => Immediate
    // LTLSequenceLike and LTLSequenceLike => Sequence
    // LTLPropertyLike and LTLPropertyLike => Property
    def &(
      that: Immediate
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Immediate =
      val op = summon[AndApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Immediate:
        private[zaozi] val _operation: Operation = op.operation

    def |(
      that: Immediate
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Immediate =
      val op = summon[OrApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Immediate:
        private[zaozi] val _operation: Operation = op.operation

    def intersect(
      that: Immediate
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Immediate =
      val op = summon[IntersectApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Immediate:
        private[zaozi] val _operation: Operation = op.operation

  extension (ref: Sequence)
    def ##(
      that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence =
      val op = summon[ConcatApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = ref._clockevent

    def ###(
      that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence = ref.##(1)(that)

    def ##(
      n:    Int
    )(that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence =
      require(n >= 0, s"delay ($n) must be greater than or equal to 0 in sequence delay")
      // ##[n] that
      val clockAsUInt = summon[AsUIntPrimApi].op(that._clockevent.clock.refer, locate)
      clockAsUInt.operation.appendToBlock()
      val clockCast   = summon[OperationApi].operationCreate(
        name = "builtin.unrealized_conversion_cast",
        location = locate,
        operands = Seq(clockAsUInt.result),
        resultsTypes = Some(Seq(1.integerTypeGet))
      )
      clockCast.appendToBlock()
      val op0         =
        summon[ClockedDelayApi].op(
          that.refer,
          that._clockevent.edge,
          clockCast.getResult(0),
          n.toLong,
          Some(0L),
          locate
        )
      op0.operation.appendToBlock()
      val delayed     = new Sequence:
        private[zaozi] val _operation:  Operation  = op0.operation
        private[zaozi] val _clockevent: ClockEvent = that._clockevent
      ref.##(delayed)

    def ##(
      min:  Int,
      max:  Option[Int]
    )(that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence =
      require(min >= 0, s"min ($min) must be greater than or equal to 0 in sequence delay")
      max.foreach(value =>
        require(value >= min, s"max ($value) must be greater than or equal to min ($min) in sequence delay")
      )
      // ##[n:m] that
      val clockAsUInt = summon[AsUIntPrimApi].op(that._clockevent.clock.refer, locate)
      clockAsUInt.operation.appendToBlock()
      val clockCast   = summon[OperationApi].operationCreate(
        name = "builtin.unrealized_conversion_cast",
        location = locate,
        operands = Seq(clockAsUInt.result),
        resultsTypes = Some(Seq(1.integerTypeGet))
      )
      clockCast.appendToBlock()
      val op0         = summon[ClockedDelayApi].op(
        that.refer,
        that._clockevent.edge,
        clockCast.getResult(0),
        min.toLong,
        max.map(value => (value - min).toLong),
        locate
      )
      op0.operation.appendToBlock()
      val delayed     = new Sequence:
        private[zaozi] val _operation:  Operation  = op0.operation
        private[zaozi] val _clockevent: ClockEvent = that._clockevent
      ref.##(delayed)

    def *(
      n: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence =
      require(n >= 0, s"repeat count ($n) must be greater than or equal to 0")
      val op = summon[RepeatApi].op(ref.refer, n.toLong, Some(0L), locate)
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = ref._clockevent

    def *(
      min: Int,
      max: Option[Int]
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence =
      require(min >= 0, s"min ($min) must be greater than or equal to 0 in repeat")
      max.foreach(value => require(value >= min, s"max ($value) must be greater than or equal to min ($min) in repeat"))
      val op = summon[RepeatApi].op(ref.refer, min.toLong, max.map(value => (value - min).toLong), locate)
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = ref._clockevent

    def *->(
      min: Int,
      max: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence =
      require(min >= 0, s"min ($min) must be greater than or equal to 0 in goto repeat")
      require(max >= min, s"max ($max) must be greater than or equal to min ($min) in goto repeat")
      val op = summon[GoToRepeatApi].op(
        ref.refer,
        min.toLong,
        (max - min).toLong,
        locate
      )
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = ref._clockevent

    def *=(
      min: Int,
      max: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence =
      require(min >= 0, s"min ($min) must be greater than or equal to 0 in non-consecutive repeat")
      require(max >= min, s"max ($max) must be greater than or equal to min ($min) in non-consecutive repeat")
      val op = summon[NonConsecutiveRepeatApi].op(
        ref.refer,
        min.toLong,
        (max - min).toLong,
        locate
      )
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = ref._clockevent

    def ##+(
      that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence = ref.##(1, None)(that)

    def ##*(
      that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence = ref.##(0, None)(that)

    def within(
      that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence =
      // within: ref occurs within the duration of 'that'
      // true ##[*] s1 ##[*] true intersect s2
      given ClockEvent = ref._clockevent
      true.B.S.##*(ref).##*(true.B.S).intersect(that)

    def |=>(
      that: LTLPropertyLike
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      // ref |=> that: implication property (weak implication)
      // ref ##1 true |-> that
      given ClockEvent = ref._clockevent
      ref.##(1)(true.B.S) |-> that

    def #=#(
      that: LTLPropertyLike
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      // ref #=# that: non-overlapping followed-by property.
      // Equivalent to: !(ref |=> !that)
      // Equivalent to: (ref ### true) #-# that
      !(ref |=> !that)

  extension (ref: LTLSequenceLike)
    def &(
      that: LTLSequenceLike
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence =
      val clock = (ref, that) match
        case (left: Sequence, _)  =>
          left._clockevent
        case (_, right: Sequence) =>
          right._clockevent
        case _                    =>
          throw IllegalArgumentException("Cannot create an SVA sequence from two clockless expressions")
      val op    = summon[AndApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = clock

    def |(
      that: LTLSequenceLike
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence =
      val clock = (ref, that) match
        case (left: Sequence, _)  =>
          left._clockevent
        case (_, right: Sequence) =>
          right._clockevent
        case _                    =>
          throw IllegalArgumentException("Cannot create an SVA sequence from two clockless expressions")
      val op    = summon[OrApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = clock

    def intersect(
      that: LTLSequenceLike
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence =
      val clock = (ref, that) match
        case (left: Sequence, _)  =>
          left._clockevent
        case (_, right: Sequence) =>
          right._clockevent
        case _                    =>
          throw IllegalArgumentException("Cannot create an SVA sequence from two clockless expressions")
      val op    = summon[IntersectApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = clock

    def |->(
      that: LTLPropertyLike
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      // ref |-> that: implication property (strong implication)
      val op = summon[ImplicationApi].op(ref.refer, that.refer, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    def #-#(
      that: LTLPropertyLike
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      // ref #-# that: strong implication property (sequence implication)
      // Equivalent to: !(ref |-> !that)
      !(ref |-> !that)

  extension (ref: LTLPropertyLike)
    def &(
      that: LTLPropertyLike
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val op = summon[AndApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    def |(
      that: LTLPropertyLike
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val op = summon[OrApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    def intersect(
      that: LTLPropertyLike
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val op = summon[IntersectApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    def unary_!(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val op = summon[NotApi].op(ref.refer, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    def implies(
      that: LTLPropertyLike
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      // Equivalent to: !ref | that
      (!ref) | that

    def iff(
      that: LTLPropertyLike
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      // Equivalent to: !(ref | that) | (ref & that)
      !(ref | that) | (ref & that)

    def eventually(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val op = summon[EventuallyApi].op(ref.refer, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    def until(
      that: LTLPropertyLike
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      // until: equivalent to ltl.until
      val op = summon[UntilApi].op(ref.refer, that.refer, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    def untilWith(
      that: LTLPropertyLike
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      // untilWith: equivalent to ltl.until with inclusive semantics
      // Equivalent to: ref until (ref & that)
      // Equivalent to: !(ref until that) or (ref & that)
      // Equivalent to: (ref until that) |-> (ref & that)
      ref.until(ref & that)

    def always(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      ref.until(false.B.I)

  def Assert[T <: LTLPropertyLike](
    expression: T,
    label:      Option[String] = None
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit =
    summon[AssertApi]
      .op(expression.refer, Some(label.getOrElse(valName)), locate)
      .operation
      .appendToBlock()

  def Assume[T <: LTLPropertyLike](
    expression: T,
    label:      Option[String] = None
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit =
    summon[AssumeApi]
      .op(expression.refer, Some(label.getOrElse(valName)), locate)
      .operation
      .appendToBlock()

  def Cover[T <: LTLPropertyLike](
    expression: T,
    label:      Option[String] = None
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit =
    summon[CoverApi]
      .op(expression.refer, Some(label.getOrElse(valName)), locate)
      .operation
      .appendToBlock()

end given
