// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.rvprobe.frontend.PortDir
import me.jiuyang.utlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import utest.*

import java.lang.foreign.Arena

/** The framework's headline example: a unit test written entirely as constraints and coverage goals. Nowhere does it
  * say which value is enqueued on which cycle — the solver decides.
  */
object FifoUnitTest extends TestSuite:

  private val fifoIface = DutInterface(
    dutName = "Fifo",
    ports = Seq(
      PortSpec("enq", PortDir.Drive, 8),
      PortSpec("deq", PortDir.Monitor, 8)
    ),
    status = Seq("empty", "full")
  )

  /** Fill the FIFO, then drain it. */
  object FillThenDrain extends UnitTest:
    def iface:  DutInterface = fifoIface
    def width:  Int          = 8
    def cycles: Int          = 8

    def coverpoints: Seq[Coverpoint] = Seq(
      Coverpoint("cover_enq_fire", "an enqueue handshake completed"),
      Coverpoint("cover_deq_fire", "a dequeue handshake completed"),
      Coverpoint("cover_full", "the FIFO reached full"),
      Coverpoint("cover_empty", "the FIFO reached empty")
    )

    def constraints(): (Arena, Context, Block, TxnRecipe) ?=> Unit =
      // Push harder than the FIFO can accept, with distinct payloads so a
      // reordering bug would be observable, then drain it.
      mustEnqueue(0 until 4, "enq")
      payloadIn(0 until 4, "enq", BigInt(1), BigInt(200))
      distinctPayloads(0 until 4, "enq")
      mustIdle(0 until 4, "deq")
      mustIdle(4 until 8, "enq")
      mustDequeue(4 until 8, "deq")

  /** Back-pressure: keep the FIFO full and keep offering. */
  object Backpressure extends UnitTest:
    def iface:  DutInterface = fifoIface
    def width:  Int          = 8
    def cycles: Int          = 6

    def coverpoints: Seq[Coverpoint] = Seq(
      Coverpoint("cover_full", "the FIFO reached full"),
      Coverpoint("cover_full_enq", "an enqueue was offered while full")
    )

    def constraints(): (Arena, Context, Block, TxnRecipe) ?=> Unit =
      mustEnqueue(0 until 6, "enq")
      mustIdle(0 until 6, "deq")

  val tests: Tests = Tests:
    test("the solver honours the declared constraints"):
      val stimulus = FillThenDrain.solve()
      val enqueues = (0 until 4).flatMap(c => stimulus.at(c, "enq"))
      assert(enqueues.size == 4)
      assert(enqueues.forall(_.kind == TxnKind.Enqueue))
      assert(enqueues.map(_.payload).distinct.size == 4)
      assert(enqueues.map(_.payload).forall(p => p >= BigInt(1) && p <= BigInt(200)))
      val dequeues = (4 until 8).flatMap(c => stimulus.at(c, "deq"))
      assert(dequeues.forall(_.kind == TxnKind.Dequeue))

    test("fill-then-drain reaches every declared coverpoint"):
      val result = FillThenDrain.run(os.temp.dir(prefix = "fill-then-drain"))
      FillThenDrain.requireCoverage(result)
      assert(result.coverage.rate(FillThenDrain.coverpoints) == 1.0)

    test("back-pressure reaches every declared coverpoint"):
      val result = Backpressure.run(os.temp.dir(prefix = "backpressure"))
      Backpressure.requireCoverage(result)
      assert(result.coverage.hits("cover_full_enq") > 0)

    test("changing the seed re-solves to a valid stimulus"):
      // Same declaration, different search path: still satisfies the
      // constraints. This is what makes a constraint reusable where a
      // hand-written poke sequence is not.
      object Reseeded extends UnitTest:
        def iface:         DutInterface                                = FillThenDrain.iface
        def width:         Int                                         = FillThenDrain.width
        def cycles:        Int                                         = FillThenDrain.cycles
        def coverpoints:   Seq[Coverpoint]                             = FillThenDrain.coverpoints
        def constraints(): (Arena, Context, Block, TxnRecipe) ?=> Unit = FillThenDrain.constraints()
        override val seed: Int                                         = 12345

      val stimulus = Reseeded.solve()
      val enqueues = (0 until 4).flatMap(c => stimulus.at(c, "enq"))
      assert(enqueues.forall(_.kind == TxnKind.Enqueue))
      assert(enqueues.map(_.payload).distinct.size == 4)
