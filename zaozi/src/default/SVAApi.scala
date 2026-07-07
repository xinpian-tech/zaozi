// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.{InstanceContext, SVAApi, TypeImpl}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.FirrtlEventControl
import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  given_LTLAndIntrinsicApi,
  given_LTLClockIntrinsicApi,
  given_LTLClockedDelayIntrinsicApi,
  given_LTLConcatIntrinsicApi,
  given_LTLEventuallyIntrinsicApi,
  given_LTLGoToRepeatIntrinsicApi,
  given_LTLImplicationIntrinsicApi,
  given_LTLIntersectIntrinsicApi,
  given_LTLNonConsecutiveRepeatIntrinsicApi,
  given_LTLNotIntrinsicApi,
  given_LTLOrIntrinsicApi,
  given_LTLRepeatIntrinsicApi,
  given_LTLUntilIntrinsicApi,
  given_VerifAssertApi,
  given_VerifAssumeApi,
  given_VerifCoverApi,
  LTLAndIntrinsicApi as AndApi,
  LTLClockIntrinsicApi as ClockApi,
  LTLClockedDelayIntrinsicApi as ClockedDelayApi,
  LTLConcatIntrinsicApi as ConcatApi,
  LTLEventuallyIntrinsicApi as EventuallyApi,
  LTLGoToRepeatIntrinsicApi as GoToRepeatApi,
  LTLImplicationIntrinsicApi as ImplicationApi,
  LTLIntersectIntrinsicApi as IntersectApi,
  LTLNonConsecutiveRepeatIntrinsicApi as NonConsecutiveRepeatApi,
  LTLNotIntrinsicApi as NotApi,
  LTLOrIntrinsicApi as OrApi,
  LTLRepeatIntrinsicApi as RepeatApi,
  LTLUntilIntrinsicApi as UntilApi,
  VerifAssertApi as AssertApi,
  VerifAssumeApi as AssumeApi,
  VerifCoverApi as CoverApi
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
  Operation
}

import java.lang.foreign.Arena

export given_SVAApi.{always, eventually, negedge, posedge, Assert, Assume, Cover}

given SVAApi with
  def posedge(clock: Referable[Clock] & HasOperation): ClockEvent =
    ClockEvent(FirrtlEventControl.AtPosEdge, clock)
  def negedge(clock: Referable[Clock] & HasOperation): ClockEvent =
    ClockEvent(FirrtlEventControl.AtNegEdge, clock)

  def always(
    property: Immediate | Sequence | Property
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Property =
    val value = property match
      case value: Immediate => value.refer
      case value: Sequence  => value.refer
      case value: Property  => value.refer
    val rhs   = false.B.I
    val op    = summon[UntilApi].op(value, rhs.refer, locate)
    op.operation.appendToBlock()
    new Property:
      private[zaozi] val _operation: Operation = op.operation

  def eventually(
    property: Immediate | Sequence | Property
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Property =
    val value = property match
      case value: Immediate => value.refer
      case value: Sequence  => value.refer
      case value: Property  => value.refer
    val op    = summon[EventuallyApi].op(value, locate)
    op.operation.appendToBlock()
    new Property:
      private[zaozi] val _operation: Operation = op.operation

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
      val seq = summon[ClockApi].op(ref.refer, clock.edge, clock.clock.refer, locate)
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
      new Immediate:
        private[zaozi] val _operation: Operation = ref.operation

    infix def throughout(
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
      val repexpr = summon[RepeatApi].op(ref.refer, 0L, None, locate)
      repexpr.operation.appendToBlock()
      val res     = summon[IntersectApi].op(Seq(repexpr.result, that.refer), locate)
      res.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = res.operation
        private[zaozi] val _clockevent: ClockEvent = that._clockevent

  extension (ref: Immediate)
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

    def &(
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
      val op = summon[AndApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = that._clockevent

    def &(
      that: Property
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

    def |(
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
      val op = summon[OrApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = that._clockevent

    def |(
      that: Property
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

    infix def intersect(
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

    infix def intersect(
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
      val op = summon[IntersectApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = that._clockevent

    infix def intersect(
      that: Property
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

    def |->(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val op        = summon[ImplicationApi].op(ref.refer, thatValue, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    def #-#(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val notThat   = summon[NotApi].op(thatValue, locate)
      notThat.operation.appendToBlock()
      val impl      = summon[ImplicationApi].op(ref.refer, notThat.result, locate)
      impl.operation.appendToBlock()
      val op        = summon[NotApi].op(impl.result, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    infix def implies(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val notRef    = summon[NotApi].op(ref.refer, locate)
      notRef.operation.appendToBlock()
      val op        = summon[OrApi].op(Seq(notRef.result, thatValue), locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    infix def iff(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val or        = summon[OrApi].op(Seq(ref.refer, thatValue), locate)
      or.operation.appendToBlock()
      val notOr     = summon[NotApi].op(or.result, locate)
      notOr.operation.appendToBlock()
      val and       = summon[AndApi].op(Seq(ref.refer, thatValue), locate)
      and.operation.appendToBlock()
      val op        = summon[OrApi].op(Seq(notOr.result, and.result), locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    infix def until(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val op        = summon[UntilApi].op(ref.refer, thatValue, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    infix def untilWith(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val and       = summon[AndApi].op(Seq(ref.refer, thatValue), locate)
      and.operation.appendToBlock()
      val op        = summon[UntilApi].op(ref.refer, and.result, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

  extension (ref: Sequence)
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
      val op         =
        summon[ClockedDelayApi].op(
          that.refer,
          that._clockevent.edge,
          that._clockevent.clock.refer,
          n.toLong,
          Some(0L),
          locate
        )
      op.operation.appendToBlock()
      val clockevent = that._clockevent
      val _that      = new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = clockevent
      ref.##(_that)

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
      val op         = summon[ClockedDelayApi].op(
        that.refer,
        that._clockevent.edge,
        that._clockevent.clock.refer,
        min.toLong,
        max.map(value => (value - min).toLong),
        locate
      )
      op.operation.appendToBlock()
      val clockevent = that._clockevent
      val _that      = new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = clockevent
      ref.##(_that)

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

    infix def within(
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
      that: Immediate | Sequence | Property
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
      that: Immediate | Sequence | Property
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
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val notThat   = summon[NotApi].op(thatValue, locate)
      notThat.operation.appendToBlock()
      val followed  = ref |=> new Property:
        private[zaozi] val _operation: Operation = notThat.operation
      val op        = summon[NotApi].op(followed.refer, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    def &(
      that: Immediate | Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
      val op        = summon[AndApi].op(Seq(ref.refer, thatValue), locate)
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = ref._clockevent

    def &(
      that: Property
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
      that: Immediate | Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
      val op        = summon[OrApi].op(Seq(ref.refer, thatValue), locate)
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = ref._clockevent

    def |(
      that: Property
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

    infix def intersect(
      that: Immediate | Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
      val op        = summon[IntersectApi].op(Seq(ref.refer, thatValue), locate)
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = ref._clockevent

    infix def intersect(
      that: Property
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

    def |->(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val op        = summon[ImplicationApi].op(ref.refer, thatValue, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    def #-#(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val notThat   = summon[NotApi].op(thatValue, locate)
      notThat.operation.appendToBlock()
      val impl      = summon[ImplicationApi].op(ref.refer, notThat.result, locate)
      impl.operation.appendToBlock()
      val op        = summon[NotApi].op(impl.result, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    infix def implies(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val notRef    = summon[NotApi].op(ref.refer, locate)
      notRef.operation.appendToBlock()
      val op        = summon[OrApi].op(Seq(notRef.result, thatValue), locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    infix def iff(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val or        = summon[OrApi].op(Seq(ref.refer, thatValue), locate)
      or.operation.appendToBlock()
      val notOr     = summon[NotApi].op(or.result, locate)
      notOr.operation.appendToBlock()
      val and       = summon[AndApi].op(Seq(ref.refer, thatValue), locate)
      and.operation.appendToBlock()
      val op        = summon[OrApi].op(Seq(notOr.result, and.result), locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    infix def until(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val op        = summon[UntilApi].op(ref.refer, thatValue, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    infix def untilWith(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val and       = summon[AndApi].op(Seq(ref.refer, thatValue), locate)
      and.operation.appendToBlock()
      val op        = summon[UntilApi].op(ref.refer, and.result, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

  extension (ref: Property)
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

    def &(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val op        = summon[AndApi].op(Seq(ref.refer, thatValue), locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    def |(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val op        = summon[OrApi].op(Seq(ref.refer, thatValue), locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    infix def intersect(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val op        = summon[IntersectApi].op(Seq(ref.refer, thatValue), locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    infix def implies(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val notRef    = summon[NotApi].op(ref.refer, locate)
      notRef.operation.appendToBlock()
      val op        = summon[OrApi].op(Seq(notRef.result, thatValue), locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    infix def iff(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val or        = summon[OrApi].op(Seq(ref.refer, thatValue), locate)
      or.operation.appendToBlock()
      val notOr     = summon[NotApi].op(or.result, locate)
      notOr.operation.appendToBlock()
      val and       = summon[AndApi].op(Seq(ref.refer, thatValue), locate)
      and.operation.appendToBlock()
      val op        = summon[OrApi].op(Seq(notOr.result, and.result), locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    infix def until(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val op        = summon[UntilApi].op(ref.refer, thatValue, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    infix def untilWith(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val thatValue = that match
        case value: Immediate => value.refer
        case value: Sequence  => value.refer
        case value: Property  => value.refer
      val and       = summon[AndApi].op(Seq(ref.refer, thatValue), locate)
      and.operation.appendToBlock()
      val op        = summon[UntilApi].op(ref.refer, and.result, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

  def Assert(
    property: Immediate | Sequence | Property
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit =
    val value = property match
      case value: Immediate => value.refer
      case value: Sequence  => value.refer
      case value: Property  => value.refer
    summon[AssertApi]
      .op(value, Some(valName), locate)
      .operation
      .appendToBlock()

  def Assume(
    property: Immediate | Sequence | Property
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit =
    val value = property match
      case value: Immediate => value.refer
      case value: Sequence  => value.refer
      case value: Property  => value.refer
    summon[AssumeApi]
      .op(value, Some(valName), locate)
      .operation
      .appendToBlock()

  def Cover(
    property: Immediate | Sequence | Property
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit =
    val value = property match
      case value: Immediate => value.refer
      case value: Sequence  => value.refer
      case value: Property  => value.refer
    summon[CoverApi]
      .op(value, Some(valName), locate)
      .operation
      .appendToBlock()

  def Assert(
    property: Immediate | Sequence | Property,
    label:    String
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit =
    val value = property match
      case value: Immediate => value.refer
      case value: Sequence  => value.refer
      case value: Property  => value.refer
    summon[AssertApi]
      .op(value, Some(label), locate)
      .operation
      .appendToBlock()

  def Assume(
    property: Immediate | Sequence | Property,
    label:    String
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit =
    val value = property match
      case value: Immediate => value.refer
      case value: Sequence  => value.refer
      case value: Property  => value.refer
    summon[AssumeApi]
      .op(value, Some(label), locate)
      .operation
      .appendToBlock()

  def Cover(
    property: Immediate | Sequence | Property,
    label:    String
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit =
    val value = property match
      case value: Immediate => value.refer
      case value: Sequence  => value.refer
      case value: Property  => value.refer
    summon[CoverApi]
      .op(value, Some(label), locate)
      .operation
      .appendToBlock()

end given
