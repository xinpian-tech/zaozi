// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*
import utest.*

object TxnTraceTest extends TestSuite:
  private val traced = HarnessFixture.parameter.copy(txnTrace = true)

  /** `sim.fmt.dec` left-pads to the width of the value's type, so a field reads `enq_bits= 11`. That is deliberate —
    * the columns line up, which is what makes the log readable — so assertions here are whitespace-tolerant.
    */
  private def field(line: String, name: String): Option[String] =
    raw"$name=\s*(\d+)".r.findFirstMatchIn(line).map(_.group(1))

  val tests: Tests = Tests:
    test("the trace is injected as procedural sim ops at HW level"):
      val hw = FifoHarness.hwString(traced)
      // Procedural form: lower-sim-to-sv has no patterns for a bare sim.print.
      assert(hw.contains("sim.triggered"))
      assert(hw.contains("sim.proc.print"))
      assert(hw.contains("sim.fmt.concat"))
      assert(hw.contains("sim.fmt.dec"))
      assert(hw.contains("[txn]"))

    test("the trace prints the solved transactions during simulation"):
      val dir    = os.temp.dir(prefix = "utlib-txntrace")
      val result = VerilatorRunner.run(traced, dir)
      assert(result.exitCode == 0)
      val lines  = result.stdout.linesIterator.filter(_.contains("[txn]")).toSeq
      assert(lines.nonEmpty)
      assert(lines.forall(l => field(l, "enq_valid").isDefined))
      assert(lines.forall(l => field(l, "full").isDefined))
      // The fixture enqueues 11 then 22 back to back...
      assert(lines.exists(l => field(l, "enq_bits").contains("11")))
      assert(lines.exists(l => field(l, "enq_bits").contains("22")))
      // ...which fills the depth-2 FIFO, and the drain empties it again.
      assert(lines.exists(l => field(l, "full").contains("1")))
      assert(lines.exists(l => field(l, "empty").contains("1")))
      // Reset cycles are filtered out, so every line is real stimulus.
      assert(lines.size == HarnessFixture.parameter.stimulus.cycles + 1)

    test("the trace is off by default"):
      val hw = FifoHarness.hwString(HarnessFixture.parameter)
      assert(!hw.contains("sim.proc.print"))
      assert(!hw.contains("sim.triggered"))
      // Self-termination is still injected either way.
      assert(hw.contains("sim.clocked_terminate"))
