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

trait RVGenerator:
  def constraints(): (Arena, Context, Block, Recipe) ?=> Unit // should be implemented by subclass
  val sets: Seq[Recipe ?=> SetConstraint] // should be implemented by subclass
  val name: String = this.getClass.getSimpleName

  // Internal MLIR context state
  private var _arena:   Arena   = uninitialized
  private var _context: Context = uninitialized
  private var _module:  Module  = uninitialized
  private var _block:   Block   = uninitialized
  private var _recipe:  Recipe  = uninitialized

  private def resolveSets(
    name:  String,
    sets:  Seq[Recipe ?=> SetConstraint]
  )(
    using Arena,
    Context,
    Block
  )(block: Recipe ?=> Unit
  ): Recipe = {
    given Recipe = new Recipe(name)

    // Store set constraints in Recipe
    sets.foreach { constraint =>
      summon[Recipe].addSet(r =>
        constraint(
          using r
        ).toRef
      )
    }

    block
    summon[Recipe]
  }

  def initialize(): Unit = {
    _arena = Arena.ofConfined()
    given Arena   = _arena
    _context = summon[ContextApi].contextCreate
    given Context = _context
    summon[SmtDialect].loadDialect()
    summon[FuncDialect].loadDialect()
    _module = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
    given Module  = _module

    val func    = summon[FuncApi].op("func")
    given Block = func.block
    func.appendToModule()

    solver {
      _block = summon[Block]
      smtSetLogic("QF_LIA")

      _recipe = resolveSets(name, sets) {
        constraints()
      }
    }
  }

  private def withContext[T](f: (Arena, Context, Block, Module, Recipe) ?=> T): T = {
    if (_context == null) initialize()
    given Arena   = _arena
    given Context = _context
    given Module  = _module
    given Block   = _block
    given Recipe  = _recipe
    f
  }

  // ================== Two-Stage Solving API ==================

  /** Stage 1: Generate SMTLIB to solve only Opcodes (nameId) */
  def printMLIROpcodes(): Unit = withContext {
    applyOpcodeConstraints()
    println(mlirToString())
  }

  def printSMTLIBOpcodes(): Unit = withContext {
    applyOpcodeConstraints()
    println(mlirToSMTLIB())
  }

  /** Stage 1 Helper: Parse Z3 output to get Opcode map */
  def solveOpcodes(): Map[Int, Int] = withContext {
    applyOpcodeConstraints()
    val smtlib   = mlirToSMTLIB()
    val z3Output = toZ3Output(smtlib)
    println(z3Output)
    val result   = parseZ3Output(z3Output)
    assert(result.status == Z3Status.Sat, s"Opcode solving failed: ${result.status}")

    result.model.collect {
      case (k, v: BigInt) if k.startsWith("nameId_") =>
        k.stripPrefix("nameId_").toInt -> v.toInt
    }
  }

  private def applyOpcodeConstraints(
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit = {
    val recipe       = summon[Recipe]
    // Apply Set constraints
    val includedSets = recipe.getSet().map(_(recipe))
    val excludedSets = recipe.allSets.diff(includedSets)
    smtAssert(smtAnd(includedSets*) & !smtOr(excludedSets*))

    recipe.allIndices().foreach { idx =>
      val index   = recipe.getIndex(idx)
      val opcodes = recipe.getOpcodes(idx).map(_(index))
      if (opcodes.nonEmpty) {
        smtAssert(smtAnd(opcodes*))
      }
    }

    smtCheck
  }

  /** Stage 2: Generate SMTLIB to solve Arguments, given fixed Opcodes */
  def printSMTLIBArgs(solvedOpcodes: Map[Int, Int]): Unit = withContext {
    applyArgConstraints(solvedOpcodes)
    println(mlirToSMTLIB())
  }

  /** Stage 2 Helper: Parse Z3 output to get all variables */
  def solveArgs(solvedOpcodes: Map[Int, Int]): Map[String, BigInt] = withContext {
    applyArgConstraints(solvedOpcodes)
    val smtlib   = mlirToSMTLIB()
    val z3Output = toZ3Output(smtlib)
    val result   = parseZ3Output(z3Output)
    result.model.collect { case (k, v: BigInt) => k -> v }
  }

  private def applyArgConstraints(
    solvedOpcodes: Map[Int, Int]
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit = {
    val recipe = summon[Recipe]

    val instructions = getInstructions()

    recipe.allIndices().foreach { idx =>
      val index    = recipe.getIndex(idx)
      val opcodeId = solvedOpcodes.getOrElse(idx, throw new RuntimeException(s"No solved opcode for index $idx"))

      // 1. Auto-constrain Args
      val inst = instructions(opcodeId)
      inst.args.foreach { arg =>
        applyArgRangeConstraint(index, arg)
      }

      // 2. Apply User Arg Constraints
      val args = recipe.getArgs(idx).map(_(index))
      if (args.nonEmpty) {
        smtAssert(smtAnd(args*))
      }
    }

    smtCheck
  }

  private def applyArgRangeConstraint(
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
      val width      = arg.lsb - arg.msb + 1
      val isSigned   =
        arg.name.toLowerCase.contains("imm") || arg.name.toLowerCase.contains("offset") || arg.name == "bimm12hi"
      val (min, max) = if isSigned then
        val limit = BigInt(1) << (width - 1)
        (-limit, limit)
      else (BigInt(0), BigInt(1) << width)
      smtAssert(field >= min.toInt.S & field < max.toInt.S)
      // println(s"Applied range constraint on $fieldNameLowered: [$min, $max), width: $width, signed: $isSigned")
    catch
      case _: NoSuchMethodException => // Ignore if field not in Index
  }

  def mlirToString(): String =
    given Arena   = _arena
    given Context = _context
    given Module  = _module
    val out       = new StringBuilder
    _module.getOperation.print(out ++= _)
    out.toString()

  def mlirToSMTLIB(): String =
    given Arena   = _arena
    given Context = _context
    given Module  = _module
    val out       = new StringBuilder
    _module.exportSMTLIB(out ++= _)
    out.toString()

  def close(): Unit =
    if _context != null then
      _context.destroy()
      _context = null
    if _arena != null then
      _arena.close()
      _arena = null
    _module = null

  def toZ3Output(smtlib: String): String =
    val z3Output =
      os.proc("z3", "-in", "-t:5000").call(stdin = smtlib.toString().replace("(reset)", "(get-model)"), check = false)
    z3Output.out.text()

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

  // ================== Convenience Methods ==================

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

  // Legacy support for tests
  def toSMTLIB(): String = withContext {
    applyOpcodeConstraints()
    mlirToSMTLIB()
  }

  def toMLIR(): String = withContext {
    applyOpcodeConstraints()
    mlirToString()
  }

  def toZ3Output(): String = {
    val smtlib = toSMTLIB()
    toZ3Output(smtlib)
  }
