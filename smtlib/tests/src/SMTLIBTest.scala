// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.smtlib.tests

import me.jiuyang.smtlib.{*, given}
import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.parser.{parseZ3Output, Z3Result}

import org.llvm.mlir.scalalib.capi.dialect.func.{Func, FuncApi, given}
import org.llvm.mlir.scalalib.capi.dialect.smt.DialectApi as SmtDialect
import org.llvm.mlir.scalalib.capi.dialect.smt.given_DialectApi
import org.llvm.mlir.scalalib.capi.dialect.func.DialectApi as FuncDialect
import org.llvm.mlir.scalalib.capi.dialect.func.given_DialectApi
import org.llvm.mlir.scalalib.capi.target.exportsmtlib.given_ExportSmtlibApi
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, LocationApi, Module, ModuleApi, Value, given}

import java.lang.foreign.Arena

def smtTest(checkLines: String*)(body: (Arena, Context, Block) ?=> Unit): Unit =
  val arena = Arena.ofConfined()
  try
    given Arena   = arena
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

    // check the output
    if (checkLines.isEmpty)
      assert(out.toString == "Nothing To Check")
    else checkLines.foreach(l => assert(out.toString.contains(l)))
  finally arena.close()

def smtZ3Test(checkLines: String*)(body: (Arena, Context, Block) ?=> Unit): Z3Result =
  val arena = Arena.ofConfined()
  try
    given Arena   = arena
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
  finally arena.close()
