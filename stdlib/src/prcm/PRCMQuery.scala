// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import java.lang.foreign.Arena

import me.jiuyang.smtlib.default.{*, given}
import org.llvm.mlir.scalalib.capi.dialect.smt.{given_DialectApi, DialectApi as SMT}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.target.exportsmtlib.given

/** An SMT-LIB query and the status z3 must report for it. */
case class PRCMQuery(name: String, expected: Boolean, smtlib: String):
  def status: String = if expected then "sat" else "unsat"

  /** The query with its expected status as a FileCheck line for `z3 <file> | FileCheck <file>`. */
  def file: String = s"; EXPECT: {{^}}$status{{$$}}\n$smtlib"

object PRCMQuery:
  def sat(name:   String, smtlib: String): PRCMQuery = PRCMQuery(name, true, smtlib)
  def unsat(name: String, smtlib: String): PRCMQuery = PRCMQuery(name, false, smtlib)

  /** Exports the SMT-LIB text of `module`, followed by a satisfiability check. */
  def render(
    module: Module
  )(
    using Arena
  ): String =
    val output = new StringBuilder
    module.exportSMTLIB(output ++= _, false)
    output.toString + "(check-sat)\n"

  /** Builds a query with the smtlib eDSL in a fresh context. */
  def build(body: (Arena, Context, Block) ?=> Unit): String =
    val arena = Arena.ofConfined()
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      try
        summon[SMT].loadDialect()
        val module = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
        try
          given Block = module.getBody
          solver {
            body
            smtYield()
          }
          render(module)
        finally module.destroy()
      finally summon[Context].destroy()
    finally arena.close()

  /** Writes each query and a manifest of expected statuses. */
  def write(directory: os.Path, queries: Seq[PRCMQuery]): ujson.Value =
    require(queries.map(_.name).distinct.size == queries.size, "query names must be unique")
    os.makeDir.all(directory)
    queries.foreach(query => os.write.over(directory / s"${query.name}.smt2", query.file))
    ujson.Arr.from(queries.map(query => ujson.Obj("name" -> query.name, "expected" -> query.status)))
