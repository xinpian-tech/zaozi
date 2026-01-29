// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.smtlib.parser

import fastparse.*
import NoWhitespace.*

def parseSExpr(input: String): Parsed[Seq[SExpr]] =
  parse(
    input,
    SExprParser.file(
      using _
    )
  )

// format: off
def convert(sexpr: SExpr): SMTCommand =
  import SExpr.*
  sexpr match
    case Symbol("check-sat")                                                                     => SMTCommand.Check
    case Symbol("reset")                                                                         => SMTCommand.Reset
    case Symbol("push")                                                                          => SMTCommand.Push
    case Symbol("pop")                                                                           => SMTCommand.Pop
    case Symbol("yield")                                                                         => SMTCommand.Yield
    case List(Symbol("assert") :: arg :: Nil)                                                    =>
      SMTCommand.Assert(convert(arg))
    case List(x :: Nil)                                                                          => convert(x)
    case List(Symbol("set-logic") :: Symbol(logic) :: Nil)                                       =>
      SMTCommand.SetLogic(logic)
    case List(Symbol("declare-fun") :: Symbol(name) :: List(args) :: retType :: Nil) =>
      val parsedArgs = args.collect {
        case List(Symbol(argName) :: Symbol(argType) :: Nil) => (argName, argType)
      }
      val parsedRetType = retType match {
        case Symbol(simpleType) => simpleType // Simple type like BigInt or Bool
        case List(complexType) => complexType.toString() // Complex type like (_ BitVec 32)
        case _ => throw new IllegalArgumentException(s"Unsupported return type: $retType")
      }
      SMTCommand.DeclareFun(name, parsedArgs, parsedRetType)
    case List(Symbol("define-fun") :: Symbol(name) :: List(args) :: retType :: retValue :: Nil) =>
      val parsedArgs = args.collect {
        case List(Symbol(argName) :: Symbol(argType) :: Nil) => (argName, argType)
      }
      val parsedRetType = retType match {
        case Symbol(simpleType) => simpleType // Simple type like Int or Bool
        case List(complexType) => complexType.toString() // Complex type like (_ BitVec 32)
        case _ => throw new IllegalArgumentException(s"Unsupported return type: $retType")
      }
      SMTCommand.DefineFun(name, parsedArgs, parsedRetType, convert(retValue))
    case List(Symbol("and") :: args)                                                             =>
      SMTCommand.And(args.map(convert))
    case List(Symbol("or") :: args)                                                              =>
      SMTCommand.Or(args.map(convert))
    case List(Symbol("not") :: arg :: Nil)                                                       =>
      SMTCommand.Not(convert(arg))
    case List(Symbol("ite") :: cond :: tBranch :: fBranch :: Nil)                                =>
      SMTCommand.Ite(convert(cond), convert(tBranch), convert(fBranch))
    case List(Symbol("eq") :: lhs :: rhs :: Nil)                                                 =>
      SMTCommand.Eq(convert(lhs), convert(rhs))
    case List(Symbol("distinct") :: args)                                                        =>
      SMTCommand.Distinct(args.map(convert))
    case List(Symbol("implies") :: lhs :: rhs :: Nil)                                            =>
      SMTCommand.Implies(convert(lhs), convert(rhs))
    case List(Symbol(">") :: lhs :: rhs :: Nil)                                                  =>
      SMTCommand.IntCmp(">", convert(lhs), convert(rhs))
    case List(Symbol("<") :: lhs :: rhs :: Nil)                                                  =>
      SMTCommand.IntCmp("<", convert(lhs), convert(rhs))
    case List(Symbol(">=") :: lhs :: rhs :: Nil)                                                 =>
      SMTCommand.IntCmp(">=", convert(lhs), convert(rhs))
    case List(Symbol("<=") :: lhs :: rhs :: Nil)                                                 =>
      SMTCommand.IntCmp("<=", convert(lhs), convert(rhs))
    case List(Symbol("-") :: Number(n) :: Nil)                                                   => SMTCommand.IntConstant(BigInt(-n.toInt))
    case Symbol("true")                                                                          => SMTCommand.BoolConstant(true)
    case Symbol("false")                                                                         => SMTCommand.BoolConstant(false)
    case Symbol(x)                                                                               => SMTCommand.Variable(x)
    case Number(n) if n.contains('.')                                                            =>
      throw new UnsupportedOperationException(s"Real numbers not supported yet: $n")
    case Number(n)                                                                               => SMTCommand.IntConstant(BigInt(n))
    case BitVector(n) if n.startsWith("#x") =>
      // Parse hexadecimal bitvector (e.g., #x00000000)
      // val bitWidth = (n.length - 2) * 4 // Each hex digit represents 4 bits
      SMTCommand.IntConstant(BigInt(n.drop(2), 16))
    case BitVector(n) if n.startsWith("#b") =>
      // Parse binary bitvector (e.g., #b1010)
      // val bitWidth = n.length - 2 // Exclude "#b"
      SMTCommand.IntConstant(BigInt(n.drop(2), 2))
    case List(Symbol("apply-func") :: Symbol(name) :: args)                                      =>
      SMTCommand.ApplyFunc(name, args.map(convert))
    case List(Symbol("extract") :: Symbol(high) :: Symbol(low) :: arg :: Nil)                    =>
      SMTCommand.Extract(high.toInt, low.toInt, convert(arg))
    case other                                                                                   => throw new Exception(s"Unknown or unimplemented expression: $other")
// format: on

def parseSMT(input: String): Seq[SMTCommand] =
  parseSExpr(input).get.value.map(convert)

enum Z3Status:
  case Sat, Unsat, Unknown

// This case class represents the result of a Z3 solver invocation.
// status indicates whether the problem was satisfiable, unsatisfiable, or unknown.
// model contains the variable assignments if the status is Sat, Or empty if Unsat or Unknown.
final case class Z3Result(status: Z3Status, model: Map[String, Boolean | BigInt])

// This function parses the output from Z3 and returns a Z3Result.
def parseZ3Output(input: String): Z3Result =
  val statusLine = input.split("\n").head.trim
  val status     = statusLine match
    case "sat"     => Z3Status.Sat
    case "unsat"   => Z3Status.Unsat
    case "unknown" => Z3Status.Unknown
    case _         => throw new Exception(s"Unexpected result: $statusLine")

  val model: Map[String, Boolean | BigInt] =
    if status == Z3Status.Sat then
      val modelRaw     = input.split("\n").drop(1).mkString("\n")
      val modelTrimmed = modelRaw.trim.stripPrefix("(").stripSuffix(")")
      parseSMT(modelTrimmed).map {
        case SMTCommand.DefineFun(name, _, _, SMTCommand.BoolConstant(value)) => (name, value)
        case SMTCommand.DefineFun(name, _, _, SMTCommand.IntConstant(value))  => (name, value)
        case other                                                            => throw new Exception(s"Unexpected command in result: $other")
      }.toMap
    else Map.empty

  Z3Result(status, model)

/** Get unsat core from Z3 for debugging constraint conflicts
  *
  * @param smtlib The SMTLIB input to analyze
  * @param z3Runner Function to run Z3 with given SMTLIB input and return output
  * @return String containing the unsat core output from Z3
  */
def getUnsatCore(smtlib: String, z3Runner: String => String): String =
  // Add option to produce unsat cores right after set-logic
  // This avoids issues with redeclaration of constants
  val smtlibWithCore = smtlib.replaceFirst(
    "\\(set-logic [^)]+\\)",
    "$0\n(set-option :produce-unsat-cores true)"
  ).replace("(check-sat)", "(check-sat)\n(get-unsat-core)")

  z3Runner(smtlibWithCore)

/** Parse Z3 output and automatically handle UNSAT/Unknown results
  *
  * This is a convenience function that combines parseZ3Output with automatic UNSAT handling.
  * If the result is SAT, returns the Z3Result. If UNSAT/Unknown, prints debugging info and throws.
  *
  * @param z3Output The output from Z3
  * @param smtlib The original SMTLIB input (for unsat core generation)
  * @param z3Runner Function to run Z3 with given SMTLIB input
  * @param context Context description for error messages (e.g., "Opcode solving", "Argument solving")
  * @return Z3Result if SAT
  * @throws RuntimeException if UNSAT/Unknown with detailed debugging information
  */
def parseZ3OutputOrFail(
  z3Output: String,
  smtlib: String,
  z3Runner: String => String,
  context: String
): Z3Result =
  val result = parseZ3Output(z3Output)

  if result.status != Z3Status.Sat then
    System.err.println(s"=== $context failed: ${result.status} ===")
    System.err.println(s"\nSMTLIB:\n$smtlib")
    System.err.println(s"\nZ3 Output:\n$z3Output")

    // Get and print unsat core for debugging
    try
      val unsatCore = getUnsatCore(smtlib, z3Runner)
      System.err.println(s"\nUnsat Core:\n$unsatCore")
    catch
      case e: Exception =>
        System.err.println(s"\nFailed to get unsat core: ${e.getMessage}")

    throw new RuntimeException(s"$context failed with status: ${result.status}")

  result

/** Handle UNSAT/Unknown results by printing debugging information and throwing exception
  *
  * This method should be called when Z3 result is not SAT to provide detailed debugging info.
  *
  * @param result The Z3Result that is not SAT
  * @param smtlib The original SMTLIB input
  * @param z3Output The Z3 output
  * @param z3Runner Function to run Z3 (used for getting unsat core)
  * @param context Context description for error messages (e.g., "Opcode solving", "Argument solving")
  * @throws RuntimeException Always throws with detailed error information
  */
def handleUnsatResult(
  result: Z3Result,
  smtlib: String,
  z3Output: String,
  z3Runner: String => String,
  context: String
): Nothing =
  System.err.println(s"=== $context failed: ${result.status} ===")
  System.err.println(s"\nSMTLIB:\n$smtlib")
  System.err.println(s"\nZ3 Output:\n$z3Output")

  // Get and print unsat core for debugging
  try
    val unsatCore = getUnsatCore(smtlib, z3Runner)
    System.err.println(s"\nUnsat Core:\n$unsatCore")
  catch
    case e: Exception =>
      System.err.println(s"\nFailed to get unsat core: ${e.getMessage}")

  throw new RuntimeException(s"$context failed with status: ${result.status}")

enum SExpr:
  case Symbol(value: String)
  case StringLit(value: String)
  case Number(value: String)
  case BitVector(value: String)
  case List(values: Seq[SExpr])

private object SExprParser:
  def ws(
    using p: P[?]
  ): P[Unit] =
    P((CharsWhileIn(" \r\n\t") | comment).rep)

  def comment(
    using p: P[?]
  ): P[Unit] =
    P(";" ~ CharsWhile(_ != '\n').rep ~ ("\n" | End))

  inline val symbolInitial    = "a-zA-Z~!@$%^&*_" + "\\-" + "+=<>.?/"
  inline val symbolSubsequent = symbolInitial + "0-9:"

  def initialChar(
    using p: P[?]
  ): P[Unit] = P(CharIn(symbolInitial))
  def subsequentChar(
    using p: P[?]
  ): P[Unit] = P(CharIn(symbolSubsequent))

  def symbol(
    using p: P[?]
  ): P[String] =
    P(
      "|" ~/ CharsWhile(_ != '|').! ~ "|" |
        (initialChar ~ subsequentChar.rep).!
    )

  def quotedString(
    using p: P[?]
  ): P[String] =
    P("\"" ~/ CharsWhile(_ != '"').! ~ "\"")

  def integer(
    using p: P[?]
  ): P[String] =
    P("-".? ~ CharIn("0-9").rep(1).!)

  def decimal(
    using p: P[?]
  ): P[String] =
    P("-".? ~ CharIn("0-9").rep(1) ~ "." ~ CharIn("0-9").rep(1)).!

  def number(
    using p: P[?]
  ): P[String] =
    P(decimal | integer)

  def bitVector(
    using p: P[?]
  ): P[String] =
    P("#x" ~ (CharIn("0-9") | CharIn("a-f")).rep(1) | "#b" ~ CharIn("0-1").rep(1)).!

  def sexpr(
    using p: P[?]
  ): P[SExpr] =
    P(
      ws ~ (
        symbol.map(SExpr.Symbol(_)) |
          quotedString.map(SExpr.StringLit(_)) |
          number.map(SExpr.Number(_)) |
          bitVector.map(SExpr.BitVector(_)) |
          list
      ) ~ ws
    )

  def list(
    using p: P[?]
  ): P[SExpr.List] =
    P("(" ~/ sexpr.rep ~ ")").map(values => SExpr.List(values))

  def file(
    using p: P[?]
  ): P[Seq[SExpr]] =
    P(ws ~ sexpr.rep ~ ws ~ End)

enum SMTCommand:
  case And(args: Seq[SMTCommand])
  case ApplyFunc(name: String, args: Seq[SMTCommand])
  case ArrayBroadcast(arg: SMTCommand)
  case ArraySelect(array: SMTCommand, index: SMTCommand)
  case ArrayStore(array: SMTCommand, index: SMTCommand, value: SMTCommand)
  case Assert(arg: SMTCommand)
  case BVAShr(lhs: SMTCommand, rhs: SMTCommand)
  case BVAnd(lhs: SMTCommand, rhs: SMTCommand)
  case BVCmp(op: String, lhs: SMTCommand, rhs: SMTCommand)
  case BVConstant(value: String)
  case BVLShr(lhs: SMTCommand, rhs: SMTCommand)
  case BVMul(lhs: SMTCommand, rhs: SMTCommand)
  case BVNeg(arg: SMTCommand)
  case BVNot(arg: SMTCommand)
  case BVOr(lhs: SMTCommand, rhs: SMTCommand)
  case BVShl(lhs: SMTCommand, rhs: SMTCommand)
  case BVXOr(lhs: SMTCommand, rhs: SMTCommand)
  case BoolConstant(value: Boolean)
  case Check
  case Concat(args: Seq[SMTCommand])
  case DeclareFun(name: String, args: Seq[(String, String)], retType: String)
  case DefineFun(name: String, args: Seq[(String, String)], retType: String, retValue: SMTCommand)
  case Distinct(args: Seq[SMTCommand])
  case Eq(lhs: SMTCommand, rhs: SMTCommand)
  case Extract(high: Int, low: Int, arg: SMTCommand)
  case Implies(lhs: SMTCommand, rhs: SMTCommand)
  case IntAdd(lhs: SMTCommand, rhs: SMTCommand)
  case IntCmp(op: String, lhs: SMTCommand, rhs: SMTCommand)
  case IntConstant(value: BigInt)
  case IntDiv(lhs: SMTCommand, rhs: SMTCommand)
  case IntMod(lhs: SMTCommand, rhs: SMTCommand)
  case IntMul(lhs: SMTCommand, rhs: SMTCommand)
  case IntSub(lhs: SMTCommand, rhs: SMTCommand)
  case Ite(cond: SMTCommand, tBranch: SMTCommand, fBranch: SMTCommand)
  case Not(arg: SMTCommand)
  case Or(args: Seq[SMTCommand])
  case Pop
  case Push
  case Repeat(times: Int, arg: SMTCommand)
  case Reset
  case SetLogic(logic: String)
  case Solver(name: String)
  case XOr(lhs: SMTCommand, rhs: SMTCommand)
  case Yield
  case Variable(name: String)
