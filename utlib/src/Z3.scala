// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

/** The Z3 solver backend. */
object Z3 extends Solver:
  def name:   String = "z3"
  def envVar: String = "Z3"
  def binary: String = sys.env.getOrElse(envVar, "z3")

  def solve(smtlib: String, seed: Int, timeoutMillis: Int = 5000): String =
    os.proc(binary, "-in", s"-t:$timeoutMillis").call(stdin = seeded(smtlib, seed), check = false).out.text()

  /** Z3's seeding is expressed as options that must precede `(set-logic …)`. The syntax is Z3-specific, which is why it
    * lives here rather than in the caller.
    */
  private def seeded(smtlib: String, seed: Int): String =
    val options = Seq(
      s"(set-option :smt.random_seed $seed)",
      s"(set-option :sat.random_seed $seed)"
    ).mkString("\n")
    smtlib.replaceFirst("""\(set-logic """, s"$options\n(set-logic ")
