// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.rvprobe.frontend.PortDir
import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.tpe.*
import me.jiuyang.utlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import utest.*

import java.lang.foreign.Arena

object TxnSolverTest extends TestSuite:
  private val queueIface = DutInterface(
    dutName = "Queue",
    ports = Seq(
      PortSpec("enq", PortDir.Drive, 8),
      PortSpec("deq", PortDir.Monitor, 8)
    ),
    status = Seq("empty", "full")
  )

  val tests: Tests = Tests:
    test("an unconstrained solve produces one transaction per cycle per port"):
      val solver = new TxnSolver:
        def iface:         DutInterface                                = queueIface
        def cycles:        Int                                         = 4
        def constraints(): (Arena, Context, Block, TxnRecipe) ?=> Unit = ()

      val stimulus = solver.solve()
      assert(stimulus.dut == "Queue")
      assert(stimulus.cycles == 4)
      assert(stimulus.txns.size == 8)
      assert(stimulus.txns.forall(t => t.cycle >= 0 && t.cycle < 4))

    test("a kind constraint pins the transaction kind"):
      val solver = new TxnSolver:
        def iface:         DutInterface                                = queueIface
        def cycles:        Int                                         = 3
        def constraints(): (Arena, Context, Block, TxnRecipe) ?=> Unit =
          summon[TxnRecipe].addKindConstraint { r =>
            (0 until r.cycles).foreach { c =>
              smtAssert(r.kind(c, "enq") === TxnKind.Enqueue.id.S)
            }
          }

      val stimulus = solver.solve()
      val enqueues = stimulus.txns.filter(_.port == "enq")
      assert(enqueues.size == 3)
      assert(enqueues.forall(_.kind == TxnKind.Enqueue))

    test("a payload constraint bounds the solved values"):
      val solver = new TxnSolver:
        def iface:         DutInterface                                = queueIface
        def cycles:        Int                                         = 3
        def constraints(): (Arena, Context, Block, TxnRecipe) ?=> Unit =
          val recipe = summon[TxnRecipe]
          recipe.addKindConstraint { r =>
            (0 until r.cycles).foreach { c =>
              smtAssert(r.kind(c, "enq") === TxnKind.Enqueue.id.S)
            }
          }
          recipe.addPayloadConstraint { r =>
            (0 until r.cycles).foreach { c =>
              val p = r.payload(c, "enq")
              smtAssert(p >= 10.S & p <= 20.S)
            }
          }

      val stimulus = solver.solve()
      val payloads = stimulus.txns.filter(_.port == "enq").map(_.payload)
      assert(payloads.forall(p => p >= BigInt(10) && p <= BigInt(20)))

    test("a Monitor port is never solved as an enqueue"):
      val solver = new TxnSolver:
        def iface:         DutInterface                                = queueIface
        def cycles:        Int                                         = 4
        def constraints(): (Arena, Context, Block, TxnRecipe) ?=> Unit = ()

      val stimulus = solver.solve()
      assert(stimulus.txns.filter(_.port == "deq").forall(_.kind != TxnKind.Enqueue))
      assert(stimulus.txns.filter(_.port == "enq").forall(_.kind != TxnKind.Dequeue))

    test("an unsatisfiable constraint set fails loudly"):
      val solver = new TxnSolver:
        def iface:         DutInterface                                = queueIface
        def cycles:        Int                                         = 2
        def constraints(): (Arena, Context, Block, TxnRecipe) ?=> Unit =
          summon[TxnRecipe].addKindConstraint { r =>
            val v = r.kind(0, "enq")
            smtAssert(v === TxnKind.Enqueue.id.S)
            smtAssert(v === TxnKind.Dequeue.id.S)
          }

      val failed =
        try
          solver.solve()
          false
        catch case e: RuntimeException => e.getMessage.contains("kind solving")
      assert(failed)
