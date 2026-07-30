// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.rvprobe.frontend.PortDir
import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.parser.{parseZ3Output, Z3Status}
import me.jiuyang.smtlib.tpe.*

import org.llvm.mlir.scalalib.capi.dialect.func.{DialectApi as FuncDialect, FuncApi, given}
import org.llvm.mlir.scalalib.capi.dialect.smt.{given_DialectApi, DialectApi as SmtDialect}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, LocationApi, Module, ModuleApi, given}
import org.llvm.mlir.scalalib.capi.target.exportsmtlib.given_ExportSmtlibApi

import java.lang.foreign.Arena

/** The mutable elaboration state of one solve.
  *
  * Constraints are registered as thunks rather than run eagerly, because each stage runs them in its own MLIR context:
  * stage 1 replays only the kind constraints, stage 2 replays only the payload constraints.
  *
  * Variable declarations are memoized. `smtValue` emits a fresh `declare-fun` on every call, so two constraints
  * referring to the same slot must go through [[kind]]/[[payload]] rather than calling `smtValue` themselves —
  * otherwise Z3 rejects the duplicate declaration.
  */
final class TxnRecipe(
  val iface: DutInterface,
  val cycles: Int):

  private val kinds    = scala.collection.mutable.ListBuffer.empty[TxnRecipe => Unit]
  private val payloads = scala.collection.mutable.ListBuffer.empty[TxnRecipe => Unit]
  private val declared = scala.collection.mutable.Map.empty[String, Ref[SInt]]

  /** SMT variable name holding the transaction kind chosen for `port` at `cycle`. */
  def kindVar(cycle: Int, port: String): String = s"kind_${port}_$cycle"

  /** SMT variable name holding the payload chosen for `port` at `cycle`. */
  def payloadVar(cycle: Int, port: String): String = s"payload_${port}_$cycle"

  /** The (memoized) kind variable for `port` at `cycle`. */
  def kind(
    cycle: Int,
    port:  String
  )(
    using Arena,
    Context,
    Block
  ): Ref[SInt] = declare(kindVar(cycle, port))

  /** The (memoized) payload variable for `port` at `cycle`. */
  def payload(
    cycle: Int,
    port:  String
  )(
    using Arena,
    Context,
    Block
  ): Ref[SInt] = declare(payloadVar(cycle, port))

  private def declare(
    name: String
  )(
    using Arena,
    Context,
    Block
  ): Ref[SInt] = declared.getOrElseUpdate(name, smtValue(name, SInt))

  def addKindConstraint(f:    TxnRecipe => Unit): Unit = kinds += f
  def addPayloadConstraint(f: TxnRecipe => Unit): Unit = payloads += f

  def kindConstraints:    Seq[TxnRecipe => Unit] = kinds.toSeq
  def payloadConstraints: Seq[TxnRecipe => Unit] = payloads.toSeq

/** Solves a transaction-level stimulus sequence for a DUT.
  *
  * Implementors declare the DUT's interface, the sequence length, and a block of constraints; [[solve]] returns a
  * satisfying [[SolvedStimulus]] or throws with the full SMT-LIB and Z3 output when the constraints are unsatisfiable.
  *
  * The solve is split in two stages for the same reason `rvprobe`'s `RVGenerator` is: stage 1 fixes *what happens* (the
  * transaction kind per slot), stage 2 fills *the values* (payloads) with the kinds already pinned, keeping each query
  * in a small, fast fragment.
  */
trait TxnSolver:
  /** The DUT's transaction surface. */
  def iface: DutInterface

  /** Sequence length, in cycles. */
  def cycles: Int

  /** Register constraints. Runs once per stage, in that stage's context. */
  def constraints(): (Arena, Context, Block, TxnRecipe) ?=> Unit

  /** Z3 random seed. Fix it for reproducible sequences. */
  val seed: Int = 0

  final def solve(): SolvedStimulus =
    // The SMT integer-constant binding takes an Int, so payload bounds are
    // limited to what fits there.
    iface.ports.foreach { p =>
      require(
        p.payloadWidth >= 1 && p.payloadWidth <= 30,
        s"port ${p.name}: payloadWidth ${p.payloadWidth} is out of the supported 1..30 range " +
          "(the smtlib integer-constant binding takes an Int)"
      )
    }

    val kinds    = solveKinds()
    val payloads = solvePayloads(kinds)
    val txns     = for
      cycle <- 0 until cycles
      port  <- iface.ports
    yield
      val kind = kinds.getOrElse((cycle, port.name), TxnKind.Idle)
      val raw  = payloads.getOrElse(s"payload_${port.name}_$cycle", BigInt(0))
      SolvedTxn(cycle, port.name, kind, if kind == TxnKind.Enqueue then raw else BigInt(0))
    SolvedStimulus(iface.dutName, cycles, txns.toSeq)

  // ================== Stage 1: transaction kinds ==================

  private def solveKinds(): Map[(Int, String), TxnKind] =
    withContext("kind solving") { recipe =>
      // Every slot's kind is a legal TxnKind id; a Monitor port can only be
      // idle or dequeued, a Drive port only idle or enqueued.
      for
        cycle <- 0 until cycles
        port  <- iface.ports
      do
        val v     = recipe.kind(cycle, port.name)
        val legal = port.dir match
          case PortDir.Drive   => Seq(TxnKind.Idle, TxnKind.Enqueue)
          case PortDir.Monitor => Seq(TxnKind.Idle, TxnKind.Dequeue)
        smtAssert(smtOr(legal.map(k => v === k.id.S)*))
      recipe.kindConstraints.foreach(_(recipe))
    }.collect {
      case (k, v) if k.startsWith("kind_") =>
        val parts = k.stripPrefix("kind_").split('_')
        val cycle = parts.last.toInt
        val port  = parts.dropRight(1).mkString("_")
        (cycle, port) -> TxnKind.fromId(v.toInt)
    }.toMap

  // ================== Stage 2: payloads ==================

  private def solvePayloads(solvedKinds: Map[(Int, String), TxnKind]): Map[String, BigInt] =
    withContext("payload solving") { recipe =>
      // Pin the kinds solved in stage 1 so payload constraints that branch on
      // the kind see the same sequence.
      solvedKinds.foreach { case ((cycle, port), kind) =>
        smtAssert(recipe.kind(cycle, port) === kind.id.S)
      }
      // Payloads are bounded by their port width.
      for
        cycle <- 0 until cycles
        port  <- iface.ports
      do
        val v = recipe.payload(cycle, port.name)
        smtAssert(v >= 0.S & v < (1 << port.payloadWidth).S)
      recipe.payloadConstraints.foreach(_(recipe))
    }.filter(_._1.startsWith("payload_"))

  // ================== Shared MLIR / Z3 plumbing ==================

  private def withContext(
    stage: String
  )(body:  (Arena, Context, Block) ?=> TxnRecipe => Unit
  ): Map[String, BigInt] =
    given arena:   Arena   = Arena.ofConfined()
    given context: Context = summon[ContextApi].contextCreate
    summon[SmtDialect].loadDialect()
    summon[FuncDialect].loadDialect()
    given module:  Module  = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
    val func = summon[FuncApi].op("func")
    given funcBlock: Block = func.block
    func.appendToModule()

    try
      solver {
        smtSetLogic("QF_LIA")
        val recipe      = new TxnRecipe(iface, cycles)
        given TxnRecipe = recipe
        constraints()
        body(recipe)
        smtCheck
      }

      val smtlib = {
        val out = new StringBuilder
        summon[Module].exportSMTLIB(out ++= _)
        prepare(out.toString())
      }
      val output = os.proc(Toolchain.z3, "-in", "-t:5000").call(stdin = smtlib, check = false).out.text()
      val result = parseZ3Output(output)
      if result.status != Z3Status.Sat then
        System.err.println(s"=== $stage failed: ${result.status} ===")
        System.err.println(s"\nSMTLIB:\n$smtlib")
        System.err.println(s"\nZ3 output:\n$output")
        throw new RuntimeException(s"$stage failed with status: ${result.status}")
      result.model.collect { case (k, v: BigInt) => k -> v }.toMap
    finally
      context.destroy()
      arena.close()

  /** Make the exported SMT-LIB solvable and readable by Z3.
    *
    * Two edits, both required:
    *   - inject the seed before `(set-logic …)` so each solve is reproducible;
    *   - swap the trailing `(reset)` the MLIR exporter emits for `(get-model)`, without which Z3 prints only
    *     `sat`/`unsat` and the model comes back empty.
    */
  private def prepare(smtlib: String): String =
    val options = Seq(
      s"(set-option :smt.random_seed $seed)",
      s"(set-option :sat.random_seed $seed)"
    ).mkString("\n")
    smtlib
      .replaceFirst("""\(set-logic """, s"$options\n(set-logic ")
      .replace("(reset)", "(get-model)")
