// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.smtlib.tests

import me.jiuyang.smtlib.{*, given}
import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.parser.{parseZ3Output, parseZ3OutputOrFail, getUnsatCore, handleUnsatResult, Z3Result}

import org.llvm.mlir.scalalib.capi.dialect.func.{Func, FuncApi, given}
import org.llvm.mlir.scalalib.capi.dialect.smt.DialectApi as SmtDialect
import org.llvm.mlir.scalalib.capi.dialect.smt.given_DialectApi
import org.llvm.mlir.scalalib.capi.dialect.func.DialectApi as FuncDialect
import org.llvm.mlir.scalalib.capi.dialect.func.given_DialectApi
import org.llvm.mlir.scalalib.capi.target.exportsmtlib.given_ExportSmtlibApi
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, LocationApi, Module, ModuleApi, Value, given}

import java.lang.foreign.Arena

def smtTest(checkLines: String*)(body: (Arena, Context, Block) ?=> Unit): Unit =
  given Arena   = Arena.ofConfined()
  given Context = summon[ContextApi].contextCreate
  summon[SmtDialect].loadDialect()
  summon[FuncDialect].loadDialect()

  // Then based on the module to construct the Func.func .
  given Module = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
  given Func   = summon[FuncApi].op("func")
  given Block  = summon[Func].block
  summon[Func].appendToModule()

  body

  // dump mlir
  val out = new StringBuilder
  summon[Module].exportSMTLIB(out ++= _)
  summon[Context].destroy()
  summon[Arena].close()

  // check the output
  if (checkLines.isEmpty)
    assert(out.toString == "Nothing To Check")
  else checkLines.foreach(l => assert(out.toString.contains(l)))

def smtZ3Test(checkLines: String*)(body: (Arena, Context, Block) ?=> Unit): Z3Result =
  given Arena   = Arena.ofConfined()
  given Context = summon[ContextApi].contextCreate
  summon[SmtDialect].loadDialect()
  summon[FuncDialect].loadDialect()

  // Then based on the module to construct the Func.func .
  given Module = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
  given Func   = summon[FuncApi].op("func")
  given Block  = summon[Func].block
  summon[Func].appendToModule()

  solver {
    body
    smtCheck
  }

  val out = new StringBuilder
  summon[Module].exportSMTLIB(out ++= _)
  summon[Context].destroy()
  summon[Arena].close()

  // check the output
  if (checkLines.isEmpty)
    assert(out.toString == "Nothing To Check")
  else checkLines.foreach(l => assert(out.toString.contains(l)))

  val smt = out.toString.replace("(reset)", "(get-model)")

  val z3Output = os
    .proc("z3", "-in", "-t:1000")
    .call(
      stdin = smt,
      check = false // ignore the error message when `unknown` or `unsat`
    )

  parseZ3Output(z3Output.out.text())

/** Test helper for UNSAT scenarios
  *
  * Creates a Z3 test that is expected to be UNSAT and tests the unsat core generation.
  * Unlike smtZ3Test, this doesn't fail on UNSAT - it expects it.
  *
  * @param body The SMT constraints to test (should be unsatisfiable)
  * @return Z3Result (expected to have status = Unsat)
  */
def smtZ3UnsatTest(body: (Arena, Context, Block) ?=> Unit): Z3Result =
  given Arena   = Arena.ofConfined()
  given Context = summon[ContextApi].contextCreate
  summon[SmtDialect].loadDialect()
  summon[FuncDialect].loadDialect()

  given Module = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
  given Func   = summon[FuncApi].op("func")
  given Block  = summon[Func].block
  summon[Func].appendToModule()

  solver {
    body
    smtCheck
  }

  val out = new StringBuilder
  summon[Module].exportSMTLIB(out ++= _)
  val smt = out.toString

  summon[Context].destroy()
  summon[Arena].close()

  // Z3 runner function for getting unsat core
  def runZ3(smtlib: String): String =
    os.proc("z3", "-in", "-t:1000")
      .call(stdin = smtlib, check = false)
      .out.text()

  // For UNSAT tests, just check satisfiability without requesting model
  val z3Output = runZ3(smt.replace("(reset)", ""))
  val result   = parseZ3Output(z3Output)

  // If UNSAT, also test that we can get the unsat core
  if (result.status == me.jiuyang.smtlib.parser.Z3Status.Unsat) {
    val unsatCore = getUnsatCore(smt, runZ3)
    // Just verify we got something back (not empty)
    assert(unsatCore.nonEmpty, "Unsat core should not be empty")
  }

  result
