// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib.tests

import me.jiuyang.rvprobe.frontend.PortDir
import me.jiuyang.utlib.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import utest.*

import java.lang.foreign.Arena

/** Concurrent enqueue+dequeue — the FIFO's trickiest path (the shift-and-land
  * logic when a handshake completes on both sides in the same cycle) and the
  * white-box coverpoints the headline scenarios never reach.
  *
  * `FifoUnitTest` separates the enqueue and dequeue phases, so `cover_simultaneous`
  * and the probe-level `cover_probe_pass_through` stay dark. This drives both
  * ports together and validates, end to end through Verilator, that those
  * coverpoints are hit — expressed purely with the existing constraint API.
  */
object FifoPassThroughTest extends TestSuite:

  private val fifoIface = DutInterface(
    dutName = "Fifo",
    ports = Seq(
      PortSpec("enq", PortDir.Drive, 8),
      PortSpec("deq", PortDir.Monitor, 8)
    ),
    status = Seq("empty", "full")
  )

  /** Fill both slots, then offer an enqueue and a dequeue every cycle. Once a
    * dequeue frees a slot the enqueue lands the same cycle, so both handshakes
    * complete together — exercising the concurrent shift-and-land path. */
  object PassThrough extends UnitTest:
    def iface:  DutInterface = fifoIface
    def cycles: Int          = 8

    def coverpoints: Seq[Coverpoint] = Seq(
      Coverpoint("cover_simultaneous", "an enqueue and a dequeue completed in the same cycle"),
      Coverpoint("cover_probe_pass_through", "an enqueue and a dequeue in the same cycle (white-box)"),
      Coverpoint("cover_probe_both_slots", "both internal slots occupied (white-box)"),
      Coverpoint("cover_probe_head_only", "only the head slot occupied (white-box)")
    )

    def constraints(): (Arena, Context, Block, TxnRecipe) ?=> Unit =
      // Prime: fill both slots.
      mustEnqueue(0 until 2, "enq")
      mustIdle(0 until 2, "deq")
      // Steady state: offer both every cycle; the FIFO fires them together.
      mustEnqueue(2 until 8, "enq")
      mustDequeue(2 until 8, "deq")

  val tests: Tests = Tests:

    test("concurrent enqueue+dequeue reaches the simultaneous and white-box coverpoints"):
      val result = PassThrough.run(os.temp.dir(prefix = "pass-through"))
      PassThrough.requireCoverage(result)
      assert(result.coverage.rate(PassThrough.coverpoints) == 1.0)
      assert(result.coverage.hit("cover_simultaneous"))
      assert(result.coverage.hit("cover_probe_pass_through"))
