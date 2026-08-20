// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}

import org.llvm.circt.scalalib.capi.dialect.firrtl.{given_DialectApi, DialectApi as FirrtlDialectApi}
import org.llvm.circt.scalalib.capi.dialect.ltl.{given_DialectApi as given_LTLDialectApi, DialectApi as LTLDialectApi}
import org.llvm.circt.scalalib.capi.dialect.verif.{
  given_DialectApi as given_VerifDialectApi,
  DialectApi as VerifDialectApi
}
import org.llvm.mlir.scalalib.capi.ir.{Context, ContextApi, given}

import java.lang.foreign.Arena

/** A per-cycle trace: for each traced signal (DUT inputs + register state), its value at each cycle. Serves as both a
  * `check` counterexample and a `generate` transaction.
  */
final case class Trace(cycles: Int, values: Map[String, Vector[BigInt]])

/** Outcome of proving a UT's assertions. */
enum CheckOutcome:
  /** No assertion can be violated within the bound. */
  case Pass

  /** An assertion can be violated; `counterexample` is the offending trace. */
  case Fail(counterexample: Trace)

  /** circt-bmc could not decide, or its output could not be interpreted. */
  case Unknown(detail: String)

/** Outcome of generating a transaction that satisfies a UT's constraint. */
enum GenerateOutcome:
  /** The constraint is satisfiable within the bound; `transaction` is a witness. */
  case Generated(transaction: Trace)

  /** No trace within the bound satisfies the constraint. */
  case Infeasible

  /** circt-bmc could not decide, or its output could not be interpreted. */
  case Unknown(detail: String)

/** The formal flavor of the CIRCT-native UT framework, on **circt-bmc** (no hand-written SMT).
  *
  * A UT module carries its verification intent as SVA — `verif.assert` / `verif.assume`, which zaozi's
  * `Assert`/`Assume` emit as the `circt.verif.*` / `circt.ltl.*` FIRRTL intrinsics. `firtool -ir-hw` lowers those to an
  * `hw.module`, and circt-bmc solves the bounded clocked problem: if an assertion can be violated it prints a clean
  * per-cycle counterexample trace, otherwise it holds up to the bound.
  *
  * `check` and `generate` are the two readings of one circt-bmc run — the dual of each other:
  *   - `check(P)`: assert `P`; a violation is a bug ⇒ [[CheckOutcome.Fail]] (the trace), no violation ⇒ Pass.
  *   - `generate(C)`: assert `¬C`; a violation is a witness where `C` holds ⇒ [[GenerateOutcome.Generated]] (the trace
  *     is the transaction), no violation ⇒ Infeasible.
  *
  * So `generate(C)` = `check(¬C)` with the outcome relabelled: the counterexample *is* the transaction.
  */
object FormalUT:

  /** Prove the assertions in an HW module hold over `bound` cycles. */
  def check(hwModule: os.Path, top: String, bound: Int): CheckOutcome =
    run(hwModule, top, bound) match
      case Bmc.Violated(t) => CheckOutcome.Fail(t)
      case Bmc.Held        => CheckOutcome.Pass
      case Bmc.Unknown(d)  => CheckOutcome.Unknown(d)

  /** Generate a transaction from an HW module whose assertion is `¬constraint`. */
  def generate(hwModule: os.Path, top: String, bound: Int): GenerateOutcome =
    run(hwModule, top, bound) match
      case Bmc.Violated(t) => GenerateOutcome.Generated(t)
      case Bmc.Held        => GenerateOutcome.Infeasible
      case Bmc.Unknown(d)  => GenerateOutcome.Unknown(d)

  /** Prove a zaozi UT generator's assertions (elaborate → HW → circt-bmc). */
  def checkGenerator[
    PARAM <: Parameter,
    L <: LayerInterface[PARAM],
    I <: HWInterface[PARAM],
    P <: DVInterface[PARAM, L]
  ](dut:       Generator[PARAM, L, I, P],
    parameter: PARAM,
    bound:     Int,
    outDir:    os.Path
  ): CheckOutcome =
    val (hw, top) = elaborateToHw(dut, parameter, outDir)
    check(hw, top, bound)

  /** Generate a transaction directly from a zaozi UT generator whose SVA asserts `¬constraint`. */
  def generateGenerator[
    PARAM <: Parameter,
    L <: LayerInterface[PARAM],
    I <: HWInterface[PARAM],
    P <: DVInterface[PARAM, L]
  ](dut:       Generator[PARAM, L, I, P],
    parameter: PARAM,
    bound:     Int,
    outDir:    os.Path
  ): GenerateOutcome =
    val (hw, top) = elaborateToHw(dut, parameter, outDir)
    generate(hw, top, bound)

  // ---- the circt-bmc engine ----------------------------------------------------------------------------------------

  private[utlib] enum Bmc:
    case Violated(trace: Trace)
    case Held
    case Unknown(detail: String)

  /** One circt-bmc run on `hwModule`, parsed into a [[Bmc]] verdict. */
  private def run(hwModule: os.Path, top: String, bound: Int): Bmc =
    val out = os
      .proc(
        circtTool("circt-bmc"),
        hwModule.toString,
        "-b",
        bound.toString,
        "--module",
        top,
        "--print-only-first-counterexample",
        s"--shared-libs=${defaultLibz3()}"
      )
      .call(check = false, mergeErrIntoOut = true)
      .out
      .text()
    parseTrace(out)

  /** Parse circt-bmc's counterexample trace. The output holds one or more blocks:
    * {{{
    * counterexample for <module>:
    * cycle 0:
    *   <signal> = 0x<hex>
    * ...
    * }}}
    * We take the deepest block. "Bound reached with no violations!" ⇒ [[Bmc.Held]].
    */
  private[utlib] def parseTrace(output: String): Bmc =
    val blocks   = scala.collection.mutable.ArrayBuffer.empty[Vector[Map[String, BigInt]]]
    var curBlock = Option.empty[scala.collection.mutable.ArrayBuffer[Map[String, BigInt]]]
    var curCycle = Option.empty[scala.collection.mutable.LinkedHashMap[String, BigInt]]

    def flushCycle(): Unit =
      for b <- curBlock; c <- curCycle do b += c.toMap
      curCycle = None
    def flushBlock(): Unit =
      flushCycle()
      curBlock.foreach(b => blocks += b.toVector)
      curBlock = None

    for raw <- output.linesIterator do
      val l = raw.trim
      if l.startsWith("counterexample for") then
        flushBlock()
        curBlock = Some(scala.collection.mutable.ArrayBuffer.empty)
      else if l.startsWith("cycle") && l.endsWith(":") then
        flushCycle()
        curCycle = Some(scala.collection.mutable.LinkedHashMap.empty)
      else
        curCycle.foreach { c =>
          l match
            case s"$name = $value" => parseValue(value).foreach(v => c(name.trim) = v)
            case _                 => ()
        }
    flushBlock()

    if blocks.nonEmpty then
      val deepest = blocks.maxBy(_.size)
      val signals = deepest.flatMap(_.keys).distinct
      Bmc.Violated(Trace(deepest.size, signals.map(s => s -> deepest.map(_.getOrElse(s, BigInt(0)))).toMap))
    else if output.contains("Bound reached with no violations") then Bmc.Held
    else Bmc.Unknown(output.linesIterator.filter(_.trim.nonEmpty).toSeq.lastOption.getOrElse("").trim)

  /** Parse a value as circt-bmc's trace (`0x..`) or a Z3 model (`#x..`/`#b..`) prints it. */
  private def parseValue(token: String): Option[BigInt] =
    val t = token.trim
    if t.startsWith("0x") || t.startsWith("0X") || t.startsWith("#x") then
      scala.util.Try(BigInt(t.drop(2), 16)).toOption
    else if t.startsWith("#b") then scala.util.Try(BigInt(t.drop(2), 2)).toOption
    else if t == "true" then Some(BigInt(1))
    else if t == "false" then Some(BigInt(0))
    else scala.util.Try(BigInt(t)).toOption

  // ---- elaboration: zaozi generator → HW module circt-bmc reads ----------------------------------------------------

  /** Elaborate a UT generator to FIRRTL, link it, and lower to the HW dialect. Returns `(hwModulePath, topName)`. */
  private def elaborateToHw[
    PARAM <: Parameter,
    L <: LayerInterface[PARAM],
    I <: HWInterface[PARAM],
    P <: DVInterface[PARAM, L]
  ](dut:       Generator[PARAM, L, I, P],
    parameter: PARAM,
    outDir:    os.Path
  ): (os.Path, String) =
    os.makeDir.all(outDir)
    val top       = dut.moduleName(parameter)
    val moduleDir = outDir / s"formal_mlir_${parameter.hashCode.toHexString}"
    os.makeDir.all(moduleDir)
    elaborate(dut, parameter, moduleDir)
    val modules   = os.list(moduleDir).filter(_.ext == "mlirbc").sortBy(_.last)
    require(modules.nonEmpty, s"elaboration produced no .mlirbc files under $moduleDir")
    val linked    = moduleDir / "linked.mlir"
    os.proc(
      Seq("firld", s"--base-circuit=$top", "--no-mangle") ++ modules.map(_.toString) ++ Seq("-o", linked.toString)
    ).call()
    (firtoolToHw(linked), top)

  /** Lower a FIRRTL module (with zaozi's `circt.verif.*` / `circt.ltl.*` intrinsics) to the HW dialect circt-bmc reads
    * (`verif.assert` / `verif.assume` + `ltl.*`), via `firtool -ir-hw`.
    */
  private[utlib] def firtoolToHw(firrtl: os.Path): os.Path =
    val out = firrtl / os.up / s"${firrtl.last}.hw.mlir"
    os.write.over(out, os.proc(circtTool("firtool"), firrtl.toString, "-ir-hw").call(check = true).out.text())
    out

  private def elaborate[
    HP <: Parameter,
    HL <: LayerInterface[HP],
    HI <: HWInterface[HP],
    HProbe <: DVInterface[HP, HL]
  ](dut:       Generator[HP, HL, HI, HProbe],
    parameter: HP,
    outDir:    os.Path
  ): Unit =
    val arena = Arena.ofConfined()
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      summon[FirrtlDialectApi].loadDialect
      summon[LTLDialectApi].loadDialect
      summon[VerifDialectApi].loadDialect
      Elaboration.inOutputDirectory(outDir) {
        dut.dumpMlirbc(parameter)
      }
      summon[Context].destroy()
    finally arena.close()

  // ---- tool resolution ---------------------------------------------------------------------------------------------

  /** Resolve a CIRCT tool from `CIRCT_INSTALL_PATH` (the flake-provided install) when set, else fall back to `PATH`. A
    * forked test JVM's `PATH` can lag the devshell, but `CIRCT_INSTALL_PATH` is threaded in explicitly.
    */
  private def circtTool(name: String): String =
    sys.env
      .get("CIRCT_INSTALL_PATH")
      .map(p => os.Path(p) / "bin" / name)
      .filter(os.exists)
      .map(_.toString)
      .getOrElse(name)

  /** Locate a `libz3.so` for circt-bmc's JIT: `ZAOZI_LIBZ3` if set, else the first one in the Nix store. */
  private def defaultLibz3(): os.Path =
    sys.env.get("ZAOZI_LIBZ3").map(os.Path(_)).getOrElse {
      val found =
        os.proc("bash", "-c", "ls /nix/store/*z3*/lib/libz3.so 2>/dev/null | head -1").call(check = false).out.trim()
      require(found.nonEmpty, "libz3.so not found; set ZAOZI_LIBZ3 to its path")
      os.Path(found)
    }
