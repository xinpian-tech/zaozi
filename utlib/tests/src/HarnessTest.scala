// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*
import utest.*

object HarnessTest extends TestSuite:
  val tests: Tests = Tests:
    test("the harness instantiates the DUT and drives the solved payloads"):
      val verilog = FifoHarness.verilogString(HarnessFixture.parameter)
      // The solved payloads are baked in as constants.
      assert(verilog.contains("8'hB"))  // 11
      assert(verilog.contains("8'h16")) // 22
      // The DUT is instantiated, not inlined away.
      assert(verilog.contains("Fifo"))

    test("coverpoints become SystemVerilog cover properties"):
      val verilog = FifoHarness.verilogString(HarnessFixture.parameter)
      assert(verilog.contains("cover property"))
      HarnessFixture.coverpoints.foreach(p => assert(verilog.contains(p.name)))

    test("the harness exposes a done signal"):
      val verilog = FifoHarness.verilogString(HarnessFixture.parameter)
      assert(verilog.contains("done"))

    test("an unknown coverpoint name fails elaboration"):
      val bad   = HarnessFixture.parameter.copy(
        coverpoints = Seq(Coverpoint("cover_nonsense", "not a thing the harness knows"))
      )
      val threw =
        try
          FifoHarness.verilogString(bad)
          false
        catch case e: Throwable => e.getMessage != null && e.getMessage.contains("cover_nonsense")
      assert(threw)
