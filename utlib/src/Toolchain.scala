// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

/** External binaries the framework shells out to.
  *
  * Both are provided by the zaozi dev shell (`nix develop .`). They are looked up on `PATH` rather than pinned to store
  * paths so that a developer can override either with a local build.
  */
object Toolchain:
  /** The Verilator driver used to build and run generated testbenches. */
  val verilator: String = sys.env.getOrElse("VERILATOR", "verilator")

  /** The Z3 binary used to solve exported SMT-LIB. */
  val z3: String = sys.env.getOrElse("Z3", "z3")

  /** Fail early, with a remediation hint, when a required binary is missing. */
  def check(): Unit =
    Seq(verilator -> "verilator", z3 -> "z3").foreach { case (binary, name) =>
      val found =
        try os.proc("which", binary).call(check = false).exitCode == 0
        catch case _: Throwable => false
      if !found then
        throw new RuntimeException(
          s"$name not found on PATH (looked for `$binary`). " +
            "Enter the dev shell with `nix develop .` from the zaozi root, " +
            s"or set the ${name.toUpperCase} environment variable to an absolute path."
        )
    }
