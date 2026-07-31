// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*
import utest.*

object TraceTest extends TestSuite:
  val tests: Tests = Tests:
    test("a traced run writes a VCD containing the DUT's handshake signals"):
      val dir    = os.temp.dir(prefix = "utlib-trace")
      val result = VerilatorRunner.run(HarnessFixture.parameter, dir, trace = true)
      assert(result.exitCode == 0)
      assert(result.tracePath.isDefined)

      val vcd = os.read(result.tracePath.get)
      assert(vcd.startsWith("$date") || vcd.contains("$version"))
      assert(vcd.contains("$enddefinitions"))
      // The signals an engineer actually looks for when a coverpoint misses.
      assert(vcd.contains("clock"))
      assert(vcd.contains("enq_valid"))
      assert(vcd.contains("enq_bits"))
      assert(vcd.contains("deq_ready"))
      assert(vcd.contains("full"))
      assert(vcd.contains("empty"))

    test("tracing is off by default and costs nothing"):
      val dir    = os.temp.dir(prefix = "utlib-notrace")
      val result = VerilatorRunner.run(HarnessFixture.parameter, dir)
      assert(result.exitCode == 0)
      assert(result.tracePath.isEmpty)
      assert(!os.exists(dir / Harness.traceFileName))
