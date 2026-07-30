// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.Toolchain
import utest.*

object ToolchainTest extends TestSuite:
  val tests: Tests = Tests:
    test("verilator and z3 are on PATH"):
      Toolchain.check()
      val version   = os.proc(Toolchain.verilator, "--version").call().out.text()
      assert(version.contains("Verilator"))
      val z3Version = os.proc(Toolchain.z3, "--version").call().out.text()
      assert(z3Version.contains("Z3"))
