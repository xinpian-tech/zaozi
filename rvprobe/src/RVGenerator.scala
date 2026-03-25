// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe

import me.jiuyang.smtlib.tpe.{Bool, Ref}
import me.jiuyang.smtlib.parser.{parseZ3OutputOrFail, Z3Result, Z3Status}
import me.jiuyang.smtlib.default.{*, given}

import me.jiuyang.rvprobe.constraints.*

import org.llvm.mlir.scalalib.capi.dialect.func.{DialectApi as FuncDialect, Func, FuncApi, given}
import org.llvm.mlir.scalalib.capi.dialect.smt.{given_DialectApi, DialectApi as SmtDialect}
import org.llvm.mlir.scalalib.capi.target.exportsmtlib.given_ExportSmtlibApi
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, LocationApi, Module, ModuleApi, Value, given}

import java.lang.foreign.Arena
import java.io.FileOutputStream
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.nio.charset.StandardCharsets

import scala.compiletime.uninitialized

/** Output mode selector for the unified [[RVGenerator.emit]] API. */
sealed trait OutputMode

/** Emit a complete GAS assembly file (template + recipe instructions). */
case object AsmMode extends OutputMode

/** Emit raw binary machine code (existing behaviour). */
case object BinMode extends OutputMode

/** RVGenerator - Two-Stage SMT Constraint Solving for RISC-V Instruction Generation
  *
  * The solving process is split into two independent stages:
  *
  * Stage 1 (Opcodes): Solve which instruction to use
  *   - Input: Set constraints (isRV64I, etc.) + Opcode constraints (isAddw, etc.)
  *   - Output: Map[Int, Int] mapping index -> nameId
  *
  * Stage 2 (Args): Solve instruction arguments given fixed opcodes
  *   - Input: Fixed opcodes + Auto arg ranges (from instruction definition) + User arg constraints (rdRange, etc.)
  *   - Output: Map[String, BigInt] mapping argName_idx -> value
  *
  * The key insight is that these two stages are INDEPENDENT:
  *   - Stage 1 only uses opcode-related constraints
  *   - Stage 2 only uses arg-related constraints (after opcodes are fixed)
  */
trait RVGenerator:
  def constraints(): (Arena, Context, Block, Recipe) ?=> Unit // should be implemented by subclass
  val sets: Seq[Recipe ?=> SetConstraint] // should be implemented by subclass
  val name: String = this.getClass.getSimpleName
  val seed: Int    = scala.util.Random.nextInt(Int.MaxValue)

  // ================== Stage 1: Opcode Solving ==================

  /** Helper for Stage 1: Create fresh MLIR context for opcode solving */
  private def withOpcodeContext[T](postProcess: (Arena, Context, Module, Recipe) => T): T = {
    given arena:   Arena   = Arena.ofConfined()
    given context: Context = summon[ContextApi].contextCreate
    summon[SmtDialect].loadDialect()
    summon[FuncDialect].loadDialect()
    given module:  Module  = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
    val func = summon[FuncApi].op("func")
    given funcBlock: Block = func.block
    func.appendToModule()

    var capturedRecipe: Recipe = null
    try
      solver {
        smtSetLogic("QF_LIA")
        given Recipe = new Recipe(name)
        // 1. Register set constraints
        sets.foreach { constraint =>
          summon[Recipe].addSetConstraint(r =>
            constraint(
              using r
            ).toRef
          )
        }
        // 2. Call constraints() to register Index and opcode/arg constraints
        constraints()
        // 3. Apply only opcode-related constraints
        emitOpcodeConstraints()
        capturedRecipe = summon[Recipe]
      }
      postProcess(arena, context, module, capturedRecipe)
    finally
      context.destroy()
      arena.close()
  }

  // ================== Stage 2: Arg Solving ==================

  /** Helper for Stage 2: Create fresh MLIR context for arg solving */
  private def withArgContext[T](
    solvedOpcodes: Map[Int, Int],
    postProcess:   (Arena, Context, Module, Recipe) => T
  ): T = {
    given arena:   Arena   = Arena.ofConfined()
    given context: Context = summon[ContextApi].contextCreate
    summon[SmtDialect].loadDialect()
    summon[FuncDialect].loadDialect()
    given module:  Module  = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
    val func = summon[FuncApi].op("func")
    given funcBlock: Block = func.block
    func.appendToModule()

    var capturedRecipe: Recipe = null
    try
      solver {
        smtSetLogic("QF_LIA")
        given Recipe = new Recipe(name)
        // 1. Call constraints() to register Index and arg constraints
        //    (We need the Index objects and user arg constraints like rdRange)
        constraints()
        // 2. Apply only arg-related constraints (with fixed opcodes)
        emitArgConstraints(solvedOpcodes)
        capturedRecipe = summon[Recipe]
      }
      postProcess(arena, context, module, capturedRecipe)
    finally
      context.destroy()
      arena.close()
  }

  // ================== Two-Stage Solving API ==================
  def solveOpcodes(): (Map[Int, Int], Seq[Statement]) = withOpcodeContext(
    postProcess = (arena, context, module, recipe) => {
      given Arena  = arena
      given Module = module
      val smtlib   = mlirToSMTLIB()
      val z3Output = toZ3Output(smtlib)
      try {
        val result  = parseZ3OutputOrFail(
          z3Output = z3Output,
          smtlib = smtlib,
          z3Runner = runZ3,
          context = "Opcode solving"
        )
        val opcodes = result.model.collect {
          case (k, v: BigInt) if k.startsWith("nameId_") =>
            k.stripPrefix("nameId_").toInt -> v.toInt
        }
        (opcodes, recipe.allStatements())
      } catch {
        case e: RuntimeException => throw e // Re-throw our custom exception
        case e: Exception        =>
          System.err.println(s"SMTLIB:\n$smtlib")
          System.err.println(s"Z3 Parsing/Solving failed. Output:\n$z3Output")
          throw e
      }
    }
  )

  /** Stage 2 Helper: Parse Z3 output to get all variables */
  def solveArgs(solvedOpcodes: Map[Int, Int]): Map[String, BigInt] = withArgContext(
    solvedOpcodes = solvedOpcodes,
    postProcess = (arena, context, module, recipe) => {
      given Arena  = arena
      given Module = module
      val smtlib   = mlirToSMTLIB()
      val z3Output = toZ3Output(smtlib)
      try {
        val result = parseZ3OutputOrFail(
          z3Output = z3Output,
          smtlib = smtlib,
          z3Runner = runZ3,
          context = "Argument solving"
        )
        result.model.collect { case (k, v: BigInt) => k -> v }
      } catch {
        case e: RuntimeException => throw e // Re-throw our custom exception
        case e: Exception        =>
          System.err.println(s"SMTLIB:\n$smtlib")
          System.err.println(s"Z3 Parsing/Solving failed. Output:\n$z3Output")
          throw e
      }
    }
  )

  // ================== Constraint Emission (Internal) ==================
  /** Stage 1: Emit only opcode-related constraints */
  private def emitOpcodeConstraints(
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit = {
    val recipe = summon[Recipe]

    // 1. Apply Set constraints (which instruction sets are enabled)
    val includedSets = recipe.getSetConstraints().map(_(recipe))
    val excludedSets = recipe.allSets.diff(includedSets)
    smtAssert(smtAnd(includedSets*))
    smtAssert(!smtOr(excludedSets*))

    // 2. Apply User Opcode Constraints from each Index (isAddw, etc.)
    recipe.allIndices().foreach { idx =>
      val index   = recipe.getIndex(idx)
      val opcodes = index.getOpcodeConstraints().map(_(index))
      if (opcodes.nonEmpty) {
        smtAssert(smtAnd(opcodes*))
      }
    }
    smtCheck
  }

  /** Stage 2: Emit only arg-related constraints (with fixed opcodes) */
  private def emitArgConstraints(
    solvedOpcodes: Map[Int, Int]
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit = {
    val recipe       = summon[Recipe]
    val instructions = getInstructions()

    // Note: We deliberately SKIP set constraints here - opcodes are already solved.

    recipe.allIndices().foreach { idx =>
      val index    = recipe.getIndex(idx)
      val opcodeId = solvedOpcodes.getOrElse(idx, throw new RuntimeException(s"No solved opcode for index $idx"))

      // Store opcodeId in Index for later use (e.g., cover constraints)
      index.setOpcodeId(opcodeId)

      // 1. Fix Opcode (this is a given, not a constraint to solve)
      smtAssert(index.nameId === opcodeId.S)

      // 2. Apply User Arg Constraints from Index (rdRange, rs1Range, etc.)
      val args = index.getArgConstraints().map(_(index))
      if (args.nonEmpty) {
        smtAssert(smtAnd(args*))
      }
    }

    // 3. Execute cross-index constraints (cover constraints)
    recipe.executeCrossIndexConstraints()

    smtCheck
  }

  // ================== Helpers ==================
  private def mlirToSMTLIB(
  )(
    using Arena,
    Module
  ): String = {
    val out = new StringBuilder
    summon[Module].exportSMTLIB(out ++= _)
    out.toString()
  }

  /** Run Z3 on given SMTLIB input
    *
    * @param smtlib
    *   The SMTLIB input to run
    * @return
    *   The output from Z3
    */
  private def runZ3(smtlib: String): String = {
    val z3Output = os
      .proc("z3", "-in", "-t:5000")
      .call(stdin = smtlib, check = false)
    z3Output.out.text()
  }

  /** Inject Z3 randomization options before `(set-logic ...)` so that each solve explores a different search path.
    * Override [[seed]] to fix the value for reproducibility.
    */
  private def injectRandomSeed(smtlib: String): String =
    val options = Seq(
      s"(set-option :smt.random_seed $seed)",
      s"(set-option :sat.random_seed $seed)"
    ).mkString("\n")
    smtlib.replaceFirst(
      """\(set-logic """,
      s"$options\n(set-logic "
    )

  private def toZ3Output(smtlib: String): String = {
    val randomized         = injectRandomSeed(smtlib)
    // Replace (reset) with (get-model) to get the model output
    val smtlibWithGetModel = randomized.replace("(reset)", "(get-model)")
    runZ3(smtlibWithGetModel)
  }

  // ================== Stage 1 Helper ==================
  def toOpcodeSMTLIB(): String = withOpcodeContext(
    postProcess = (arena, context, module, recipe) => {
      given Arena  = arena
      given Module = module
      mlirToSMTLIB()
    }
  )

  def printOpcodeSMTLIB() = println(toOpcodeSMTLIB())

  def toOpcodeMLIR(): String = withOpcodeContext(
    postProcess = (arena, context, module, recipe) => {
      given Arena  = arena
      given Module = module
      val out      = new StringBuilder
      summon[Module].getOperation.print(out ++= _)
      out.toString()
    }
  )

  def printOpcodeMLIR() = println(toOpcodeMLIR())

  def toOpcodeZ3Output(): String = {
    val smtlib = toOpcodeSMTLIB()
    toZ3Output(smtlib)
  }

  def printOpcodeZ3Output() = println(toOpcodeZ3Output())

  // ================== Stage 2 Helper ==================
  def toArgSMTLIB(): String = withArgContext(
    solvedOpcodes = solveOpcodes()._1,
    postProcess = (arena, context, module, recipe) => {
      given Arena  = arena
      given Module = module
      mlirToSMTLIB()
    }
  )

  def printArgSMTLIB() = println(toArgSMTLIB())

  def toArgMLIR(): String = withArgContext(
    solvedOpcodes = solveOpcodes()._1,
    postProcess = (arena, context, module, recipe) => {
      given Arena  = arena
      given Module = module
      val out      = new StringBuilder
      summon[Module].getOperation.print(out ++= _)
      out.toString()
    }
  )

  def printArgMLIR() = println(toArgMLIR())

  def toArgZ3Output(): String = {
    val smtlib = toArgSMTLIB()
    toZ3Output(smtlib)
  }

  def printArgZ3Output() = println(toArgZ3Output())

  // ================== Assembly & Legacy ==================
  // NOP instruction encoding: ADDI x0, x0, 0 = 0x00000013
  private val NOP_ENCODING: Long              = 0x00000013L
  private val NOP_BYTES:    scala.Array[Byte] = scala.Array[Byte](
    (NOP_ENCODING & 0xff).toByte,
    ((NOP_ENCODING >> 8) & 0xff).toByte,
    ((NOP_ENCODING >> 16) & 0xff).toByte,
    ((NOP_ENCODING >> 24) & 0xff).toByte
  )

  // C.NOP instruction encoding: 0x0001
  private val C_NOP_ENCODING: Int               = 0x0001
  private val C_NOP_BYTES:    scala.Array[Byte] = scala.Array[Byte](
    (C_NOP_ENCODING & 0xff).toByte,
    ((C_NOP_ENCODING >> 8) & 0xff).toByte
  )

  /** Check if instruction is a compressed (16-bit) instruction. C extension instructions have names starting with "c."
    * or belong to instruction sets containing "_c".
    */
  private def isCompressedInstruction(inst: org.chipsalliance.rvdecoderdb.Instruction): Boolean =
    inst.name.startsWith("c.") || inst.instructionSets.exists(_.name.contains("_c"))

  def assembleInstructions(
    solvedOpcodes: Map[Int, Int],
    solvedArgs:    Map[String, BigInt]
  ): Seq[(Array[Byte], String)] = {
    val instructions  = getInstructions()
    val sortedIndices = solvedOpcodes.keys.toSeq.sorted

    // Generate instructions with NOP padding for non-contiguous indices
    val result = scala.collection.mutable.ListBuffer[(Array[Byte], String)]()

    sortedIndices.zipWithIndex.foreach { case (i, pos) =>
      // Insert NOP instructions for gaps
      if (pos == 0) {
        // For the first instruction, insert NOPs from index 0 to i-1
        for (nopIdx <- 0 until i) {
          result += ((NOP_BYTES.clone(), s"$nopIdx: nop"))
        }
      } else {
        // Insert NOP instructions for gaps between indices
        val prevIndex = sortedIndices(pos - 1)
        val gap       = i - prevIndex - 1
        for (nopIdx <- 1 to gap) {
          val nopPos = prevIndex + nopIdx
          result += ((NOP_BYTES.clone(), s"$nopPos: nop"))
        }
      }

      val nameId = solvedOpcodes(i)
      val inst   = instructions(nameId)

      val (args, bits) = inst.args.foldLeft((Vector.empty[String], inst.encoding.value)) {
        case ((argsAcc, bitsAcc), arg) =>
          val argName        = translateToCamelCase(arg.name)
          val argNameLowered = argName.head.toLower + argName.tail
          val prefix         = if arg.name.startsWith("r") then "x" else ""

          // Fetch value from solvedArgs
          val argValue = solvedArgs.getOrElse(
            argNameLowered + s"_$i",
            BigInt(0)
          )

          val processedValue: Long = if argValue < 0 then
            val fieldWidth = arg.msb - arg.lsb + 1
            val mask       = (1L << fieldWidth) - 1
            argValue.toLong & mask
          else argValue.toLong

          (
            argsAcc :+ (prefix + argValue.toString),
            bitsAcc | (BigInt(processedValue) << arg.lsb)
          )
      }

      val instrString = s"$i: ${inst.name} ${args.mkString(" ")}"

      val isCompressed = isCompressedInstruction(inst)
      val bytes        =
        if (isCompressed) {
          val value: Int = bits.toInt & 0xffff
          scala.Array[Byte](
            (value & 0xff).toByte,
            ((value >> 8) & 0xff).toByte
          )
        } else {
          val value: Long = bits.toLong & 0xffffffffL
          scala.Array[Byte](
            (value & 0xff).toByte,
            ((value >> 8) & 0xff).toByte,
            ((value >> 16) & 0xff).toByte,
            ((value >> 24) & 0xff).toByte
          )
        }

      result += ((bytes, instrString))
    }

    result.toSeq
  }

  def toInstructions(): Seq[(Array[Byte], String)] = {
    val (opcodes, _) = solveOpcodes()
    val args         = solveArgs(opcodes)
    assembleInstructions(opcodes, args)
  }

  def printInstructions(): Unit =
    val outputs = toInstructions()
    outputs.foreach { case (_, instrStr) => println(instrStr) }

  def writeInstructionsToFile(filename: String = s"${name}_output.bin"): Unit =
    val outputs = toInstructions()
    val fos     = new FileOutputStream(filename, true)
    try
      outputs.foreach { case (bytes, _) => fos.write(bytes) }
    finally fos.close()
  // ================== Template / GAS Assembly ==================

  /** True when instruction `name` is a memory load (formats as `name rd, imm(rs1)`). */
  private def isLoadInstruction(name: String): Boolean =
    Set("lb", "lh", "lw", "ld", "lbu", "lhu", "lwu", "flw", "fld", "flq", "c.lw", "c.ld", "c.flw", "c.fld").contains(
      name
    )

  /** Combine a split immediate (e.g. imm12hi/imm12lo) into a single signed value.
    *
    * Both fields are retrieved from `solvedArgs` using their camelCase key + `_idx`. After masking to unsigned within
    * each field width, the bits are concatenated and sign-extended to the full `hiWidth + loWidth` precision.
    */
  private def combineSignedImm(
    hiName:  String,
    loName:  String,
    hiWidth: Int,
    loWidth: Int,
    idx:     Int,
    solved:  Map[String, BigInt]
  ): BigInt = {
    def key(n: String): String =
      val s = translateToCamelCase(n); (s.head.toLower + s.tail) + s"_$idx"
    val hi = solved.getOrElse(key(hiName), BigInt(0)) & ((BigInt(1) << hiWidth) - 1)
    val lo    = solved.getOrElse(key(loName), BigInt(0)) & ((BigInt(1) << loWidth) - 1)
    val raw   = (hi << loWidth) | lo
    val total = hiWidth + loWidth
    if (raw.testBit(total - 1)) raw - (BigInt(1) << total) else raw
  }

  private def signedFieldValue(
    idx:    Int,
    name:   String,
    width:  Int,
    solved: Map[String, BigInt]
  ): BigInt = {
    def key(n: String): String =
      val s = translateToCamelCase(n); (s.head.toLower + s.tail) + s"_$idx"
    val raw = solved.getOrElse(key(name), BigInt(0)) & ((BigInt(1) << width) - 1)
    if (raw.testBit(width - 1)) raw - (BigInt(1) << width) else raw
  }

  private def unsignedFieldValue(
    idx:    Int,
    name:   String,
    width:  Int,
    solved: Map[String, BigInt]
  ): BigInt = {
    def key(n: String): String =
      val s = translateToCamelCase(n); (s.head.toLower + s.tail) + s"_$idx"
    solved.getOrElse(key(name), BigInt(0)) & ((BigInt(1) << width) - 1)
  }

  private def fenceMaskToGas(mask: Int): String = {
    val ordered  = Seq(8 -> "i", 4 -> "o", 2 -> "r", 1 -> "w")
    val rendered = ordered.collect { case (bit, label) if (mask & bit) != 0 => label }.mkString
    if rendered.isEmpty then "0" else rendered
  }

  private def withAqRlSuffix(
    idx:    Int,
    solved: Map[String, BigInt],
    base:   String
  ): String = {
    def key(n: String): String =
      val s = translateToCamelCase(n); (s.head.toLower + s.tail) + s"_$idx"
    val aq = solved.getOrElse(key("aq"), BigInt(0)).toInt
    val rl = solved.getOrElse(key("rl"), BigInt(0)).toInt
    (aq, rl) match
      case (0, 0) => base
      case (1, 0) => s"$base.aq"
      case (0, 1) => s"$base.rl"
      case _      => s"$base.aqrl"
  }

  /** Format a single solved instruction as a GAS assembly line (without indentation).
    *
    * Handles the main RISC-V base/extension instruction formats:
    *   - R-type : `name rd, rs1, rs2`
    *   - I-type : `name rd, rs1, imm` (ALU, shifts, JALR)
    *   - Load : `name rd, imm(rs1)`
    *   - Store : `name rs2, imm(rs1)` (imm12hi/lo combined)
    *   - Branch : `name rs1, rs2, . + imm` (bimm12hi/lo combined)
    *   - JAL : `name rd, . + imm`
    *   - U-type : `name rd, imm`
    *   - Fallback: comma-separated args in rvdecoderdb order
    */
  /** Reverse CSR number → name mapping for GAS output. */
  private val csrNumberToName: Map[Int, String] = Map(
    0x300 -> "mstatus",
    0x301 -> "misa",
    0x302 -> "medeleg",
    0x303 -> "mideleg",
    0x304 -> "mie",
    0x305 -> "mtvec",
    0x306 -> "mcounteren",
    0x340 -> "mscratch",
    0x341 -> "mepc",
    0x342 -> "mcause",
    0x343 -> "mtval",
    0x344 -> "mip",
    0x3a0 -> "pmpcfg0",
    0x3a1 -> "pmpcfg1",
    0x3a2 -> "pmpcfg2",
    0x3a3 -> "pmpcfg3",
    0x3b0 -> "pmpaddr0",
    0x3b1 -> "pmpaddr1",
    0x3b2 -> "pmpaddr2",
    0x3b3 -> "pmpaddr3",
    0xf11 -> "mvendorid",
    0xf12 -> "marchid",
    0xf13 -> "mimpid",
    0xf14 -> "mhartid",
    0x100 -> "sstatus",
    0x104 -> "sie",
    0x105 -> "stvec",
    0x106 -> "scounteren",
    0x140 -> "sscratch",
    0x141 -> "sepc",
    0x142 -> "scause",
    0x143 -> "stval",
    0x144 -> "sip",
    0x180 -> "satp",
    0x000 -> "ustatus",
    0x001 -> "fflags",
    0x002 -> "frm",
    0x003 -> "fcsr",
    0xc00 -> "cycle",
    0xc01 -> "time",
    0xc02 -> "instret"
  )

  /** RISC-V rounding mode number → name for GAS output. */
  private val rmNumberToName: Map[Int, String] = Map(
    0 -> "rne",
    1 -> "rtz",
    2 -> "rdn",
    3 -> "rup",
    4 -> "rmm",
    7 -> "dyn"
  )

  /** Instruction names that use floating-point registers (rd/rs1/rs2 are FP regs). */
  private val fpInstructionPrefixes: Set[String] = Set(
    "fadd",
    "fsub",
    "fmul",
    "fdiv",
    "fsqrt",
    "fmadd",
    "fmsub",
    "fnmadd",
    "fnmsub",
    "fsgnj",
    "fsgnjn",
    "fsgnjx",
    "fmin",
    "fmax",
    "fcvt",
    "fmv",
    "fclass",
    "feq",
    "flt",
    "fle",
    "fround",
    "fli",
    "fleq",
    "fltq",
    "fminm",
    "fmaxm",
    "fld",
    "flw",
    "flh",
    "flq",
    "fsd",
    "fsw",
    "fsh",
    "fsq"
  )

  /** Check whether a given arg is a floating-point register for this instruction. */
  private def isFpReg(instName: String, argName: String): Boolean =
    val baseName = instName.split('.').head.toLowerCase
    val isFpInst = fpInstructionPrefixes.exists(p => baseName.startsWith(p))
    if !isFpInst then return false
    argName match
      // For FP instructions: rd, rs1, rs2, rs3 are FP regs,
      // EXCEPT: fcvt.*.int, fmv.x.*, fclass.* where rd is integer;
      //         fmv.*.x, fcvt.int.* where rs1 is integer
      case "rd"          =>
        // fmv.x.d, fmv.x.w, fclass.*, feq/flt/fle: rd is integer
        val intRd = instName.startsWith("fmv.x.") || instName.startsWith("fclass.") ||
          instName.startsWith("feq.") || instName.startsWith("flt.") || instName.startsWith("fle.") ||
          instName.startsWith("fltq.") || instName.startsWith("fleq.") ||
          (instName.startsWith("fcvt.") && (instName.contains(".w") || instName.contains(".l")) && !instName.matches(
            "fcvt\\.[sdqh]\\..*"
          ))
        !intRd
      case "rs1"         =>
        // fmv.d.x, fmv.w.x: rs1 is integer
        val intRs1 = instName.startsWith("fmv.") && instName.endsWith(".x")
        !intRs1
      case "rs2" | "rs3" => true
      case _             => false

  private def toGasLine(
    idx:    Int,
    inst:   org.chipsalliance.rvdecoderdb.Instruction,
    solved: Map[String, BigInt]
  ): String = {
    val instName = inst.name
    val names    = inst.args.map(_.name).toSet

    def key(n: String):                     String =
      val s = translateToCamelCase(n); (s.head.toLower + s.tail) + s"_$idx"
    def regVal(n: String):                  String =
      val num    = solved.getOrElse(key(n), BigInt(0))
      val prefix = if isFpReg(instName, n) then "f" else "x"
      s"$prefix$num"
    def immVal(n:      String):             String = solved.getOrElse(key(n), BigInt(0)).toString
    def signedImm(n:   String, width: Int): String = signedFieldValue(idx, n, width, solved).toString
    def unsignedImm(n: String, width: Int): String = unsignedFieldValue(idx, n, width, solved).toString
    def csrVal:                             String =
      val num = solved.getOrElse(key("csr"), BigInt(0)).toInt
      csrNumberToName.getOrElse(num, s"0x${num.toHexString}")
    def rmVal:                              String =
      val num = solved.getOrElse(key("rm"), BigInt(0)).toInt
      rmNumberToName.getOrElse(num, num.toString)
    def argFmt(n: String):                  String =
      val prefix = if n.startsWith("r") then "x" else ""
      prefix + solved.getOrElse(key(n), BigInt(0)).toString

    val hasRd    = names("rd")
    val hasRs1   = names("rs1")
    val hasRs2   = names("rs2")
    val hasCsr   = names("csr") || solved.contains(key("csr"))
    val hasRm    = names("rm") || solved.contains(key("rm"))
    val hasImm12 = names("imm12")
    val hasSplit = names("imm12hi") && names("imm12lo")
    val hasBimm  = names("bimm12hi") && names("bimm12lo")
    val hasJimm  = names("jimm20")
    val hasImm20 = names("imm20")
    val hasShamt = names("shamt") || names("shamtw") || names("shamtd")

    if instName == "fence.tso" then "fence.tso"
    else if instName == "fence.i" then "fence.i"
    else if instName == "fence" && names("pred") && names("succ") then
      val pred = solved.getOrElse(key("pred"), BigInt(0)).toInt
      val succ = solved.getOrElse(key("succ"), BigInt(0)).toInt
      s"fence ${fenceMaskToGas(pred)}, ${fenceMaskToGas(succ)}"
    else if instName.startsWith("lr.") then
      val mnemonic = withAqRlSuffix(idx, solved, instName)
      s"$mnemonic ${regVal("rd")}, (${regVal("rs1")})"
    else if instName.startsWith("sc.") then
      val mnemonic = withAqRlSuffix(idx, solved, instName)
      s"$mnemonic ${regVal("rd")}, ${regVal("rs2")}, (${regVal("rs1")})"
    else if instName.startsWith("amo") || instName.startsWith("ssamo") then
      val mnemonic = withAqRlSuffix(idx, solved, instName)
      s"$mnemonic ${regVal("rd")}, ${regVal("rs2")}, (${regVal("rs1")})"
    else if (!hasRd && !hasRs1 && !hasRs2 && hasJimm)
      // Unconditional jump pseudo (j): jimm only, implicit rd=x0
      s"$instName . + ${immVal("jimm20")}"
    else if (!hasRd && !hasRs1 && !hasRs2 && !hasJimm)
      // System / fence instructions that take no register args
      instName
    else if (hasRd && hasRs1 && hasRs2 && !hasImm12 && !hasSplit && !hasRm)
      // R-type (no rounding mode)
      s"$instName ${regVal("rd")}, ${regVal("rs1")}, ${regVal("rs2")}"
    else if (hasRd && hasRs1 && hasShamt && !hasRs2)
      // Shift-immediate (slli / srli / srai / *w variants)
      // The rvdecoderdb arg name may differ from the AsmApi constraint key
      // (e.g., instruction has shamtw but AsmApi writes shamtd), so try all variants
      val shamtVariants = Seq("shamtd", "shamt", "shamtw")
      val sName         = shamtVariants
        .find(n => solved.contains(key(n)))
        .getOrElse(if names("shamt") then "shamt" else if names("shamtw") then "shamtw" else "shamtd")
      s"$instName ${regVal("rd")}, ${regVal("rs1")}, ${immVal(sName)}"
    else if (hasRd && hasRs1 && !hasRs2 && hasImm12 && isLoadInstruction(instName))
      // Load
      s"$instName ${regVal("rd")}, ${signedImm("imm12", 12)}(${regVal("rs1")})"
    else if (hasRd && hasRs1 && !hasRs2 && hasImm12)
      // I-type ALU / JALR
      s"$instName ${regVal("rd")}, ${regVal("rs1")}, ${signedImm("imm12", 12)}"
    else if (!hasRd && hasRs1 && hasRs2 && hasSplit)
      // Store
      val imm = combineSignedImm("imm12hi", "imm12lo", 7, 5, idx, solved)
      s"$instName ${regVal("rs2")}, $imm(${regVal("rs1")})"
    else if (!hasRd && hasRs1 && hasRs2 && hasBimm)
      // Branch
      val imm = combineSignedImm("bimm12hi", "bimm12lo", 7, 5, idx, solved)
      s"$instName ${regVal("rs1")}, ${regVal("rs2")}, . + $imm"
    else if (!hasRd && hasRs1 && !hasRs2 && hasBimm)
      // Pseudo-branch (beqz/bnez): rs1 + bimm, implicit rs2=x0
      val imm = combineSignedImm("bimm12hi", "bimm12lo", 7, 5, idx, solved)
      s"$instName ${regVal("rs1")}, . + $imm"
    else if (hasRd && hasJimm)
      // JAL
      s"$instName ${regVal("rd")}, . + ${immVal("jimm20")}"
    else if (hasRd && hasImm20)
      // LUI / AUIPC
      s"$instName ${regVal("rd")}, ${unsignedImm("imm20", 20)}"
    // === CSR instructions ===
    else if (hasCsr && hasRd && hasRs1)
      // csrrw rd, csr, rs1 / csrrs rd, csr, rs1 / csrrc rd, csr, rs1
      s"$instName ${regVal("rd")}, $csrVal, ${regVal("rs1")}"
    else if (hasCsr && !hasRd && hasRs1)
      // csrw csr, rs1 / csrs csr, rs1 / csrc csr, rs1 (pseudo: rd=x0 implicit)
      s"$instName $csrVal, ${regVal("rs1")}"
    else if (hasCsr && hasRd && !hasRs1)
      // csrr rd, csr (pseudo: rs1=x0 implicit)
      s"$instName ${regVal("rd")}, $csrVal"
    else if (hasCsr && names("zimm"))
      // csrrwi/csrrsi/csrrci with zimm
      if hasRd then s"$instName ${regVal("rd")}, $csrVal, ${immVal("zimm")}"
      else s"$instName $csrVal, ${immVal("zimm")}"
    // === FP instructions with rounding mode ===
    else if (hasRm && hasRd && hasRs1 && hasRs2)
      // fadd.s, fsub.s, fmul.s, fdiv.s etc: rd, rs1, rs2, rm
      s"$instName ${regVal("rd")}, ${regVal("rs1")}, ${regVal("rs2")}, $rmVal"
    else if (hasRm && hasRd && hasRs1 && !hasRs2)
      // fround.s, fsqrt.s etc: rd, rs1, rm
      s"$instName ${regVal("rd")}, ${regVal("rs1")}, $rmVal"
    // === Simple FP two-register (fmv.d.x, fmv.x.d, fclass, etc.) ===
    else if (hasRd && hasRs1 && !hasRs2 && !hasImm12 && !hasShamt && !hasCsr)
      s"$instName ${regVal("rd")}, ${regVal("rs1")}"
    // === FP R-type without rounding mode (fsgnj, fmin, fmax, etc.) ===
    else if (hasRd && hasRs1 && hasRs2 && !hasImm12 && !hasSplit && !hasCsr)
      s"$instName ${regVal("rd")}, ${regVal("rs1")}, ${regVal("rs2")}"
    else
      // Fallback: positional args in rvdecoderdb order
      s"$instName ${inst.args.map(a => argFmt(a.name)).mkString(", ")}"
  }

  /** Render a single [[Statement]] as a GAS assembly line. */
  private def statementToGas(
    stmt:          Statement,
    solvedOpcodes: Map[Int, Int],
    solvedArgs:    Map[String, BigInt],
    instructions:  Seq[org.chipsalliance.rvdecoderdb.Instruction],
    labelRefs:     Map[Int, String]
  ): String = stmt match
    case Statement.Inst(idx)                  =>
      val inst     = instructions(solvedOpcodes(idx))
      val baseLine = toGasLine(idx, inst, solvedArgs)
      labelRefs.get(idx) match
        case Some(target) =>
          replaceImmWithLabel(baseLine, inst, target)
        case None         =>
          s"    $baseLine"
    case Statement.Label(name)                =>
      s"$name:"
    case Statement.Section(name, flags*)      =>
      val flagStr =
        if flags.isEmpty then ""
        else
          val parts = flags.map: f =>
            if f.startsWith("@") || f.startsWith("\"") then f
            else s"\"$f\""
          s",${parts.mkString(",")}"
      s"    .section $name$flagStr"
    case Statement.Global(symbol)             =>
      s"    .globl $symbol"
    case Statement.Align(n)                   =>
      s"    .align $n"
    case Statement.Word(value)                =>
      s"    .word 0x${value.toHexString}"
    case Statement.Dword(value)               =>
      s"    .dword 0x${value.toHexString}"
    case Statement.Zero(size)                 =>
      s"    .zero $size"
    case Statement.Balign(alignment)          =>
      s"    .balign $alignment"
    case Statement.Space(size)                =>
      s"    .space $size"
    case Statement.Pseudo(mnemonic, operands) =>
      if operands.isEmpty then s"    $mnemonic" else s"    $mnemonic $operands"
    case Statement.Raw(content)               =>
      content
    case Statement.LabelRef(_, _)             =>
      ""

  /** Replace immediate offset with label in GAS assembly line. */
  private def replaceImmWithLabel(
    line:   String,
    inst:   org.chipsalliance.rvdecoderdb.Instruction,
    target: String
  ): String =
    val argNames = inst.args.map(_.name).toSet
    val hasBimm  = argNames.contains("bimm12hi") && argNames.contains("bimm12lo")
    val hasJimm  = argNames.contains("jimm20")
    if hasBimm || hasJimm then s"    ${line.replaceFirst("""\. \+ -?\d+""", target)}"
    else s"    $line"

  /** Build GAS assembly lines from the ordered statement list. */
  private def assembleStatementsGas(
    statements:    Seq[Statement],
    solvedOpcodes: Map[Int, Int],
    solvedArgs:    Map[String, BigInt]
  ): Seq[String] = {
    val instructions = getInstructions()
    val labelRefs    = statements.collect { case Statement.LabelRef(idx, target) => idx -> target }.toMap
    statements.map(s => statementToGas(s, solvedOpcodes, solvedArgs, instructions, labelRefs))
  }

  /** Legacy: Build GAS assembly lines with NOP-padding for explicit-index programs. */
  private def assembleInstructionsGas(
    solvedOpcodes: Map[Int, Int],
    solvedArgs:    Map[String, BigInt]
  ): Seq[String] = {
    val instructions  = getInstructions()
    val sortedIndices = solvedOpcodes.keys.toSeq.sorted
    val lines         = scala.collection.mutable.ListBuffer[String]()

    sortedIndices.zipWithIndex.foreach { case (i, pos) =>
      if (pos == 0) for (_ <- 0 until i) lines += "    nop"
      else
        val prevIdx = sortedIndices(pos - 1)
        for (_ <- 1 to (i - prevIdx - 1)) lines += "    nop"

      val gasLine = toGasLine(i, instructions(solvedOpcodes(i)), solvedArgs)
      lines += s"    $gasLine"
    }
    lines.toSeq
  }

  /** Solve the recipe and return it formatted as GAS assembly lines (newline-joined).
    *
    * If the program contains directives or labels, the full statement ordering is used. Otherwise falls back to legacy
    * NOP-padding for explicit-index programs.
    */
  def toRecipeAsm(): String = {
    val (opcodes, statements) = solveOpcodes()
    val args                  = solveArgs(opcodes)
    val hasDirectives         = statements.exists { case _: Statement.Inst => false; case _ => true }
    if hasDirectives then assembleStatementsGas(statements, opcodes, args).mkString("\n")
    else assembleInstructionsGas(opcodes, args).mkString("\n")
  }

  /** Unified output API.
    *
    * @param filename
    *   Target file path.
    * @param mode
    *   [[AsmMode]] writes a GAS `.s` file; [[BinMode]] writes raw binary.
    */
  def emit(filename: String = s"${name}_output", mode: OutputMode = AsmMode): Unit =
    mode match
      case AsmMode =>
        val content = toRecipeAsm()
        Files.write(
          Paths.get(filename),
          content.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING
        )
      case BinMode =>
        val outputs = toInstructions()
        val fos     = new FileOutputStream(filename)
        try outputs.foreach { case (bytes, _) => fos.write(bytes) }
        finally fos.close()
