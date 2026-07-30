// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.rvprobe.frontend.PortDir
import me.jiuyang.utlib.*
import utest.*

object TxnTest extends TestSuite:
  val tests: Tests = Tests:
    test("TxnKind round-trips through its id"):
      assert(TxnKind.Idle.id == 0)
      assert(TxnKind.fromId(TxnKind.Enqueue.id) == TxnKind.Enqueue)
      assert(TxnKind.fromId(TxnKind.Dequeue.id) == TxnKind.Dequeue)

    test("SolvedStimulus looks up by cycle and port"):
      val stimulus = SolvedStimulus(
        dut = "Queue",
        cycles = 3,
        txns = Seq(
          SolvedTxn(0, "enq", TxnKind.Enqueue, BigInt(7)),
          SolvedTxn(1, "enq", TxnKind.Idle, BigInt(0)),
          SolvedTxn(1, "deq", TxnKind.Dequeue, BigInt(0))
        )
      )
      assert(stimulus.at(0, "enq").map(_.payload).contains(BigInt(7)))
      assert(stimulus.at(1, "deq").map(_.kind).contains(TxnKind.Dequeue))
      assert(stimulus.at(2, "enq").isEmpty)

    test("DutInterface separates drive and monitor ports"):
      val iface = DutInterface(
        dutName = "Queue",
        ports = Seq(
          PortSpec("enq", PortDir.Drive, 16),
          PortSpec("deq", PortDir.Monitor, 16)
        ),
        status = Seq("empty", "full")
      )
      assert(iface.ports.count(_.dir == PortDir.Drive) == 1)
      assert(iface.ports.count(_.dir == PortDir.Monitor) == 1)

    test("SolvedStimulus survives a upickle round trip"):
      // The harness carries a SolvedStimulus inside a Zaozi Parameter, which
      // the generator machinery serializes — so this must hold.
      val stimulus = SolvedStimulus(
        dut = "Queue",
        cycles = 2,
        txns = Seq(
          SolvedTxn(0, "enq", TxnKind.Enqueue, BigInt(255)),
          SolvedTxn(1, "deq", TxnKind.Dequeue, BigInt(0))
        )
      )
      val json     = upickle.default.write(stimulus)
      assert(upickle.default.read[SolvedStimulus](json) == stimulus)

    test("CoverageReport summarizes hits and misses"):
      val points = Seq(
        Coverpoint("full", "queue reached full"),
        Coverpoint("empty", "queue reached empty"),
        Coverpoint("b2b", "back-to-back enqueue")
      )
      val report = CoverageReport(Map("full" -> 3, "empty" -> 1, "b2b" -> 0))
      assert(report.hit("full"))
      assert(!report.hit("b2b"))
      assert(report.missed(points).map(_.name) == Seq("b2b"))
      assert(math.abs(report.rate(points) - 2.0 / 3.0) < 1e-9)
