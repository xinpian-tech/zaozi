// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.smtlib

import me.jiuyang.smtlib.parser.{parseZ3Output, Z3Result}

/** An external SMT solver. */
trait Solver:
  def name:   String
  def binary: String
  def envVar: String

  final def available: Boolean =
    try os.proc("which", binary).call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0
    catch case _: Throwable => false

  final def check(): Unit =
    if !available then
      throw new RuntimeException(
        s"$name not found on PATH (looked for `$binary`). " +
          "Enter the dev shell with `nix develop .` from the zaozi root, " +
          s"or set $envVar to an absolute path."
      )

  /** Run one SMT-LIB program and return the solver's raw stdout. */
  def run(smtlib: String, seed: Int = 0, timeoutMillis: Int = 5000): String

  /** Run and parse a Z3-compatible result. */
  final def solve(smtlib: String, seed: Int = 0, timeoutMillis: Int = 5000): Z3Result =
    parseZ3Output(run(smtlib, seed, timeoutMillis))

/** The Z3 solver backend. */
object Z3 extends Solver:
  def name:   String = "z3"
  def envVar: String = "Z3"
  def binary: String = sys.env.getOrElse(envVar, "z3")

  def run(smtlib: String, seed: Int = 0, timeoutMillis: Int = 5000): String =
    os.proc(binary, "-in", s"-t:$timeoutMillis").call(stdin = seeded(smtlib, seed), check = false).out.text()

  private def seeded(smtlib: String, seed: Int): String =
    val options = Seq(
      s"(set-option :smt.random_seed $seed)",
      s"(set-option :sat.random_seed $seed)"
    ).mkString("\n")
    smtlib.replaceFirst("""\(set-logic """, s"$options\n(set-logic ")
