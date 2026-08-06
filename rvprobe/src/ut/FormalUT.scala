// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.ut

import me.jiuyang.smtlib.Z3
import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.tpe.*
import me.jiuyang.smtlib.parser.{parseZ3Output, Z3Status}

import org.llvm.mlir.scalalib.capi.dialect.func.{DialectApi as FuncDialect, FuncApi, given}
import org.llvm.mlir.scalalib.capi.dialect.smt.{given_DialectApi, DialectApi as SmtDialect}
import org.llvm.mlir.scalalib.capi.target.exportsmtlib.given_ExportSmtlibApi
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, LocationApi, Module, ModuleApi, given}

import java.lang.foreign.Arena

/** Outcome of a formal unit test. */
enum UtOutcome:
  /** The property is proven to hold for every stimulus the assumptions admit. */
  case Pass

  /** The assumptions admit a stimulus that violates the property — the model is that counterexample (variable name ->
    * value).
    */
  case Fail(counterexample: Map[String, BigInt])

  /** The solver could not decide (e.g. returned `unknown`). */
  case Unknown(status: String)

/** A formal unit test over the SMT layer — the formal flavor of the CIRCT-native UT framework: rvprobe's SMT machinery
  * supplies solver-guaranteed stimulus, and the property is the verif-style oracle. No simulation, no `sim` dialect.
  *
  * A test declares symbolic inputs and assumptions (the constrained stimulus / preconditions) and returns the property
  * that should hold. [[FormalUT.check]] asks the solver whether any stimulus satisfying the assumptions can violate the
  * property:
  *   - UNSAT(assumptions ∧ ¬property) ⇒ property proven ⇒ [[UtOutcome.Pass]]
  *   - SAT ⇒ counterexample ⇒ [[UtOutcome.Fail]]
  *
  * This generalizes the sat/unsat pattern the RISC-V spec tests already use into a reusable, DUT-agnostic harness.
  * Wiring a real hardware DUT's transfer function into `spec` (hw -> smt) is the natural next step; today `spec` is
  * expressed directly in the SMT layer.
  */
trait FormalUT:
  def name: String

  /** SMT logic to declare (default linear integer arithmetic). */
  def logic: String = "QF_LIA"

  /** Build the SMT body: declare inputs via `smtValue(name, tpe)`, assert assumptions via `smtAssert`, and return the
    * property that should hold.
    */
  def spec(
    using Arena,
    Context,
    Block
  ): Referable[Bool]

object FormalUT:

  /** Run a formal unit test to a [[UtOutcome]]. */
  def check(ut: FormalUT): UtOutcome =
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
        smtSetLogic(ut.logic)
        val property = ut.spec
        // Search for a stimulus that satisfies the assumptions yet violates the
        // property. None exists ⇒ the property holds.
        smtAssert(!property)
        smtCheck
      }
      val smtlib = {
        val sb = new StringBuilder
        summon[Module].exportSMTLIB(sb ++= _)
        sb.toString()
      }
      // Turn the trailing (reset) into (get-model) so a SAT result yields the
      // counterexample assignment (mirrors the RISC-V solve path).
      val z3in   = smtlib.replace("(reset)", "(get-model)")
      val output = Z3.run(z3in)
      val result = parseZ3Output(output)
      result.status match
        case Z3Status.Unsat   => UtOutcome.Pass
        case Z3Status.Sat     =>
          UtOutcome.Fail(result.model.collect { case (k, v: BigInt) => k -> v }.toMap)
        case Z3Status.Unknown => UtOutcome.Unknown("unknown")
    finally
      context.destroy()
      arena.close()
