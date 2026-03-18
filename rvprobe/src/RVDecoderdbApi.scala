package me.jiuyang.rvprobe

import org.chipsalliance.rvdecoderdb.*
import os.Path

// Cached instruction argument lookup table for performance optimization
object InstructionArgsCache {
  // Lazy initialization: only load instructions once when first accessed
  private lazy val instructionArgsMap: Map[Int, Set[String]] = {
    val merged = me.jiuyang.rvprobe.getMergedInstructions()
    merged.zipWithIndex.map { case (variant, idx) =>
      idx -> variant.args.map(_.name).toSet
    }.toMap
  }

  def hasArg(opcodeId: Int, argName: String): Boolean =
    instructionArgsMap.get(opcodeId).exists(_.contains(argName))

  def hasRd(opcodeId:  Int): Boolean = hasArg(opcodeId, "rd")
  def hasRs1(opcodeId: Int): Boolean = hasArg(opcodeId, "rs1")
  def hasRs2(opcodeId: Int): Boolean = hasArg(opcodeId, "rs2")
}

// A merged view of instructions with OR'd sets.
case class MergedInstructionVariant(
  name:  String,
  args:  Seq[org.chipsalliance.rvdecoderdb.Arg],
  sets:  Seq[InstructionSet]
)

def getInstructions(): Seq[Instruction] =
  val riscvOpcodesPath: Path = Path(
    sys.env.getOrElse(
      "RISCV_OPCODES_INSTALL_PATH",
      throw new RuntimeException("Environment variable RISCV_OPCODES_INSTALL_PATH not set")
    )
  )
  // Must use the same ordering as getMergedInstructions() so that
  // nameId indices from constraint solving map to the correct instruction.
  org.chipsalliance.rvdecoderdb
    .instructions(riscvOpcodesPath)
    .toSeq
    .groupBy(_.name)
    .toSeq
    .sortBy(_._1)
    .map(_._2.maxBy(_.args.size))

// Returns one entry per unique instruction name with sets merged.
// Picks the variant with the most args as representative.
// Used for nameId assignment (RVConstraints) and InstructionArgsCache.
def getMergedInstructions(): Seq[MergedInstructionVariant] =
  val riscvOpcodesPath: Path = Path(
    sys.env.getOrElse(
      "RISCV_OPCODES_INSTALL_PATH",
      throw new RuntimeException("Environment variable RISCV_OPCODES_INSTALL_PATH not set")
    )
  )
  val all = org.chipsalliance.rvdecoderdb
    .instructions(riscvOpcodesPath)
    .toSeq

  all
    .groupBy(_.name)
    .toSeq
    .sortBy(_._1)
    .map { case (name, instrs) =>
      val representative = instrs.maxBy(_.args.size)
      MergedInstructionVariant(
        name = name,
        args = representative.args,
        sets = instrs.flatMap(_.instructionSets).distinctBy(_.name)
      )
    }

// Returns all unique (name, args) variants with sets merged.
// Same name + different args → separate variants (pseudo-instruction overloads).
// Used by UpdateAsmApi to generate overloaded methods.
def getAllMergedVariants(): Seq[MergedInstructionVariant] =
  val riscvOpcodesPath: Path = Path(
    sys.env.getOrElse(
      "RISCV_OPCODES_INSTALL_PATH",
      throw new RuntimeException("Environment variable RISCV_OPCODES_INSTALL_PATH not set")
    )
  )
  val all = org.chipsalliance.rvdecoderdb
    .instructions(riscvOpcodesPath)
    .toSeq

  // Group by name, then within each name group, sub-group by arg names
  all
    .groupBy(_.name)
    .toSeq
    .sortBy(_._1)
    .flatMap { case (name, instrs) =>
      // Sub-group by arg signature (list of arg names, order-sensitive)
      instrs
        .groupBy(_.args.map(_.name).toList)
        .toSeq
        .sortBy(_._1.mkString(","))
        .map { case (_, variants) =>
          MergedInstructionVariant(
            name = name,
            args = variants.head.args,
            sets = variants.flatMap(_.instructionSets).distinctBy(_.name)
          )
        }
    }

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
