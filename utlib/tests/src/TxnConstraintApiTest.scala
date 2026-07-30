// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.rvprobe.frontend.PortDir
import me.jiuyang.utlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import utest.*

import java.lang.foreign.Arena

object TxnConstraintApiTest extends TestSuite:
  private val queueIface = DutInterface(
    dutName = "Queue",
    ports = Seq(
      PortSpec("enq", PortDir.Drive, 8),
      PortSpec("deq", PortDir.Monitor, 8)
    ),
    status = Seq("empty", "full")
  )

  private def solveWith(
    n:    Int
  )(body: (Arena, Context, Block, TxnRecipe) ?=> Unit
  ): SolvedStimulus =
    val solver = new TxnSolver:
      def iface:         DutInterface                                = queueIface
      def cycles:        Int                                         = n
      def constraints(): (Arena, Context, Block, TxnRecipe) ?=> Unit = body
    solver.solve()

  val tests: Tests = Tests:
    test("mustEnqueue pins every named cycle"):
      val stimulus = solveWith(4)(mustEnqueue(0 until 4, "enq"))
      assert(stimulus.txns.filter(_.port == "enq").forall(_.kind == TxnKind.Enqueue))

    test("mustIdle pins every named cycle"):
      val stimulus = solveWith(4)(mustIdle(0 until 4, "deq"))
      assert(stimulus.txns.filter(_.port == "deq").forall(_.kind == TxnKind.Idle))

    test("mustDequeue pins every named cycle"):
      val stimulus = solveWith(4)(mustDequeue(0 until 4, "deq"))
      assert(stimulus.txns.filter(_.port == "deq").forall(_.kind == TxnKind.Dequeue))

    test("payloadIn bounds the solved payloads"):
      val stimulus = solveWith(3) {
        mustEnqueue(0 until 3, "enq")
        payloadIn(0 until 3, "enq", BigInt(100), BigInt(120))
      }
      val values   = stimulus.txns.filter(_.port == "enq").map(_.payload)
      assert(values.forall(v => v >= BigInt(100) && v <= BigInt(120)))

    test("distinctPayloads forbids repeats"):
      val stimulus = solveWith(4) {
        mustEnqueue(0 until 4, "enq")
        payloadIn(0 until 4, "enq", BigInt(0), BigInt(5))
        distinctPayloads(0 until 4, "enq")
      }
      val values   = stimulus.txns.filter(_.port == "enq").map(_.payload)
      assert(values.distinct.size == values.size)

    test("atLeast forces a minimum number of transactions"):
      val stimulus = solveWith(6)(atLeast(4, TxnKind.Dequeue, "deq"))
      assert(stimulus.txns.count(t => t.port == "deq" && t.kind == TxnKind.Dequeue) >= 4)

    test("backToBack forces a consecutive run"):
      val stimulus = solveWith(6)(backToBack("enq", TxnKind.Enqueue, 3))
      val kinds    = (0 until 6).map(c => stimulus.at(c, "enq").map(_.kind))
      assert(kinds.sliding(3).exists(_.forall(_.contains(TxnKind.Enqueue))))
