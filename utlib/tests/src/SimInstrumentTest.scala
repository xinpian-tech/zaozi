// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*
import utest.*

object SimInstrumentTest extends TestSuite:
  val tests: Tests = Tests:
    test("the instrumented harness carries a sim.clocked_terminate at HW level"):
      val hw           = FifoHarness.hwString(HarnessFixture.parameter)
      assert(hw.contains("sim.clocked_terminate"))
      // It must sit inside the harness module, before its hw.output.
      val harnessIdx   = hw.indexOf(s"hw.module @${FifoHarness.moduleName(HarnessFixture.parameter)}")
      val terminateIdx = hw.indexOf("sim.clocked_terminate")
      assert(harnessIdx >= 0 && terminateIdx > harnessIdx)

    test("the injected terminate reaches SystemVerilog as a finish"):
      val sv = FifoHarness.verilogString(HarnessFixture.parameter)
      assert(sv.contains("$finish"))
      assert(!sv.contains("sim."))
