// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

import me.jiuyang.zaozi.*

import java.util.regex.Pattern

/** A per-cycle trace: for each traced signal (DUT inputs + register state), its value at each cycle. Serves as both a
  * `check` counterexample and a `generate` transaction.
  */
final case class Trace(cycles: Int, values: Map[String, Vector[BigInt]])

/** The interface between the solver's raw counterexample and the framework's [[Trace]]: lowering may rename an
  * ABI-level signal in the model (a delayed drive is traced as its register's `_next`), and the lowering that did the
  * renaming is the one that records it here. Applied once at the model→trace boundary, so everything downstream —
  * [[Stimulus]], reports, tests — speaks pure ABI names and no name convention leaks past this type.
  */
final case class TraceBinding(renames: Map[String, String]):
  /** The trace with solver-side names translated back to ABI names (a translated entry wins over a raw one). */
  def rebind(trace: Trace): Trace =
    val (renamed, kept) = trace.values.partition((k, _) => renames.contains(k))
    Trace(trace.cycles, kept ++ renamed.map((k, v) => renames(k) -> v))

object TraceBinding:
  /** No renaming — the model's signal names are already the ABI's. */
  val identity: TraceBinding = TraceBinding(Map.empty)

  /** The renaming [[FormalUT.delay]] introduces: a delayed drive's value at row t is its delay register's `_next`. */
  def delayedDrives(drives: Seq[String]): TraceBinding =
    TraceBinding(drives.map(d => s"$d${MlirBmc.DelayedDriveSuffix}_next" -> d).toMap)

/** A lowered bounded model: the HW file circt-bmc reads, the module to solve, and the [[TraceBinding]] that maps the
  * solver's signal names back to the ABI's.
  */
final case class LoweredModel(hw: os.Path, top: String, binding: TraceBinding)

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

  /** [[generate]] on a lowered model, with the witness translated back to ABI names through the model's binding. */
  def generate(model: LoweredModel, bound: Int): GenerateOutcome =
    generate(model.hw, model.top, bound) match
      case GenerateOutcome.Generated(t) => GenerateOutcome.Generated(model.binding.rebind(t))
      case other                        => other

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
    generate(lowerGenerator(dut, parameter, outDir, delayedDrives), bound)

  /** Lower a zaozi generator to the BMC-ready HW module, without running the solver — the composition point for flows
    * that post-process the model first (e.g. [[SvImport.mergeForBmc]] splicing an imported IP over an extern).
    *
    * The pipeline: elaborate + `firld` link, `firtool -ir-hw`, then in memory — prune what circt-bmc cannot ingest,
    * pin register initial state, model the testbench's drive register for `delayedDrives` — and finally
    * `circt-opt --strip-contracts --canonicalize`. Pass the ABI drive port(s) as `delayedDrives` whenever the witness
    * will replay on the Model B testbench, so model time equals testbench time.
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
  ): LoweredModel =
    os.makeDir.all(outDir)
    val top       = dut.moduleName(parameter)
    val moduleDir = outDir / s"formal_mlir_${parameter.hashCode.toHexString}"
    val linked    = Lower.elaborateAndLink(dut, parameter, moduleDir, top)
    val hw        = firtoolToHw(linked)
    val bmcReady  = delay(prune(readLines(hw)), top, delayedDrives)
    val bmcFile   = hw / os.up / s"${hw.last}.bmc.mlir"
    os.write.over(bmcFile, bmcReady.mkString("\n"))
    LoweredModel(stripContracts(bmcFile), top, TraceBinding.delayedDrives(delayedDrives))

  // ---- the circt-bmc engine ----------------------------------------------------------------------------------------

  private[utlib] enum Bmc:
    case Violated(trace: Trace)
    case Held
    case Unknown(detail: String)

  /** One circt-bmc run, retried when the solver *crashes* rather than decides.
    *
    * circt-bmc aborts intermittently on some queries — the give-away is a backtrace on stderr and a non-zero exit,
    * with no verdict either way. That is not an undecidable query, and reporting it as [[Bmc.Unknown]] would make a
    * flaky crash indistinguishable from a real "cannot decide", which the whole argument for the `Infeasible`
    * verdict depends on telling apart. Retry a crash; report a genuine non-verdict as Unknown.
    */
  private def run(hwModule: os.Path, top: String, bound: Int, attempt: Int = 1): Bmc =
    runOnce(hwModule, top, bound) match
      case Bmc.Unknown(detail) if attempt < 3 && looksLikeCrash(detail) => run(hwModule, top, bound, attempt + 1)
      case verdict                                                      => verdict

  /** A backtrace frame or an abort message, rather than anything circt-bmc says about the property. */
  private def looksLikeCrash(detail: String): Boolean =
    detail.contains("0x") && (detail.contains("libc.so") || detail.contains("+0x")) ||
      detail.contains("Stack dump") || detail.contains("PLEASE submit a bug report")

  private def runOnce(hwModule: os.Path, top: String, bound: Int): Bmc =
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
        s"--shared-libs=$libz3"
      )
      .call(check = false, stderr = os.Pipe)
    // stdout only: circt-bmc prints the counterexample there while the JIT and library loader write warnings to
    // stderr, and merging the two streams can interleave mid-line — which showed up as an intermittent `Unknown`
    // on a run whose counterexample was in fact present.
    parseTrace(out.out.text()) match
      case Bmc.Unknown(_) if out.err.text().nonEmpty =>
        parseTrace(out.out.text() + "\n" + out.err.text())
      case verdict                                   => verdict

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
    val lines  = output.linesIterator.map(_.trim).toVector
    val blocks = groupsAfter(lines, _.startsWith("counterexample for")).map { block =>
      groupsAfter(block, l => l.startsWith("cycle") && l.endsWith(":")).map { cycle =>
        cycle.flatMap {
          case s"$name = $value" => parseValue(value).map(name.trim -> _)
          case _                 => None
        }.toMap
      }
    }

    if blocks.nonEmpty then
      val deepest = blocks.maxBy(_.size)
      val signals = deepest.flatMap(_.keys).distinct
      Bmc.Violated(Trace(deepest.size, signals.map(s => s -> deepest.map(_.getOrElse(s, BigInt(0)))).toMap))
    else if output.contains("Bound reached with no violations") then Bmc.Held
    else Bmc.Unknown(lines.filter(_.nonEmpty).lastOption.getOrElse(""))

  /** The runs delimited by marker lines: everything before the first marker is dropped, each group holds the lines
    * after one marker (the marker itself excluded).
    */
  private def groupsAfter(lines: Vector[String], marker: String => Boolean): Vector[Vector[String]] =
    val starts = lines.zipWithIndex.collect { case (l, i) if marker(l) => i }
    starts.zipAll(starts.drop(1), -1, lines.length).map((s, e) => lines.slice(s + 1, e))

  /** Parse a value as circt-bmc's trace (`0x..`) or a Z3 model (`#x..`/`#b..`) prints it. */
  private def parseValue(token: String): Option[BigInt] =
    val t = token.trim
    if t.startsWith("0x") || t.startsWith("0X") || t.startsWith("#x") then
      scala.util.Try(BigInt(t.drop(2), 16)).toOption
    else if t.startsWith("#b") then scala.util.Try(BigInt(t.drop(2), 2)).toOption
    else if t == "true" then Some(BigInt(1))
    else if t == "false" then Some(BigInt(0))
    else scala.util.Try(BigInt(t)).toOption

  // ---- making the lowered module BMC-ready -------------------------------------------------------------------------

  private def readLines(p: os.Path): Vector[String] = os.read(p).split("\n", -1).toVector

  /** Lower a FIRRTL module (with zaozi's `circt.verif.*` / `circt.ltl.*` intrinsics) to the HW dialect circt-bmc reads
    * (`verif.assert` / `verif.assume` + `ltl.*`), via `firtool -ir-hw`.
    */
  private[utlib] def firtoolToHw(firrtl: os.Path): os.Path =
    val out = firrtl / os.up / s"${firrtl.last}.hw.mlir"
    os.write.over(out, os.proc(CirctTools("firtool"), firrtl.toString, "-ir-hw").call(check = true).out.text())
    out

  /** Strip what circt-bmc cannot ingest from a `firtool -ir-hw` module, and pin register initial state.
    *
    * A zaozi UT's layered probes lower to observation plumbing the bounded model does not need: bound `*_Verification`
    * modules full of `sv.xmr` refs, `sv.macro.*`/`emit.file` ref-macro machinery, the `hw.hierpath`s pointing into
    * them, `om.*` Object Model metadata, the `{doNotPrint}` bind instances, and `sv.namehint` attributes. circt-bmc
    * registers none of the `sv`/`emit` dialects, so any survivor is a parse error. Formal intent must live as
    * `verif.*` in the module body — a layered assert sits in a pruned module and is dropped with it. Each kept
    * module's registers are pinned via [[MlirBmc.pinFirregs]].
    *
    * The prune is structural on the printed IR: top-level entities are delimited by brace depth, and an `hw.module`
    * whose body holds `sv.` ops is a lowered layer, not logic.
    */
  private[utlib] def prune(lines: Vector[String]): Vector[String] =
    // An `sv.` OP starts the line or follows `= `; `{sv.namehint = ...}` is only an attribute.
    val svOp = raw"(^|= )sv\.".r

    def dropEntity(range: (Int, Int)): Boolean =
      val head = lines(range._1).trim
      head.startsWith("sv.")
      || head.startsWith("emit.file")
      || head.startsWith("hw.hierpath")
      || head.startsWith("om.") // Object Model metadata references the dropped hierpaths and carries no logic
      || (head.startsWith("hw.module") && (range._1 to range._2).exists(i => svOp.findFirstIn(lines(i).trim).nonEmpty))

    val body = MlirBmc
      .topEntities(lines)
      .filterNot(dropEntity)
      .flatMap((s, e) => MlirBmc.pinFirregs(lines.slice(s, e + 1)))
      .collect {
        case l if !l.contains("{doNotPrint}") =>
          // With the hierpaths gone an inner sym has no user, and circt-bmc refuses an `hw.wire` that carries one.
          MlirBmc.stripWireSym(MlirBmc.stripNamehints(l))
      }
    Vector(lines.head) ++ body ++ Vector("}", "")

  /** Model the Model B testbench's drive path: the tick callback's return value crosses a registered DPI boundary, so
    * the DUT sees stimulus line N only during cycle (N, N+1] — one cycle later than a bare input port would suggest.
    * Routing each drive port through one zero-initialized register makes the bounded model's time axis equal the
    * testbench's, so a witness row IS the stimulus line with the same index. Constraints that only relate stimulus
    * values to each other survive without this (the whole stream shifts uniformly); constraints coupled to the DUT's
    * own startup dynamics do not.
    */
  private[utlib] def delay(lines: Vector[String], top: String, drives: Seq[String]): Vector[String] =
    if drives.isEmpty then return lines
    val headerIdx = lines.indexWhere(_.contains(s"hw.module @$top("))
    require(headerIdx >= 0, s"top module @$top not found")
    val header    = lines(headerIdx)
    // A clockless (combinational) top has no registered sampling to align — the delay is correctly a no-op.
    val clockOpt  = raw"in (%[\w.$$]+) : !seq\.clock".r.findFirstMatchIn(header).map(_.group(1))
    if clockOpt.isEmpty then return lines
    val endIdx    = MlirBmc.entityEnd(lines, headerIdx)

    val ports    = drives.map { d =>
      val ty = raw"in %${Pattern.quote(d)} : (i\d+)".r
        .findFirstMatchIn(header)
        .map(_.group(1))
        .getOrElse(throw IllegalArgumentException(s"drive port %$d not found on @$top"))
      (d, ty, Pattern.compile(raw"%${Pattern.quote(d)}\b"))
    }
    val inserted = ports.flatMap { (d, ty, _) =>
      MlirBmc.pinnedReg("    ", s"%$d${MlirBmc.DelayedDriveSuffix}", s"%$d", clockOpt.get, ty, MlirBmc.zeroConst(ty))
    }
    val body     = lines.slice(headerIdx + 1, endIdx).map { l =>
      ports.foldLeft(l)((acc, p) => p._3.matcher(acc).replaceAll(s"%${p._1}${MlirBmc.DelayedDriveSuffix}"))
    }
    lines.take(headerIdx + 1) ++ inserted ++ body ++ lines.drop(endIdx)

  /** Remove `verif.contract` regions via `circt-opt --strip-contracts`: a DUT's own contract is design metadata proven
    * in its own flow, not part of the bounded model, and circt-bmc rejects the contract region's use of the contract
    * result. `--canonicalize` then folds the (de-symed) `hw.wire`s away — circt-bmc marks `hw.wire` illegal and runs
    * no canonicalization of its own.
    */
  private def stripContracts(hwMlir: os.Path): os.Path =
    val out = hwMlir / os.up / s"${hwMlir.last}.nocontract.mlir"
    os.write.over(
      out,
      os.proc(CirctTools("circt-opt"), hwMlir.toString, "--strip-contracts", "--canonicalize")
        .call(check = true)
        .out
        .text()
    )
    out

  // File-based wrappers, kept for unit tests of the individual passes.
  private[utlib] def pruneForBmc(hwMlir: os.Path): os.Path =
    val out = hwMlir / os.up / s"${hwMlir.last}.bmc.mlir"
    os.write.over(out, prune(readLines(hwMlir)).mkString("\n"))
    out

  private[utlib] def delayInputs(hwMlir: os.Path, top: String, drives: Seq[String]): os.Path =
    val out = hwMlir / os.up / s"${hwMlir.last}.delayed.mlir"
    os.write.over(out, delay(readLines(hwMlir), top, drives).mkString("\n"))
    out

  // ---- tool resolution ---------------------------------------------------------------------------------------------

  /** The `libz3.so` circt-bmc's JIT loads: `ZAOZI_LIBZ3`, else the dev shell's `Z3_LIB`, else the first one in the
    * Nix store (resolved once per JVM).
    */
  private lazy val libz3: os.Path =
    sys.env
      .get("ZAOZI_LIBZ3")
      .orElse(sys.env.get("Z3_LIB"))
      .map(os.Path(_))
      .getOrElse {
        val found =
          os.proc("bash", "-c", "ls /nix/store/*z3*/lib/libz3.so 2>/dev/null | head -1").call(check = false).out.trim()
        require(found.nonEmpty, "libz3.so not found; set ZAOZI_LIBZ3 (or Z3_LIB) to its path")
        os.Path(found)
      }
