// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.tpe.*

import me.jiuyang.rvprobe.constraints.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Location, LocationApi, Operation, Type, Value, given}

import java.lang.foreign.Arena

def coverSigns(
  instructionCount: Int,
  inst:             Index ?=> Constraint,
  hasRd:            Boolean = false,
  hasRs1:           Boolean = false,
  hasRs2:           Boolean = false
)(
  using Arena,
  Context,
  Block,
  Recipe
): Unit =
  instruction(instructionCount) { isAddi() } // addi x1, 0, -1
  smtAssert(instruction(instructionCount).rd === 1.S)
  smtAssert(instruction(instructionCount).rs1 === 31.S)
  smtAssert(instruction(instructionCount).imm12 === (-2048).S)

  instruction(instructionCount + 1) { isAddi() }
  smtAssert(instruction(instructionCount + 1).rd === 2.S)
  smtAssert(instruction(instructionCount + 1).rs1 === 31.S)
  smtAssert(instruction(instructionCount + 1).imm12 === 2047.S)

  instruction(instructionCount + 2) { inst }
  instruction(instructionCount + 3) { inst }
  if (hasRd) {
    smtAssert(instruction(instructionCount + 2).rd === 3.S)
    smtAssert(instruction(instructionCount + 3).rd === 3.S)
  }
  if (hasRs1) {
    smtAssert(instruction(instructionCount + 2).rs1 === 1.S)
    smtAssert(instruction(instructionCount + 3).rs1 === 2.S)
  }
  if (hasRs2) {
    smtAssert(instruction(instructionCount + 2).rs2 === 2.S)
    smtAssert(instruction(instructionCount + 3).rs2 === 1.S)
  }

extension (insts: Seq[Index])
  def coverBins(
    targets: Index => Referable[SInt],
    bins:    Seq[Const[SInt]]
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    val recipe       = summon[Recipe]
    val targetValues = insts.map(targets)

    // For each element in bins, there exists at least one element in targets that is equal to it.
    val constraints = bins.map { binElement =>
      smtOr(targetValues.map(_ === binElement).toSeq*)
    }

    // Combine all constraints with AND and assert
    smtAssert(smtAnd(constraints*))

  def coverNoHazard(
    hasRd:  Boolean = false,
    hasRs1: Boolean = false,
    hasRs2: Boolean = false
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    val recipe = summon[Recipe]
    val pairs  = insts.zip(insts.tail)

    smtAssert(smtOr(pairs.map { case (e, l) =>
      (l.rs1 =/= e.rd) & (l.rs2 =/= e.rd) & (e.rs1 =/= l.rd) & (e.rs2 =/= l.rd) & (e.rd =/= l.rd)
    }.toSeq*))

  def coverRAW(
    hasRd:  Boolean = false,
    hasRs1: Boolean = false,
    hasRs2: Boolean = false
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    val recipe = summon[Recipe]
    val pairs  = insts.zip(insts.tail)

    if (hasRd && hasRs1 && hasRs2) {
      // rs1 or rs2 of the later instruction is equal to rd of the earlier instruction
      smtAssert(smtOr(pairs.map { case (e, l) =>
        (l.rs1 === e.rd) | (l.rs2 === e.rd)
      }.toSeq*))
    } else if (hasRd && hasRs1) {
      // only rs1 of the later instruction is equal to rd of the earlier instruction
      smtAssert(smtOr(pairs.map { case (e, l) =>
        (l.rs1 === e.rd)
      }.toSeq*))
    }

  def coverWAR(
    hasRd:  Boolean = false,
    hasRs1: Boolean = false,
    hasRs2: Boolean = false
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    val recipe = summon[Recipe]
    val pairs  = insts.zip(insts.tail)

    if (hasRd && hasRs1 && hasRs2) {
      // rs1 or rs2 of the later instruction is equal to rd of the earlier instruction
      smtAssert(smtOr(pairs.map { case (e, l) =>
        ((e.rs1 === l.rd) | (e.rs2 === l.rd)) & !((l.rs1 === e.rd) | (l.rs2 === e.rd)) // and not raw
      }.toSeq*))
    } else if (hasRd && hasRs1) {
      // only rs1 of the later instruction is equal to rd of the earlier instruction
      smtAssert(smtOr(pairs.map { case (e, l) =>
        (e.rs1 === l.rd) & !((l.rs1 === e.rd))
      }.toSeq*))
    }

  def coverWAW(
    hasRd:  Boolean = false,
    hasRs1: Boolean = false,
    hasRs2: Boolean = false
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    val recipe = summon[Recipe]
    val pairs  = insts.zip(insts.tail)

    if (hasRd) {
      // rs1 or rs2 of the later instruction is equal to rd of the earlier instruction
      smtAssert(smtOr(pairs.map { case (e, l) =>
        (e.rd === l.rd)
      }.toSeq*))
    }