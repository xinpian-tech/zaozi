// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe

import me.jiuyang.smtlib.tpe.{Bool, Ref}
import me.jiuyang.smtlib.parser.{parseZ3Output, Z3Result, Z3Status}
import me.jiuyang.smtlib.default.{smtAnd, smtAssert, smtCheck, smtOr, smtSetLogic, solver}

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

  type WithMLIRContext[T] = (Arena, Context, Block, Module) ?=> T

  val name: String = this.getClass.getSimpleName

  // Internal MLIR context state (initialized by createMLIRModule)
  private var _arena:   Arena   = uninitialized
  private var _context: Context = uninitialized
  private var _module:  Module  = uninitialized

  private def resolveSets(
    name:  String,
    sets:  Seq[Recipe ?=> SetConstraint]
  )(
    using Arena,
    Context,
    Block
  )(block: Recipe ?=> Unit
  ): Unit = {
    given Recipe = new Recipe(name)

    // assert sets are valid
    val includedSets: List[Ref[Bool]] = sets.toList.map(
      _(
        using summon
      ).toRef
    )
    val excludedSets = summon[Recipe].allSets.diff(includedSets)
    smtAssert(smtAnd(includedSets*))
    smtAssert(!smtOr(excludedSets*))

    block
  }

  def createMLIRModule(): Unit =
    _arena = Arena.ofConfined()
    given Arena   = _arena
    _context = summon[ContextApi].contextCreate
    given Context = _context
    summon[SmtDialect].loadDialect()
    _module = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
    given Module  = _module
    given Func    = summon[FuncApi].op("func")
    given Block   = summon[Func].block
    summon[Func].appendToModule()

    try
      solver {
        smtSetLogic("QF_LIA")
        resolveSets(name, sets) {
          constraints()
        }
        smtCheck
      }
    catch
      case e: Throwable =>
        close()
        throw e

  private def InMLIRContext[T](action: WithMLIRContext[T]): T =
    given Arena   = Arena.ofConfined()
    given Context = summon[ContextApi].contextCreate
    summon[SmtDialect].loadDialect()
    summon[FuncDialect].loadDialect()
    given Module  = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
    given Func    = summon[FuncApi].op("func")
    given Block   = summon[Func].block
    summon[Func].appendToModule()

    try
      solver {
        smtSetLogic("QF_LIA")
        resolveSets(name, sets) {
          constraints()
        }
        smtCheck
      }
      action
    finally
      summon[Context].destroy()
      summon[Arena].close()

  private def getMLIRString: WithMLIRContext[String] =
    val out = new StringBuilder
    summon[Module].getOperation.print(out ++= _)
    out.toString()

  private def getSMTLIBString: WithMLIRContext[String] =
    val out = new StringBuilder
    summon[Module].exportSMTLIB(out ++= _)
    out.toString()

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

  // Legacy methods for backward compatibility
  def toMLIR():    String = InMLIRContext { getMLIRString }
  def printMLIR(): Unit   = InMLIRContext { println(getMLIRString) }

  def toSMTLIB():    String = InMLIRContext { getSMTLIBString }
  def printSMTLIB(): Unit   = InMLIRContext { println(getSMTLIBString) }

  def toZ3Output(smtlib: String): String =
    val z3Output =
      os.proc("z3", "-in", "-t:5000").call(stdin = smtlib.toString().replace("(reset)", "(get-model)"), check = false)
    z3Output.out.text()
  def toZ3Output():               String =
    toZ3Output(toSMTLIB())
  def printZ3Output():            Unit   = println(toZ3Output())

  def toInstructions(z3Output: String):                                  Seq[(scala.Array[Byte], String)] =
    val z3Result: Z3Result = parseZ3Output(z3Output)

    assert(z3Result.status == Z3Status.Sat, s"Z3 result is not SAT: ${z3Result.status}")
    val model        = z3Result.model
    val instructions = getInstructions()

    // TODO: pass instruction count from outside? Get Recipe given here?
    val instructionCount = toSMTLIB().split('\n').count(_.startsWith("(declare-const nameId"))

    (0 until instructionCount).map { case i =>
      val nameId = getModelField(model, s"nameId_$i")
      val inst   = instructions(nameId)

      val (args, bits) = inst.args.foldLeft((Vector.empty[String], inst.encoding.value)) {
        case ((argsAcc, bitsAcc), arg) =>
          val argName        = translateToCamelCase(arg.name)
          val argNameLowered = argName.head.toLower + argName.tail
          val prefix         = if arg.name.startsWith("r") then "x" else ""
          val argValue       = getModelField(model, argNameLowered + s"_$i")

          val processedValue: Long = if argValue < 0 then
            val fieldWidth = arg.lsb - arg.msb + 1
            val mask       = (1L << fieldWidth) - 1
            argValue.toLong & mask
          else argValue.toLong

          (
            argsAcc :+ (prefix + argValue.toString),
            bitsAcc | (BigInt(processedValue) << arg.msb)
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
    }.toSeq
  def toInstructions():                                                  Seq[(scala.Array[Byte], String)] =
    toInstructions(toZ3Output())
  def printInstructions():                                               Unit                             =
    val outputs = toInstructions()
    outputs.foreach { case (_, instrStr) => println(instrStr) }
  def writeInstructionsToFile(filename: String = s"${name}_output.bin"): Unit                             =
    val outputs = toInstructions()
    val fos     = new FileOutputStream(filename, true)
    try
      outputs.foreach { case (bytes, _) => fos.write(bytes) }
    finally fos.close()

  private def getModelField(model: Map[String, Boolean | BigInt], name: String): Int =
    model
      .get(name)
      .map {
        case b: Boolean => throw new RuntimeException(s"Expected an integer for $name, but got a boolean: $b")
        case i: BigInt  => i.toInt
      }
      .getOrElse(throw new RuntimeException(s"Model does not contain field: $name"))
