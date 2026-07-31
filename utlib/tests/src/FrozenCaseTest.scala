// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib.tests

import me.jiuyang.rvprobe.frontend.PortDir
import me.jiuyang.utlib.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import utest.*

import java.lang.foreign.Arena

/** Freezing a generated case: solve once, persist the stimulus as JSON, and
  * replay it without re-solving. A frozen case is byte-for-byte reproducible and
  * runs the same coverage — a fast, solver- and seed-independent regression that
  * pins down a sequence the solver happened to find. */
object FrozenCaseTest extends TestSuite:

  private val fifoIface = DutInterface(
    dutName = "Fifo",
    ports = Seq(
      PortSpec("enq", PortDir.Drive, 8),
      PortSpec("deq", PortDir.Monitor, 8)
    ),
    status = Seq("empty", "full")
  )

  object FillThenDrain extends UnitTest:
    def iface:  DutInterface = fifoIface
    def cycles: Int          = 8

    def coverpoints: Seq[Coverpoint] = Seq(
      Coverpoint("cover_enq_fire", "an enqueue handshake completed"),
      Coverpoint("cover_deq_fire", "a dequeue handshake completed"),
      Coverpoint("cover_full", "the FIFO reported full"),
      Coverpoint("cover_empty", "the FIFO reported empty")
    )

    def constraints(): (Arena, Context, Block, TxnRecipe) ?=> Unit =
      mustEnqueue(0 until 4, "enq")
      mustIdle(0 until 4, "deq")
      mustIdle(4 until 8, "enq")
      mustDequeue(4 until 8, "deq")

  val tests: Tests = Tests:

    test("freeze then reload yields the identical stimulus"):
      val caseFile = os.temp.dir(prefix = "frozen") / "fill-then-drain.case.json"
      val frozen   = FillThenDrain.freeze(caseFile)
      assert(os.exists(caseFile))
      val reloaded = UnitTest.loadStimulus(caseFile)
      assert(reloaded == frozen) // byte-for-byte round-trip through JSON
      assert(reloaded.dut == "Fifo" && reloaded.cycles == 8)

    test("replaying a frozen case reproduces the coverage without re-solving"):
      val dir      = os.temp.dir(prefix = "frozen-replay")
      val caseFile = dir / "fill-then-drain.case.json"
      FillThenDrain.freeze(caseFile)
      val stimulus = UnitTest.loadStimulus(caseFile)
      val result   = FillThenDrain.runStimulus(stimulus, dir) // no solver runs here
      FillThenDrain.requireCoverage(result)
      assert(result.coverage.rate(FillThenDrain.coverpoints) == 1.0)
