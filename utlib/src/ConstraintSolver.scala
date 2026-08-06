// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.smtlib.{Solver, Z3}
import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.parser.{parseZ3Output, Z3Status}
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
  def solve[PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    dut:           Generator[PARAM, L, I, P] & HasUT[PARAM, I],
    parameter:     PARAM,
    cycles:        Int,
    seed:          Int,
    solverBackend: Solver = Z3
  ): SolvedStimulus[I] =
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
        smtCheck
      }

      val smtlib =
        val out = new StringBuilder
        summon[Module].exportSMTLIB(out ++= _)
        out.toString.replace("(reset)", "(get-model)")
      val output = solverBackend.run(smtlib, seed)
      val result = parseZ3Output(output)
      if result.status != Z3Status.Sat then
        throw new RuntimeException(
          s"UT constraint solving failed with status ${result.status}\n\nSMT-LIB:\n$smtlib\n\nSolver output:\n$output"
        )

      val model = result.model.collect { case (name, value: BigInt) => name -> value }.toMap
      SolvedStimulus(
        StimulusData(
          dut.moduleName(parameter),
          cycles,
          constraintInterface.inputPorts.map { port =>
            port.name -> Vector.tabulate(cycles)(cycle => model(constraintInterface.variableName(port.name, cycle)))
          }.toMap
        )
      )
    finally
      context.destroy()
      arena.close()
