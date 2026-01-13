// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.tpe.*

import me.jiuyang.rvprobe.constraints.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Location, LocationApi, Operation, Type, Value, given}
import org.chipsalliance.rvdecoderdb.Instruction

import java.lang.foreign.Arena

// Lazy cache for instruction lookup by name
private lazy val instructionsByName: Map[String, (Int, Instruction)] = {
  getInstructions().zipWithIndex.map { case (inst, idx) => (inst.name, (idx, inst)) }.toMap
}

/** Get opcode ID by instruction name (e.g., "addw" -> 55) */
def getOpcodeIdByName(name: String): Int = {
  instructionsByName.get(name) match {
    case Some((idx, _)) => idx
    case None => throw new IllegalArgumentException(s"Unknown instruction: $name")
  }
}

/** Helper to check if an instruction has specific args based on its definition */
private def instructionHasArg(opcodeId: Int, argName: String): Boolean = {
  val instructions = getInstructions()
  instructions(opcodeId).args.exists(_.name == argName)
}

private def instructionHasRd(opcodeId: Int): Boolean = instructionHasArg(opcodeId, "rd")
private def instructionHasRs1(opcodeId: Int): Boolean = instructionHasArg(opcodeId, "rs1")
private def instructionHasRs2(opcodeId: Int): Boolean = instructionHasArg(opcodeId, "rs2")

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

  /** Cover no hazard with explicit opcode ID for all instructions (assumes same type) */
  def coverNoHazard(
    opcodeId: Int
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    val pairs = insts.zip(insts.tail)

    val hasRd  = instructionHasRd(opcodeId)
    val hasRs1 = instructionHasRs1(opcodeId)
    val hasRs2 = instructionHasRs2(opcodeId)

    if (hasRd && hasRs1 && hasRs2) {
      smtAssert(smtOr(pairs.map { case (e, l) =>
        (l.rs1 =/= e.rd) & (l.rs2 =/= e.rd) & (e.rs1 =/= l.rd) & (e.rs2 =/= l.rd) & (e.rd =/= l.rd)
      }.toSeq*))
    } else if (hasRd && hasRs1) {
      smtAssert(smtOr(pairs.map { case (e, l) =>
        (l.rs1 =/= e.rd) & (e.rs1 =/= l.rd) & (e.rd =/= l.rd)
      }.toSeq*))
    }

  /** Cover no hazard with instruction name */
  def coverNoHazardByName(
    instName: String
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit = coverNoHazard(getOpcodeIdByName(instName))

  /** Cover RAW (Read After Write) hazard with explicit opcode ID */
  def coverRAW(
    opcodeId: Int
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    val pairs = insts.zip(insts.tail)

    val hasRd  = instructionHasRd(opcodeId)
    val hasRs1 = instructionHasRs1(opcodeId)
    val hasRs2 = instructionHasRs2(opcodeId)

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

  /** Cover RAW with instruction name */
  def coverRAWByName(
    instName: String
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit = coverRAW(getOpcodeIdByName(instName))

  /** Cover WAR (Write After Read) hazard with explicit opcode ID */
  def coverWAR(
    opcodeId: Int
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    val pairs = insts.zip(insts.tail)

    val hasRd  = instructionHasRd(opcodeId)
    val hasRs1 = instructionHasRs1(opcodeId)
    val hasRs2 = instructionHasRs2(opcodeId)

    if (hasRd && hasRs1 && hasRs2) {
      // rs1 or rs2 of the earlier instruction is equal to rd of the later instruction
      smtAssert(smtOr(pairs.map { case (e, l) =>
        ((e.rs1 === l.rd) | (e.rs2 === l.rd)) & !((l.rs1 === e.rd) | (l.rs2 === e.rd)) // and not raw
      }.toSeq*))
    } else if (hasRd && hasRs1) {
      // only rs1 of the earlier instruction is equal to rd of the later instruction
      smtAssert(smtOr(pairs.map { case (e, l) =>
        (e.rs1 === l.rd) & !(l.rs1 === e.rd)
      }.toSeq*))
    }

  /** Cover WAR with instruction name */
  def coverWARByName(
    instName: String
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit = coverWAR(getOpcodeIdByName(instName))

  /** Cover WAW (Write After Write) hazard with explicit opcode ID */
  def coverWAW(
    opcodeId: Int
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    val pairs = insts.zip(insts.tail)

    val hasRd = instructionHasRd(opcodeId)

    if (hasRd) {
      // rd of the later instruction is equal to rd of the earlier instruction
      smtAssert(smtOr(pairs.map { case (e, l) =>
        (e.rd === l.rd)
      }.toSeq*))
    }

  /** Cover WAW with instruction name */
  def coverWAWByName(
    instName: String
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit = coverWAW(getOpcodeIdByName(instName))
