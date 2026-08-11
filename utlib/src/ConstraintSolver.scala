// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.smtlib.{Solver, Z3}
import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.parser.{parseZ3Output, Z3Status}
import me.jiuyang.smtlib.tpe.{Bool as SMTBool, Referable}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}

import org.llvm.circt.scalalib.capi.dialect.firrtl.{
  given_DialectApi as given_FirrtlDialectApi,
  DialectApi as FirrtlDialectApi
}
import org.llvm.mlir.scalalib.capi.dialect.func.{DialectApi as FuncDialect, FuncApi, given}
import org.llvm.mlir.scalalib.capi.dialect.smt.{given_DialectApi, DialectApi as SmtDialect}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, LocationApi, Module, ModuleApi, given}
import org.llvm.mlir.scalalib.capi.target.exportsmtlib.given_ExportSmtlibApi

import java.lang.foreign.Arena

private[utlib] object ConstraintSolver:

  /** Result of asking whether the constraints admit a stimulus violating a property. */
  private[utlib] sealed trait RefuteOutcome[I <: HWInterface[?]]
  private[utlib] object RefuteOutcome:
    final case class Refuted[I <: HWInterface[?]](counterexample: SolvedStimulus[I]) extends RefuteOutcome[I]
    final case class Holds[I <: HWInterface[?]]() extends RefuteOutcome[I]
    final case class Undecided[I <: HWInterface[?]](status: String) extends RefuteOutcome[I]

  def solve[PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    dut:           Generator[PARAM, L, I, P] & HasUT[PARAM, I],
    parameter:     PARAM,
    cycles:        Int,
    seed:          Int,
    solverBackend: Solver = Z3
  ): SolvedStimulus[I] =
    val (status, stimulus, smtlib, output) = query(dut, parameter, cycles, seed, solverBackend)(None)
    if status != Z3Status.Sat then
      throw new RuntimeException(
        s"UT constraint solving failed with status $status\n\nSMT-LIB:\n$smtlib\n\nSolver output:\n$output"
      )
    stimulus.get

  /** Ask whether the constrained stimulus space contains a violation of `property`.
    *
    * The check is `constraints ∧ ¬property`: SAT yields the violating stimulus as a
    * counterexample, UNSAT proves the property over every admitted stimulus.
    */
  def refute[PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    dut:           Generator[PARAM, L, I, P] & HasUT[PARAM, I],
    parameter:     PARAM,
    cycles:        Int,
    seed:          Int,
    solverBackend: Solver = Z3
  )(
    property: (Arena, Context, Block, ConstraintInterface[I]) ?=> Referable[SMTBool]
  ): RefuteOutcome[I] =
    val (status, stimulus, _, _) = query(dut, parameter, cycles, seed, solverBackend)(Some(property))
    status match
      case Z3Status.Sat   => RefuteOutcome.Refuted(stimulus.get)
      case Z3Status.Unsat => RefuteOutcome.Holds()
      case other          => RefuteOutcome.Undecided(other.toString)

  /** Build the SMT query — width bounds, the DUT's own constraints, and optionally a negated
    * property — run the backend, and turn a SAT model into a [[SolvedStimulus]].
    */
  private def query[PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[
    PARAM,
    L
  ]](
    dut:           Generator[PARAM, L, I, P] & HasUT[PARAM, I],
    parameter:     PARAM,
    cycles:        Int,
    seed:          Int,
    solverBackend: Solver
  )(
    negated: Option[(Arena, Context, Block, ConstraintInterface[I]) ?=> Referable[SMTBool]]
  ): (Z3Status, Option[SolvedStimulus[I]], String, String) =
    solverBackend.check()
    given arena:   Arena   = Arena.ofConfined()
    given context: Context = summon[ContextApi].contextCreate
    summon[FirrtlDialectApi].loadDialect
    summon[SmtDialect].loadDialect()
    summon[FuncDialect].loadDialect()
    given module:  Module  = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
    val func = summon[FuncApi].op("constraints")
    given block: Block = func.block
    func.appendToModule()

    try
      val constraintInterface      = new ConstraintInterface(dut.interface(parameter), cycles)
      given ConstraintInterface[I] = constraintInterface

      solver {
        smtSetLogic("QF_LIA")
        for
          port  <- constraintInterface.inputPorts
          cycle <- 0 until cycles
        do
          val value = port.at(cycle)
          port.kind match
            case ConstraintInputKind.SInt | ConstraintInputKind.Bits =>
              val limit = 1 << (port.width - 1)
              smtAssert(value >= (-limit).S & value < limit.S)
            case _                                                   =>
              smtAssert(value >= 0.S & value < (1 << port.width).S)
        dut.constraints(parameter)
        negated.foreach(property => smtAssert(!property))
        smtCheck
      }

      val smtlib =
        val out = new StringBuilder
        summon[Module].exportSMTLIB(out ++= _)
        out.toString.replace("(reset)", "(get-model)")
      val output = solverBackend.run(smtlib, seed)
      val result = parseZ3Output(output)

      val stimulus = Option.when(result.status == Z3Status.Sat) {
        val model = result.model.collect { case (name, value: BigInt) => name -> value }.toMap
        SolvedStimulus[I](
          StimulusData(
            dut.moduleName(parameter),
            cycles,
            constraintInterface.inputPorts.map { port =>
              port.name -> Vector.tabulate(cycles)(cycle => model(constraintInterface.variableName(port.name, cycle)))
            }.toMap
          )
        )
      }
      (result.status, stimulus, smtlib, output)
    finally
      context.destroy()
      arena.close()
