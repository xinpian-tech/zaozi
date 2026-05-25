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
  AndApi as LTLAndApi,
  ClockApi as LTLClockApi,
  ClockedDelayApi as LTLClockedDelayApi,
  ConcatApi as LTLConcatApi,
  EventuallyApi as LTLEventuallyApi,
  GoToRepeatApi as LTLGoToRepeatApi,
  ImplicationApi as LTLImplicationApi,
  IntersectApi as LTLIntersectApi,
  NonConsecutiveRepeatApi as LTLNonConsecutiveRepeatApi,
  NotApi as LTLNotApi,
  OrApi as LTLOrApi,
  RepeatApi as LTLRepeatApi,
  UntilApi as LTLUntilApi
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
  NamedAttributeApi,
  Operation,
  OperationApi
}

import java.lang.foreign.Arena

export given_SVAApi.{anyedge, negedge, posedge, Assert, Assume, Cover}

given SVAApi with
  private def requireSameClock(ref: Sequence, that: Sequence): Unit =
    require(
      ref._clockevent == that._clockevent,
      s"Cannot combine SVA expressions from different clocking events: ${ref._clockevent} and ${that._clockevent}"
    )

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
      val seq         = summon[LTLClockApi].op(boolAsI1.getResult(0), clock.edge, clockAsI1.getResult(0), locate)
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
      val repexpr = summon[LTLRepeatApi].op(cast.getResult(0), 0L, None, locate)
      repexpr.operation.appendToBlock()
      val res     = summon[LTLIntersectApi].op(Seq(repexpr.result, that.refer), locate)
      res.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = res.operation
        private[zaozi] val _clockevent: ClockEvent = that._clockevent

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
        summon[LTLClockedDelayApi].op(
          that.refer,
          that._clockevent.edge,
          clockCast.getResult(0),
          n.toLong,
          Some(0L),
          locate
        )
      op0.operation.appendToBlock()
      // ref ## ##[n] that -> ref ##[n] that
      val op1         = summon[LTLConcatApi].op(Seq(ref.refer, op0.result), locate)
      op1.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op1.operation
        private[zaozi] val _clockevent: ClockEvent = ref._clockevent

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
      val op0         = summon[LTLClockedDelayApi].op(
        that.refer,
        that._clockevent.edge,
        clockCast.getResult(0),
        min.toLong,
        max.map(value => (value - min).toLong),
        locate
      )
      op0.operation.appendToBlock()
      // ref ## ##[n:m] that -> ref ##[n:m] that
      val op1         = summon[LTLConcatApi].op(Seq(ref.refer, op0.result), locate)
      op1.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op1.operation
        private[zaozi] val _clockevent: ClockEvent = ref._clockevent

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
      val op = summon[LTLRepeatApi].op(ref.refer, n.toLong, Some(0L), locate)
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
      val op = summon[LTLRepeatApi].op(ref.refer, min.toLong, max.map(value => (value - min).toLong), locate)
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
      val op = summon[LTLGoToRepeatApi].op(
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
      val op = summon[LTLNonConsecutiveRepeatApi].op(
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

    def and(
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
      requireSameClock(ref, that)
      val op = summon[LTLAndApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = ref._clockevent

    def intersect(
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
      requireSameClock(ref, that)
      val op = summon[LTLIntersectApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = ref._clockevent

    def or(
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
      requireSameClock(ref, that)
      val op = summon[LTLOrApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = ref._clockevent

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

    def |->(
      that: LTLExpr
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
      val op = summon[LTLImplicationApi].op(ref.refer, that.refer, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    def |=>(
      that: LTLExpr
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

    def #-#(
      that: LTLExpr
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
      // Equivalent to: !(ref -> !that)

      // %np = ltl.not %p : !ltl.property
      // %impl = ltl.implication %s, %np : !ltl.property
      // %res = ltl.not %impl : !ltl.property
      val np   = summon[LTLNotApi].op(that.refer, locate)
      np.operation.appendToBlock()
      val impl = summon[LTLImplicationApi].op(ref.refer, np.result, locate)
      impl.operation.appendToBlock()
      val res  = summon[LTLNotApi].op(impl.result, locate)
      res.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = res.operation

    def #=#(
      that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      // ref #=# that: sequence equivalence property
      // Equivalent to: (ref #-# that) & (that #-# ref)
      // Equivalent to: (ref #-# that) & !(that -> !ref)

      val np    = summon[LTLNotApi].op(that.refer, locate)
      np.operation.appendToBlock()
      val impl0 = summon[LTLImplicationApi].op(ref.refer, np.result, locate)
      impl0.operation.appendToBlock()
      val left  = summon[LTLNotApi].op(impl0.result, locate)
      left.operation.appendToBlock()
      val nref  = summon[LTLNotApi].op(ref.refer, locate)
      nref.operation.appendToBlock()
      val impl  = summon[LTLImplicationApi].op(that.refer, nref.result, locate)
      impl.operation.appendToBlock()
      val right = summon[LTLNotApi].op(impl.result, locate)
      right.operation.appendToBlock()

      val op = summon[LTLAndApi].op(Seq(left.result, right.result), locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    def always(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence =
      // always: equivalent to ltl.repeat with 0
      val op = summon[LTLRepeatApi].op(ref.refer, 0L, None, locate)
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = ref._clockevent

    def always(
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
      // always: equivalent to ltl.repeat with min and max
      require(min >= 0, s"min ($min) must be greater than or equal to 0 in always")
      require(max >= min, s"max ($max) must be greater than or equal to min ($min) in always")
      val op = summon[LTLRepeatApi].op(ref.refer, min.toLong, Some((max - min).toLong), locate)
      op.operation.appendToBlock()
      new Sequence:
        private[zaozi] val _operation:  Operation  = op.operation
        private[zaozi] val _clockevent: ClockEvent = ref._clockevent

  extension (ref: LTLExpr)
    def not(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val op = summon[LTLNotApi].op(ref.refer, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    def implies(
      that: LTLExpr
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      // Logical implication: ref => that is equivalent to !ref | that
      val op0 = summon[LTLNotApi].op(ref.refer, locate)
      op0.operation.appendToBlock()
      val op1 = summon[LTLOrApi].op(Seq(op0.result, that.refer), locate)
      op1.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op1.operation

    def iff(
      that: LTLExpr
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      // Logical equivalence: ref <=> that is !(ref | that) | (ref & that)
      // !(ref | that)
      val or    = summon[LTLOrApi].op(Seq(ref.refer, that.refer), locate)
      or.operation.appendToBlock()
      val left  = summon[LTLNotApi].op(or.result, locate)
      left.operation.appendToBlock()
      // ref & that
      val right = summon[LTLAndApi].op(Seq(ref.refer, that.refer), locate)
      right.operation.appendToBlock()
      val op    = summon[LTLOrApi].op(Seq(left.result, right.result), locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    def eventually(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property =
      val op = summon[LTLEventuallyApi].op(ref.refer, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    def until(
      that: LTLExpr
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
      val op = summon[LTLUntilApi].op(ref.refer, that.refer, locate)
      op.operation.appendToBlock()
      new Property:
        private[zaozi] val _operation: Operation = op.operation

    def untilWith(
      that: LTLExpr
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
      // This is typically modeled as: (ref until that) implies (ref and that)
      val left  = ref.until(that)
      val op    = summon[LTLAndApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      val right = new Property:
        private[zaozi] val _operation: Operation = op.operation

      left.implies(right)

  def Assert[T <: LTLExpr](
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
    summon[OperationApi]
      .operationCreate(
        name = "verif.assert",
        location = locate,
        namedAttributes = Seq(
          summon[NamedAttributeApi].namedAttributeGet(
            "label".identifierGet,
            label.getOrElse(summon[sourcecode.Name].value).stringAttrGet
          )
        ),
        operands = Seq(expression.refer)
      )
      .appendToBlock()

  def Assume[T <: LTLExpr](
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
    summon[OperationApi]
      .operationCreate(
        name = "verif.assume",
        location = locate,
        namedAttributes = Seq(
          summon[NamedAttributeApi].namedAttributeGet(
            "label".identifierGet,
            label.getOrElse(summon[sourcecode.Name].value).stringAttrGet
          )
        ),
        operands = Seq(expression.refer)
      )
      .appendToBlock()

  def Cover[T <: LTLExpr](
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
    summon[OperationApi]
      .operationCreate(
        name = "verif.cover",
        location = locate,
        namedAttributes = Seq(
          summon[NamedAttributeApi].namedAttributeGet(
            "label".identifierGet,
            label.getOrElse(summon[sourcecode.Name].value).stringAttrGet
          )
        ),
        operands = Seq(expression.refer)
      )
      .appendToBlock()

end given
