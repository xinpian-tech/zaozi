// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe

import me.jiuyang.smtlib.tpe.{Bool, Ref}
import me.jiuyang.smtlib.parser.{parseZ3Output, Z3Result, Z3Status}
import me.jiuyang.smtlib.default.{*, given}

import me.jiuyang.rvprobe.constraints.*

import org.llvm.mlir.scalalib.capi.dialect.func.{DialectApi as FuncDialect, Func, FuncApi, given}
import org.llvm.mlir.scalalib.capi.dialect.smt.{given_DialectApi, DialectApi as SmtDialect}
import org.llvm.mlir.scalalib.capi.target.exportsmtlib.given_ExportSmtlibApi
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, LocationApi, Module, ModuleApi, Value, given}

import java.lang.foreign.Arena
import java.io.FileOutputStream

import scala.compiletime.uninitialized

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

  /** Stage 1: Generate SMTLIB to solve only Opcodes (nameId) */
  def printSMTLIBOpcodes(): Unit = withOpcodeContext(
    postProcess = (arena, context, module, recipe) => {
      given Arena  = arena
      given Module = module
      println(mlirToSMTLIB())
    }
  )

  /** Stage 1 Helper: Parse Z3 output to get Opcode map */
  def solveOpcodes(): Map[Int, Int] = withOpcodeContext(
    postProcess = (arena, context, module, recipe) => {
      given Arena  = arena
      given Module = module
      val smtlib   = mlirToSMTLIB()
      val z3Output = toZ3Output(smtlib)
      try {
        val result = parseZ3Output(z3Output)
        assert(result.status == Z3Status.Sat, s"Opcode solving failed: ${result.status}")
        result.model.collect {
          case (k, v: BigInt) if k.startsWith("nameId_") =>
            k.stripPrefix("nameId_").toInt -> v.toInt
        }
      } catch {
        case e: Exception =>
          System.err.println(s"SMTLIB:\n$smtlib")
          System.err.println(s"Z3 Parsing/Solving failed. Output:\n$z3Output")
          throw e
      }
    }
  )

  /** Stage 2: Generate SMTLIB to solve Arguments, given fixed Opcodes */
  def printSMTLIBArgs(solvedOpcodes: Map[Int, Int]): Unit = withArgContext(
    solvedOpcodes = solvedOpcodes,
    postProcess = (arena, context, module, recipe) => {
      given Arena  = arena
      given Module = module
      println(mlirToSMTLIB())
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
      val result   = parseZ3Output(z3Output)
      assert(result.status == Z3Status.Sat, s"Argument solving failed: ${result.status}")
      result.model.collect { case (k, v: BigInt) => k -> v }
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

    // 2. Apply User Opcode Constraints (isAddw, etc.)
    recipe.allIndices().foreach { idx =>
      val index   = recipe.getIndex(idx)
      val opcodes = recipe.getOpcodes(idx).map(_(index))
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

      // 1. Fix Opcode (this is a given, not a constraint to solve)
      smtAssert(index.nameId === opcodeId.S)

      // 2. Auto-constrain Args based on instruction definition
      val inst = instructions(opcodeId)
      inst.args.foreach { arg =>
        emitArgRangeConstraint(index, arg)
      }

      // 3. Apply User Arg Constraints (rdRange, rs1Range, etc.)
      val args = recipe.getArgs(idx).map(_(index))
      if (args.nonEmpty) {
        smtAssert(smtAnd(args*))
      }
    }
    smtCheck
  }

  /** Helper: Emit range constraint for a single arg based on its bit width */
  private def emitArgRangeConstraint(
    index: Index,
    arg:   org.chipsalliance.rvdecoderdb.Arg
  )(
    using Arena,
    Context,
    Block
  ): Unit = {
    val fieldName        = translateToCamelCase(arg.name)
    val fieldNameLowered = fieldName.head.toLower + fieldName.tail
    try
      val method     = index.getClass.getMethod(fieldNameLowered)
      val field      = method.invoke(index).asInstanceOf[Ref[me.jiuyang.smtlib.tpe.SInt]]
      // Note: In rvdecoderdb, msb/lsb might be swapped (msb < lsb), use absolute value
      val width      = Math.abs(arg.msb - arg.lsb) + 1
      val isSigned   =
        arg.name.toLowerCase.contains("imm") || arg.name.toLowerCase.contains("offset") || arg.name == "bimm12hi"
      val (min, max) = if isSigned then
        val limit = BigInt(1) << (width - 1)
        (-limit, limit)
      else (BigInt(0), BigInt(1) << width)
      smtAssert(field >= min.toInt.S)
      smtAssert(field < max.toInt.S)
    catch
      case _: NoSuchMethodException => // Ignore if field not in Index
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

  private def toZ3Output(smtlib: String): String = {
    val z3Output =
      os.proc("z3", "-in", "-t:5000").call(stdin = smtlib.toString().replace("(reset)", "(get-model)"), check = false)
    z3Output.out.text()
  }

  // ================== Assembly & Legacy ==================

  def assembleInstructions(
    solvedOpcodes: Map[Int, Int],
    solvedArgs:    Map[String, BigInt]
  ): Seq[(Array[Byte], String)] = {
    val instructions  = getInstructions()
    val sortedIndices = solvedOpcodes.keys.toSeq.sorted

    sortedIndices.map { i =>
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

      val value: Long = bits.toLong & 0xffffffffL
      val bytes = scala.Array[Byte](
        (value & 0xff).toByte,
        ((value >> 8) & 0xff).toByte,
        ((value >> 16) & 0xff).toByte,
        ((value >> 24) & 0xff).toByte
      )

      (bytes, instrString)
    }
  }

  def toInstructions(): Seq[(Array[Byte], String)] = {
    val opcodes = solveOpcodes()
    val args    = solveArgs(opcodes)
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

  // For tests that expect SMTLIB output of opcodes stage
  def toSMTLIB(): String = withOpcodeContext(
    postProcess = (arena, context, module, recipe) => {
      given Arena  = arena
      given Module = module
      mlirToSMTLIB()
    }
  )

  def toMLIR(): String = withOpcodeContext(
    postProcess = (arena, context, module, recipe) => {
      given Arena  = arena
      given Module = module
      val out      = new StringBuilder
      summon[Module].getOperation.print(out ++= _)
      out.toString()
    }
  )

  def toZ3Output(): String = {
    val smtlib = toSMTLIB()
    toZ3Output(smtlib)
  }
