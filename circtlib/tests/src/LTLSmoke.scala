// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.circtlib.tests

import org.llvm.circt.scalalib.capi.dialect.ltl.{
  AttributeApi as LTLAttributeApi,
  DialectApi as LTLDialect,
  LTLClockEdge,
  TypeApi as LTLTypeApi,
  given
}
import org.llvm.circt.scalalib.dialect.ltl.operation.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Context, ContextApi, LocationApi, OperationApi, TypeApi as MlirTypeApi, given}
import utest.*

import java.lang.foreign.Arena

object LTLSmoke extends TestSuite:
  private var currentArena:   Arena   = null
  private var currentContext: Context = null

  override def utestBeforeEach(path: Seq[String]): Unit =
    currentArena = Arena.ofConfined()
    currentContext = null

  override def utestAfterEach(path: Seq[String]): Unit =
    val c = currentContext
    val a = currentArena
    currentContext = null
    currentArena = null
    try if c != null then c.destroy()
    finally if a != null then a.close()

  val tests: Tests = Tests:
    test("LTL dialect"):
      given Arena         = currentArena
      val context         = summon[ContextApi].contextCreate
      currentContext = context
      context.allowUnregisteredDialects(true)
      given Context       = context
      summon[LTLDialect].loadDialect
      val unknownLocation = summon[LocationApi].locationUnknownGet

      val sequenceType = summon[LTLTypeApi].sequenceTypeGet
      val propertyType = summon[LTLTypeApi].propertyTypeGet
      assert(sequenceType.isSequence)
      assert(propertyType.isProperty)

      val edgeAttr = LTLClockEdge.Pos.toAttribute
      assert(edgeAttr.isClockEdgeAttr)
      assert(edgeAttr.clockEdgeAttrGetValue == LTLClockEdge.Pos)

      val i1                                     = 1.integerTypeGet
      val scope                                  = summon[OperationApi].operationCreate(
        name = "test.scope",
        location = unknownLocation,
        regionBlockTypeLocations = Seq(
          Seq(
            (
              Seq(i1, sequenceType, propertyType),
              Seq(unknownLocation, unknownLocation, unknownLocation)
            )
          )
        )
      )
      given org.llvm.mlir.scalalib.capi.ir.Block = scope.getFirstRegion.getFirstBlock

      val boolValue = summon[org.llvm.mlir.scalalib.capi.ir.Block].getArgument(0)
      val sequence  = summon[org.llvm.mlir.scalalib.capi.ir.Block].getArgument(1)
      val property  = summon[org.llvm.mlir.scalalib.capi.ir.Block].getArgument(2)

      val and                  = summon[AndApi].op(Seq(boolValue, sequence), unknownLocation)
      and.operation.appendToBlock()
      val or                   = summon[OrApi].op(Seq(property, boolValue), unknownLocation)
      or.operation.appendToBlock()
      val intersect            = summon[IntersectApi].op(Seq(and.result, sequence), unknownLocation)
      intersect.operation.appendToBlock()
      val clockedDelay         = summon[ClockedDelayApi].op(sequence, LTLClockEdge.Neg, boolValue, 2, Some(1), unknownLocation)
      clockedDelay.operation.appendToBlock()
      val past                 = summon[PastApi].op(boolValue, 1, boolValue, unknownLocation)
      past.operation.appendToBlock()
      val sampled              = summon[SampledApi].op(boolValue, unknownLocation)
      sampled.operation.appendToBlock()
      val concat               = summon[ConcatApi].op(Seq(sequence, clockedDelay.result), unknownLocation)
      concat.operation.appendToBlock()
      val repeat               = summon[RepeatApi].op(sequence, 1, Some(0), unknownLocation)
      repeat.operation.appendToBlock()
      val gotoRepeat           = summon[GoToRepeatApi].op(sequence, 1, 2, unknownLocation)
      gotoRepeat.operation.appendToBlock()
      val nonConsecutiveRepeat = summon[NonConsecutiveRepeatApi].op(sequence, 1, 2, unknownLocation)
      nonConsecutiveRepeat.operation.appendToBlock()
      val booleanConstant      = summon[BooleanConstantApi].op(true, unknownLocation)
      booleanConstant.operation.appendToBlock()
      val not                  = summon[NotApi].op(property, unknownLocation)
      not.operation.appendToBlock()
      val implication          = summon[ImplicationApi].op(sequence, not.result, unknownLocation)
      implication.operation.appendToBlock()
      val until                = summon[UntilApi].op(not.result, implication.result, unknownLocation)
      until.operation.appendToBlock()
      val eventually           = summon[EventuallyApi].op(until.result, unknownLocation)
      eventually.operation.appendToBlock()
      val clock                = summon[ClockApi].op(eventually.result, LTLClockEdge.Pos, boolValue, unknownLocation)
      clock.operation.appendToBlock()

      val out         = StringBuilder()
      scope.print(out ++= _)
      val scopeString = out.toString()

      // println(scopeString)
      assert(scopeString.contains("ltl.and"))
      assert(scopeString.contains("ltl.or"))
      assert(scopeString.contains("ltl.intersect"))
      assert(!scopeString.contains("ltl.delay"))
      assert(scopeString.contains("ltl.clocked_delay"))
      assert(scopeString.contains("ltl.past"))
      assert(scopeString.contains("ltl.sampled"))
      assert(scopeString.contains("ltl.concat"))
      assert(scopeString.contains("ltl.repeat"))
      assert(scopeString.contains("ltl.goto_repeat"))
      assert(scopeString.contains("ltl.non_consecutive_repeat"))
      assert(scopeString.contains("ltl.boolean_constant"))
      assert(scopeString.contains("ltl.not"))
      assert(scopeString.contains("ltl.implication"))
      assert(scopeString.contains("ltl.until"))
      assert(scopeString.contains("ltl.eventually"))
      assert(scopeString.contains("ltl.clock"))
