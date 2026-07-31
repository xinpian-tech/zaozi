// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*
import utest.*

object TxnTraceTest extends TestSuite:
  private val traced = HarnessFixture.parameter.copy(txnTrace = true)

  private def field(line: String, name: String): Option[String] =
    raw"$name=\s*(\d+)".r.findFirstMatchIn(line).map(_.group(1))

  val tests: Tests = Tests:
    test("the trace is emitted during elaboration, not injected afterwards"):
      // Written in the DSL over typed signals. firtool lowers firrtl.printf
      // straight to sv.fwrite — it does *not* route through the sim dialect,
      // which is why the only sim op here is the injected terminate.
      val hw = FifoHarness.hwString(traced)
      assert(hw.contains("sv.fwrite"))
      assert(hw.contains("[txn]"))
      assert(hw.contains("sim.clocked_terminate"))
      assert(!hw.contains("sim.print"))

    test("the trace prints both DUT ports and white-box probe signals"):
      val dir    = os.temp.dir(prefix = "utlib-txntrace")
      val result = Simulation.run(traced, dir)
      assert(result.exitCode == 0)
      val lines  = result.traceLines
      assert(lines.nonEmpty)
      // Port-level values.
      assert(lines.forall(l => field(l, "enq_valid").isDefined))
      assert(lines.forall(l => field(l, "full").isDefined))
      // White-box values, which no port exposes.
      assert(lines.forall(l => field(l, "probe_accepted").isDefined))
      assert(lines.forall(l => field(l, "probe_released").isDefined))
      // The fixture enqueues 11 then 22 back to back, filling the depth-2 FIFO.
      assert(lines.exists(l => field(l, "enq_bits").contains("11")))
      assert(lines.exists(l => field(l, "enq_bits").contains("22")))
      assert(lines.exists(l => field(l, "full").contains("1")))
      assert(lines.exists(l => field(l, "probe_accepted").contains("1")))

    test("the trace is off by default"):
      val hw = FifoHarness.hwString(HarnessFixture.parameter)
      assert(!hw.contains("sv.fwrite"))
      assert(!hw.contains("[txn]"))
      // Self-termination is injected either way.
      assert(hw.contains("sim.clocked_terminate"))
