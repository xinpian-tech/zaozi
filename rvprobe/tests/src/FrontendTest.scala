// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.rvprobe.frontend.*

import utest.*

/** Exercises the DutFrontend contract through its public API. The Zaozi leg is
  * self-contained (deterministic solve, no SMT/env), so it validates the seam —
  * alphabet, whitebox, solve -> artifact -> render — end to end. */
object FrontendTest extends TestSuite:
  private def queueIface = TransactionInterface(
    dutName = "Queue",
    ports = Seq(
      DecoupledPort("enq", PortDir.Drive, 32),
      DecoupledPort("deq", PortDir.Monitor, 32)
    ),
    status = Seq(
      StatusSignal("empty", "occupancy"),
      StatusSignal("full", "occupancy")
    )
  )

  val tests = Tests:

    test("Zaozi leg: alphabet is transaction kinds from the Decoupled ports"):
      val fe        = ZaoziFrontend(queueIface)
      val mnemonics = fe.alphabet.kinds.map(_.mnemonic).toSet
      assert(mnemonics == Set("enqueue.enq", "dequeue.deq"))
      // stable ids, one per port
      assert(fe.alphabet.kinds.map(_.id).toSet == Set(0, 1))

    test("Zaozi leg: whitebox predicates are the module status signals"):
      val fe = ZaoziFrontend(queueIface)
      assert(fe.whitebox.map(_.signal).toSet == Set("empty", "full"))
      assert(fe.whitebox.forall(_.category == "occupancy"))

    test("Zaozi leg: backend renders a ChiselSim driver from a solved artifact"):
      val fe     = ZaoziFrontend(queueIface)
      assert(fe.backend.kind == "chiselsim")
      val driver = fe.generate()
      // enqueue drives valid/bits and waits on ready; dequeue waits on valid
      assert(driver.contains("dut.enq.bits.poke"))
      assert(driver.contains("dut.enq.valid.poke(true)"))
      assert(driver.contains("dut.deq.ready.poke(true)"))
      assert(driver.contains("dut.clock.step()"))

    test("contract: solve's artifact sequence lines up with its transactions"):
      val fe  = ZaoziFrontend(queueIface)
      val art = fe.solve()
      assert(art.transactions.nonEmpty)
      assert(art.sequence.selections.size == art.transactions.size)
      // the one Drive port produced an enqueue field
      assert(art.sequence.fields.keys.exists(_.startsWith("enq_bits_")))
