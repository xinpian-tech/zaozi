package me.jiuyang.rvprobe

import org.chipsalliance.rvdecoderdb.*
import os.Path

// Cached instruction argument lookup table for performance optimization
object InstructionArgsCache {
  // Lazy initialization: only load instructions once when first accessed
  private lazy val instructionArgsMap: Map[Int, Set[String]] = {
    val instructions = me.jiuyang.rvprobe.getInstructions()
    instructions.zipWithIndex.map { case (instr, idx) =>
      idx -> instr.args.map(_.name).toSet
    }.toMap
  }

  def hasArg(opcodeId: Int, argName: String): Boolean =
    instructionArgsMap.get(opcodeId).exists(_.contains(argName))

  def hasRd(opcodeId:  Int): Boolean = hasArg(opcodeId, "rd")
  def hasRs1(opcodeId: Int): Boolean = hasArg(opcodeId, "rs1")
  def hasRs2(opcodeId: Int): Boolean = hasArg(opcodeId, "rs2")
}

def getInstructions(): Seq[Instruction] =
  val riscvOpcodesPath: Path = Path(
    sys.env.getOrElse(
      "RISCV_OPCODES_INSTALL_PATH",
      throw new RuntimeException("Environment variable RISCV_OPCODES_INSTALL_PATH not set")
    )
  )
  org.chipsalliance.rvdecoderdb
    .instructions(riscvOpcodesPath)
    .toSeq
    .filter {
      // special case for rv32 pseudo from rv64
      case i if i.pseudoFrom.isDefined && Seq("slli", "srli", "srai").contains(i.name) => true
      case i if i.pseudoFrom.isDefined                                                 => false
      case _                                                                           => true
    }
    .sortBy(i => (i.instructionSet.name, i.name))

// set argLut
def getArgLut(): Seq[(String, org.chipsalliance.rvdecoderdb.Arg)] =
  val riscvOpcodesPath: Path = Path(
    sys.env.getOrElse(
      "RISCV_OPCODES_INSTALL_PATH",
      throw new RuntimeException("Environment variable RISCV_OPCODES_INSTALL_PATH not set")
    )
  )
  org.chipsalliance.rvdecoderdb
    .argLut(riscvOpcodesPath, None)
    .toSeq
    .sortBy(i => (i._1, i._2.name))

def getExtensions(): Seq[String] =
  val riscvOpcodesPath: Path = Path(
    sys.env.getOrElse(
      "RISCV_OPCODES_INSTALL_PATH",
      throw new RuntimeException("Environment variable RISCV_OPCODES_INSTALL_PATH not set")
    )
  )
  org.chipsalliance.rvdecoderdb
    .instructions(riscvOpcodesPath)
    .toSeq
    .map(_.instructionSets)
    .flatMap(_.map(_.name))
    .distinct
    .sorted

// helper function
def translateToCamelCase(s: String): String =
  s.replace(".", "_")
    .split("[^a-zA-Z0-9]")
    .filter(_.nonEmpty)
    .map(_.capitalize)
    .mkString
