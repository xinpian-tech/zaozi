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
  ](dut:           Generator[PARAM, L, I, P],
    parameter:     PARAM,
    bound:         Int,
    outDir:        os.Path,
    delayedDrives: Seq[String] = Seq.empty
  ): CheckOutcome =
    val (hw, top) = elaborateToHw(dut, parameter, outDir, delayedDrives)
    check(hw, top, bound)

  /** Generate a transaction directly from a zaozi UT generator whose SVA asserts `¬constraint`. */
  def generateGenerator[
    PARAM <: Parameter,
    L <: LayerInterface[PARAM],
    I <: HWInterface[PARAM],
    P <: DVInterface[PARAM, L]
  ](dut:           Generator[PARAM, L, I, P],
    parameter:     PARAM,
    bound:         Int,
    outDir:        os.Path,
    delayedDrives: Seq[String] = Seq.empty
  ): GenerateOutcome =
    val (hw, top) = elaborateToHw(dut, parameter, outDir, delayedDrives)
    generate(hw, top, bound)

  /** Lower a zaozi generator to the pruned HW module circt-bmc reads, without running it — the composition point for
    * flows that post-process the model first (e.g. [[SvImport.mergeForBmc]] splicing an imported IP over an extern).
    *
    * `delayedDrives` names the top module's drive ports to route through [[delayInputs]] — pass the ABI drive port(s)
    * whenever the witness will replay on the Model B testbench, so model time equals testbench time.
    */
  def lowerGenerator[
    PARAM <: Parameter,
    L <: LayerInterface[PARAM],
    I <: HWInterface[PARAM],
    P <: DVInterface[PARAM, L]
  ](dut:           Generator[PARAM, L, I, P],
    parameter:     PARAM,
    outDir:        os.Path,
    delayedDrives: Seq[String] = Seq.empty
  ): (os.Path, String) = elaborateToHw(dut, parameter, outDir, delayedDrives)

  /** Model the Model B testbench's drive path: the tick callback's return value crosses a registered DPI boundary, so
    * the DUT sees stimulus line N only during cycle (N, N+1] — one cycle later than a bare input port would suggest.
    * Routing each drive port through one zero-initialized register makes the bounded model's time axis equal the
    * testbench's, so a witness row IS the stimulus line with the same index. Constraints that only relate stimulus
    * values to each other survive without this (the whole stream shifts uniformly); constraints coupled to the DUT's
    * own startup dynamics do not.
    */
  private[utlib] def delayInputs(hwMlir: os.Path, top: String, drives: Seq[String]): os.Path =
    if drives.isEmpty then return hwMlir
    val lines     = os.read(hwMlir).split("\n", -1).toVector
    val headerIdx = lines.indexWhere(_.contains(s"hw.module @$top("))
    require(headerIdx >= 0, s"top module @$top not found in $hwMlir")
    var depth     = 0
    var endIdx    = headerIdx
    var found     = false
    for i <- headerIdx until lines.length if !found do
      depth += lines(i).count(_ == '{') - lines(i).count(_ == '}')
      if depth == 0 then
        endIdx = i
        found = true
    val header   = lines(headerIdx)
    // A clockless (combinational) top has no registered sampling to align — the delay is correctly a no-op.
    val clockOpt = raw"in (%[\w.$$]+) : !seq\.clock".r.findFirstMatchIn(header).map(_.group(1))
    if clockOpt.isEmpty then return hwMlir
    val clock    = clockOpt.get

    val inserted = drives.flatMap { d =>
      val ty = raw"in %${java.util.regex.Pattern.quote(d)} : (i\d+)".r
        .findFirstMatchIn(header)
        .map(_.group(1))
        .getOrElse(throw IllegalArgumentException(s"drive port %$d not found on @$top"))
      val z  = if ty == "i1" then "false" else s"0 : $ty"
      Vector(
        s"    %${d}__d_init = seq.initial() {",
        s"      %${d}__d_c = hw.constant $z",
        s"      seq.yield %${d}__d_c : $ty",
        s"    } : () -> !seq.immutable<$ty>",
        s"    %${d}__d = seq.compreg %$d, $clock initial %${d}__d_init : $ty"
      )
    }
    val body     = lines.slice(headerIdx + 1, endIdx).map { l =>
      drives.foldLeft(l)((acc, d) => acc.replaceAll(raw"%${java.util.regex.Pattern.quote(d)}\b", s"%${d}__d"))
    }
    val out      = hwMlir / os.up / s"${hwMlir.last}.delayed.mlir"
    os.write.over(
      out,
      (lines.take(headerIdx + 1) ++ inserted ++ body ++ lines.drop(endIdx)).mkString("\n")
    )
    out

  // ---- the circt-bmc engine ----------------------------------------------------------------------------------------

  private[utlib] enum Bmc:
    case Violated(trace: Trace)
    case Held
    case Unknown(detail: String)

  /** One circt-bmc run on `hwModule`, parsed into a [[Bmc]] verdict. */
  private def run(hwModule: os.Path, top: String, bound: Int): Bmc =
    val out = os
      .proc(
        CirctTools("circt-bmc"),
        hwModule.toString,
        "-b",
        bound.toString,
        "--module",
        top,
        // One BMC step = one rising edge, so trace rows are clock cycles: without this each step is a clock
        // phase, register state updates every other row, and a multi-cycle witness can't drive a testbench.
        "--rising-clocks-only",
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
  ](dut:           Generator[PARAM, L, I, P],
    parameter:     PARAM,
    outDir:        os.Path,
    delayedDrives: Seq[String] = Seq.empty
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
      Seq(CirctTools("firld"), s"--base-circuit=$top", "--no-mangle") ++ modules.map(_.toString) ++
        Seq("-o", linked.toString)
    ).call()
    (stripContracts(delayInputs(pruneForBmc(firtoolToHw(linked)), top, delayedDrives)), top)

  /** Lower a FIRRTL module (with zaozi's `circt.verif.*` / `circt.ltl.*` intrinsics) to the HW dialect circt-bmc reads
    * (`verif.assert` / `verif.assume` + `ltl.*`), via `firtool -ir-hw`.
    */
  private[utlib] def firtoolToHw(firrtl: os.Path): os.Path =
    val out = firrtl / os.up / s"${firrtl.last}.hw.mlir"
    os.write.over(out, os.proc(CirctTools("firtool"), firrtl.toString, "-ir-hw").call(check = true).out.text())
    out

  /** Pin each sync-reset register's initial state to its reset value, within one top-level entity's lines.
    *
    * circt-bmc leaves register initial state FREE — a solver can then satisfy a cross-cycle constraint by inventing
    * pre-loaded history instead of driving real inputs. Its externalize-registers pass does honor `seq.compreg …
    * initial %v` when `%v` is a `seq.initial`-wrapped constant, so a
    * `seq.firreg %in clock %clk reset sync %rst, %cst` (the lowering of zaozi's `RegInit` under a sync active-high
    * reset) is rewritten to that form, with the sync reset itself re-expressed as a mux on the register input. Async
    * resets and non-constant reset values are left alone — free init, as before. hw.module bodies are graph regions,
    * so the inserted lines need no dominance ordering.
    */
  private def pinRegisterInit(block: Vector[String]): Vector[String] =
    // 1-bit constants print bare (`hw.constant true`); wider ones carry the type (`hw.constant 0 : i8`).
    val constDef = raw"(%[\w.$$]+) = hw.constant ([-\d]+ : [\w<>]+|true|false)".r
    val consts   = block.flatMap(l => constDef.findFirstMatchIn(l.trim).map(m => m.group(1) -> m.group(2))).toMap
    def constTy(text: String): String = if text == "true" || text == "false" then "i1" else text.split(" : ").last
    val firreg   =
      raw"^(\s*)(%[\w.$$]+) = seq\.firreg (%[\w.$$]+) clock (%[\w.$$]+)(?: sym @[\w.]+)? reset sync (%[\w.$$]+), (%[\w.$$]+)(?: \{[^}]*\})? : (\S+)$$".r
    block.flatMap {
      case l @ firreg(ind, r, in, clk, rst, rv, ty) =>
        consts.get(rv) match
          case Some(constText) if constTy(constText) == ty =>
            Vector(
              s"$ind${r}__init = seq.initial() {",
              s"$ind  ${r}__c = hw.constant $constText",
              s"$ind  seq.yield ${r}__c : $ty",
              s"$ind} : () -> !seq.immutable<$ty>",
              s"$ind${r}__mux = comb.mux $rst, $rv, $in : $ty",
              s"$ind$r = seq.compreg ${r}__mux, $clk initial ${r}__init : $ty"
            )
          case _                                           => Vector(l)
      case l                                               => Vector(l)
    }

  /** Remove `verif.contract` regions via `circt-opt --strip-contracts`: a DUT's own contract is design metadata proven
    * in its own flow, not part of the bounded model, and circt-bmc rejects the contract region's use of the contract
    * result. `--canonicalize` then folds the (de-symed) `hw.wire`s away — circt-bmc marks `hw.wire` illegal and runs no
    * canonicalization of its own.
    */
  private def stripContracts(hwMlir: os.Path): os.Path =
    val out = hwMlir / os.up / s"${hwMlir.last}.nocontract.mlir"
    os.write.over(
      out,
      os.proc(CirctTools("circt-opt"), hwMlir.toString, "--strip-contracts", "--canonicalize").call(check = true).out.text()
    )
    out

  /** Strip what circt-bmc cannot ingest from a `firtool -ir-hw` module.
    *
    * A zaozi UT's layered probes lower to observation plumbing the bounded model does not need: bound `*_Verification`
    * modules full of `sv.xmr` refs, `sv.macro.*`/`emit.file` ref-macro machinery, the `hw.hierpath`s pointing into
    * them, the `{doNotPrint}` bind instances, and `sv.namehint` attributes. circt-bmc registers none of the `sv`/`emit`
    * dialects, so any survivor is a parse error. Formal intent must live as `verif.*` in the module body — a layered
    * assert sits in a pruned module and is dropped with it.
    *
    * The prune is structural on the printed IR: top-level entities are delimited by brace depth, and an `hw.module`
    * whose body holds `sv.` ops is a lowered layer, not logic.
    */
  private[utlib] def pruneForBmc(hwMlir: os.Path): os.Path =
    val lines    = os.read(hwMlir).split("\n", -1).toVector
    // Top-level entities: contiguous line ranges at brace depth 1 (directly under the outer `module {`).
    val entities = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)] // [start, end] inclusive
    var depth    = 0
    var start    = -1
    for (l, i) <- lines.zipWithIndex do
      val net = l.count(_ == '{') - l.count(_ == '}')
      if depth == 1 && start < 0 && l.trim.nonEmpty && l.trim != "}" then start = i
      depth += net
      if start >= 0 && depth <= 1 then
        entities += ((start, i))
        start = -1

    // An `sv.` OP starts the line or follows `= `; `{sv.namehint = ...}` is only an attribute.
    val svOp = raw"(^|= )sv\.".r

    def dropEntity(range: (Int, Int)): Boolean =
      val head = lines(range._1).trim
      head.startsWith("sv.")
      || head.startsWith("emit.file")
      || head.startsWith("hw.hierpath")
      || head.startsWith("om.") // Object Model metadata references the dropped hierpaths and carries no logic
      || (head.startsWith("hw.module") && (range._1 to range._2).exists(i => svOp.findFirstIn(lines(i).trim).nonEmpty))

    val keptRanges = entities.filterNot(dropEntity).toVector
    val body       = keptRanges
      .flatMap((s, e) => pinRegisterInit(lines.slice(s, e + 1)))
      .filterNot(_.contains("{doNotPrint}"))
      .map(_.replaceAll(""" \{sv\.namehint = "[^"]*"\}""", "").replaceAll("""sv\.namehint = "[^"]*", """, ""))
      // With the hierpaths gone an inner sym has no user, and circt-bmc refuses an `hw.wire` that carries one.
      .map(l => if l.contains("hw.wire") then l.replaceAll(""" sym @[\w.]+\s+""", " ") else l)
    val out        = hwMlir / os.up / s"${hwMlir.last}.bmc.mlir"
    os.write.over(out, (Vector(lines.head) ++ body ++ Vector("}", "")).mkString("\n"))
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

  /** Locate a `libz3.so` for circt-bmc's JIT: `ZAOZI_LIBZ3` if set, else the first one in the Nix store. */
  private def defaultLibz3(): os.Path =
    sys.env.get("ZAOZI_LIBZ3").map(os.Path(_)).getOrElse {
      val found =
        os.proc("bash", "-c", "ls /nix/store/*z3*/lib/libz3.so 2>/dev/null | head -1").call(check = false).out.trim()
      require(found.nonEmpty, "libz3.so not found; set ZAOZI_LIBZ3 to its path")
      os.Path(found)
    }
