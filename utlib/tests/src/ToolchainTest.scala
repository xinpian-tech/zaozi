// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*
import utest.*

object ToolchainTest extends TestSuite:
  val tests: Tests = Tests:
    test("the default simulator resolves"):
      assert(Toolchain.simulator == Verilator)
      assert(Toolchain.simulator.name == "verilator")

    test("the simulator resolves on PATH"):
      Toolchain.check()
      assert(Verilator.available)
      assert(os.proc(Verilator.binary, "--version").call().out.text().contains("Verilator"))

    test("the registries are the seam a new backend plugs into"):
      // Adding VCS means adding it here; nothing above the Simulator
      // interface changes.
      assert(Toolchain.simulators.keySet == Set("verilator"))
