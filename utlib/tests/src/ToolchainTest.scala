// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*
import utest.*

object ToolchainTest extends TestSuite:
  val tests: Tests = Tests:
    test("the default backends are Verilator and Z3"):
      assert(Toolchain.simulator == Verilator)
      assert(Toolchain.solver == Z3)
      assert(Toolchain.simulator.name == "verilator")
      assert(Toolchain.solver.name == "z3")

    test("both backends resolve on PATH"):
      Toolchain.check()
      assert(Verilator.available)
      assert(Z3.available)
      assert(os.proc(Verilator.binary, "--version").call().out.text().contains("Verilator"))
      assert(os.proc(Z3.binary, "--version").call().out.text().contains("Z3"))

    test("the registries are the seam a new backend plugs into"):
      // Adding VCS means adding it here; nothing above the Simulator
      // interface changes.
      assert(Toolchain.simulators.keySet == Set("verilator"))
      assert(Toolchain.solvers.keySet == Set("z3"))

    test("a missing tool reports how to fix it"):
      val phantom = new Solver:
        def name:                                                        String = "phantom"
        def envVar:                                                      String = "PHANTOM"
        def binary:                                                      String = "definitely-not-a-real-binary"
        def solve(smtlib: String, seed: Int, timeoutMillis: Int = 5000): String = ""
      assert(!phantom.available)
      val message =
        try { phantom.check(); "" }
        catch case e: RuntimeException => e.getMessage
      assert(message.contains("nix develop"))
      assert(message.contains("PHANTOM"))
