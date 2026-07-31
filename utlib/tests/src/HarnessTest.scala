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

    test("the catalogue covers both DUT ports and white-box probe signals"):
      val verilog = FifoHarness.verilogString(HarnessFixture.parameter)
      // Port-bound coverpoints.
      assert(verilog.contains("cover_enq_fire"))
      assert(verilog.contains("cover_full"))
      // Probe-bound coverpoints, on signals no port exposes.
      assert(verilog.contains("cover_probe_both_slots"))
      assert(verilog.contains("cover_probe_pass_through"))
      // The probe path goes through a layer bind, not through the DUT's IO.
      assert(verilog.contains("bind Fifo"))
