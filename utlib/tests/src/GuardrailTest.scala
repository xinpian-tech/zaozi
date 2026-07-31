// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.rvprobe.frontend.PortDir
import me.jiuyang.utlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import utest.*

import java.lang.foreign.Arena

/** The framework's own guardrails: the checks that turn a silent wrong answer into a loud one. Each of these was a way
  * to get a misleading result before it was closed.
  */
object GuardrailTest extends TestSuite:

  private def fifoIface(widths: (String, Int)*) = DutInterface(
    dutName = "Fifo",
    ports = widths.map { case (n, w) =>
      PortSpec(n, if n == "enq" then PortDir.Drive else PortDir.Monitor, w)
    },
    status = Seq("empty", "full")
  )

  private class Probe(
    ifaceIn:       DutInterface,
    coverpointsIn: Seq[Coverpoint])
      extends UnitTest:
    def iface:         DutInterface                                = ifaceIn
    def cycles:        Int                                         = 4
    def coverpoints:   Seq[Coverpoint]                             = coverpointsIn
    def constraints(): (Arena, Context, Block, TxnRecipe) ?=> Unit = mustEnqueue(0 until 4, "enq")

  val tests: Tests = Tests:
    test("ports disagreeing on payload width are rejected up front"):
      // Previously `width` was declared separately from the port widths, so a
      // disagreement surfaced only after a full solve, as a Zaozi width error
      // that named neither of them.
      val bad     = Probe(fifoIface("enq" -> 8, "deq" -> 3), Seq.empty)
      val message =
        try { bad.width; "" }
        catch case e: IllegalArgumentException => e.getMessage
      assert(message.contains("one payload width"))
      assert(message.contains("enq=8"))
      assert(message.contains("deq=3"))

    test("a consistent interface derives its width"):
      assert(Probe(fifoIface("enq" -> 8, "deq" -> 8), Seq.empty).width == 8)

    test("a mistyped coverpoint is a mistake, not a coverage hole"):
      // This is the failure the typed-condition rework did *not* fix on its
      // own: the expectation side is still a string, so it needs validating.
      val typo    = Probe(fifoIface("enq" -> 8, "deq" -> 8), Seq(Coverpoint("cover_fulll", "typo")))
      val message =
        try { typo.validateCoverpoints(); "" }
        catch case e: IllegalArgumentException => e.getMessage
      assert(message.contains("unknown coverpoint"))
      assert(message.contains("cover_fulll"))
      // and it tells you what you could have meant
      assert(message.contains("cover_full"))

    test("every declared coverpoint is one the harness actually emits"):
      // Elaboration enforces both directions, so the catalogue cannot drift
      // from the code that emits it. If it had, this would fail to elaborate.
      val sv = FifoHarness.verilogString(HarnessFixture.parameter)
      FifoCoverpoints.all.foreach(p => assert(sv.contains(p.name)))

    test("a run that never finishes fails loudly instead of hanging"):
      // The timeout guard was previously unverified — an untested safety net.
      val wedged = HarnessFixture.parameter.copy(timeoutCycles = Some(2))
      val result = Simulation.run(wedged, os.temp.dir(prefix = "utlib-timeout"))
      assert(result.exitCode != 0)
      assert(result.log.contains("HARNESS-TIMEOUT"))

    test("an unknown backend name reports what is available"):
      val message =
        try { Toolchain.simulators("vcs"); "" }
        catch case e: NoSuchElementException => "missing"
      assert(message == "missing")
      assert(Toolchain.simulators.keySet == Set("verilator"))
