// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.tpe.*

import me.jiuyang.rvprobe.constraints.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Location, LocationApi, Operation, Type, Value, given}
import org.chipsalliance.rvdecoderdb.Instruction

import java.lang.foreign.Arena

extension (insts: Seq[Index])
  /** Cover bins for a specific field across all instructions */
  def coverBins(
    targets: Index => Referable[SInt],
    bins:    Seq[Const[SInt]]
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    val targetValues = insts.map(targets)

    // For each element in bins, there exists at least one element in targets that is equal to it.
    val constraints = bins.map { binElement =>
      smtOr(targetValues.map(_ === binElement).toSeq*)
    }

    // Combine all constraints with AND and assert
    smtAssert(smtAnd(constraints*))

  /** Cover no hazard - auto-detect instruction args from Index.opcodeId Registered as cross-index constraint, executed
    * in Stage 2
    */
  def coverNoHazard(
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    summon[Recipe].addCrossIndexConstraint { () =>
      val pairs = insts.zip(insts.tail)

      val constraints = pairs.flatMap { case (e, l) =>
        val conditions = scala.collection.mutable.ListBuffer[Ref[Bool]]()

        // No RAW: later's source != earlier's destination
        if (e.hasRd && l.hasRs1) conditions += (l.rs1 =/= e.rd)
        if (e.hasRd && l.hasRs2) conditions += (l.rs2 =/= e.rd)

        // No WAR: earlier's source != later's destination
        if (l.hasRd && e.hasRs1) conditions += (e.rs1 =/= l.rd)
        if (l.hasRd && e.hasRs2) conditions += (e.rs2 =/= l.rd)

        // No WAW: earlier's destination != later's destination
        if (e.hasRd && l.hasRd) conditions += (e.rd =/= l.rd)

        if (conditions.nonEmpty) Some(smtAnd(conditions.toSeq*)) else None
      }

      if (constraints.nonEmpty) {
        smtAssert(smtOr(constraints.toSeq*))
      }
    }

  /** Cover RAW (Read After Write) hazard - auto-detect instruction args from Index.opcodeId Registered as cross-index
    * constraint, executed in Stage 2
    */
  def coverRAW(
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    summon[Recipe].addCrossIndexConstraint { () =>
      val pairs = insts.zip(insts.tail)

      val constraints = pairs.flatMap { case (e, l) =>
        val conditions = scala.collection.mutable.ListBuffer[Ref[Bool]]()

        // RAW: later's source == earlier's destination
        if (e.hasRd && l.hasRs1) conditions += (l.rs1 === e.rd)
        if (e.hasRd && l.hasRs2) conditions += (l.rs2 === e.rd)

        if (conditions.nonEmpty) Some(smtOr(conditions.toSeq*)) else None
      }

      if (constraints.nonEmpty) {
        smtAssert(smtOr(constraints.toSeq*))
      }
    }

  /** Cover WAR (Write After Read) hazard - auto-detect instruction args from Index.opcodeId Registered as cross-index
    * constraint, executed in Stage 2
    */
  def coverWAR(
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    summon[Recipe].addCrossIndexConstraint { () =>
      val pairs = insts.zip(insts.tail)

      val constraints = pairs.flatMap { case (e, l) =>
        val warConditions = scala.collection.mutable.ListBuffer[Ref[Bool]]()
        val rawConditions = scala.collection.mutable.ListBuffer[Ref[Bool]]()

        // WAR: earlier's source == later's destination
        if (l.hasRd && e.hasRs1) warConditions += (e.rs1 === l.rd)
        if (l.hasRd && e.hasRs2) warConditions += (e.rs2 === l.rd)

        // Exclude RAW (to get pure WAR)
        if (e.hasRd && l.hasRs1) rawConditions += (l.rs1 === e.rd)
        if (e.hasRd && l.hasRs2) rawConditions += (l.rs2 === e.rd)

        if (warConditions.nonEmpty) {
          val warPart = smtOr(warConditions.toSeq*)
          val notRaw  = if (rawConditions.nonEmpty) !smtOr(rawConditions.toSeq*) else true.B
          Some(warPart & notRaw)
        } else None
      }

      if (constraints.nonEmpty) {
        smtAssert(smtOr(constraints.toSeq*))
      }
    }

  /** Cover WAW (Write After Write) hazard - auto-detect instruction args from Index.opcodeId Registered as cross-index
    * constraint, executed in Stage 2
    */
  def coverWAW(
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    summon[Recipe].addCrossIndexConstraint { () =>
      val pairs = insts.zip(insts.tail)

      val constraints = pairs.flatMap { case (e, l) =>
        // WAW: earlier's destination == later's destination
        if (e.hasRd && l.hasRd) Some(e.rd === l.rd) else None
      }

      if (constraints.nonEmpty) {
        smtAssert(smtOr(constraints.toSeq*))
      }
    }
