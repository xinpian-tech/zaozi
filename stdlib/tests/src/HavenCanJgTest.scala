// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** The CAN controller under the JasperGold backend, with the engine playing the second node on the bus. Leaving
  * reset and starting a frame must solve; the acknowledged transmission, receiving a frame and reaching bus-off are recorded (the engine has to
  * construct a CRC-correct frame, or 256 error counts). Skipped without an engine.
  */
object HavenCanJgTest extends TestSuite:
  import FormalGenHarness.*

  val rtlDir = resources / "haven" / "can"
  val files  = Seq(
    "can_defines", "can_crc", "can_ibo", "can_acf", "can_register", "can_register_asyn", "can_register_asyn_syn",
    "can_register_syn", "can_fifo", "can_btl", "can_bsp", "can_registers", "can_top"
  ).map(n => rtlDir / s"$n.v")

  val tests: Tests = Tests:
    test("leave reset and transmit solve; receive and bus-off are recorded"):
      if !JasperGold.available then println("  SKIPPED: no JasperGold reachable")
      else
        val dir      = freshDir("HavenCan-jg")
        val failures = Seq.newBuilder[String]
        for flow <- Seq("leave_reset", "sof", "transmit", "receive", "busoff") do
          val param = HavenCanFlowParameter(flow, partner = if flow == "transmit" then "ack_once" else "free")
          val model = JasperGold.lower(HavenCanFlowUT, param, dir / flow, files, include = Some(rtlDir))
          val t0    = System.currentTimeMillis()
          val out   = JasperGold.generate(model, dir / flow / "jg", timeLimit = if flow == "transmit" then "900s" else "300s")
          val ms    = System.currentTimeMillis() - t0
          out match
            case GenerateOutcome.Generated(t) =>
              // Outputs the flow does not name are outside the dumped cone.
              def first(name: String, v: BigInt) = t.values.get(name).map(_.indexOf(v)).map(_.toString).getOrElse("n/a")
              println(s"  CAN $flow: witness ${t.cycles} cycles in ${ms}ms; tx dominant first at ${first("TX", BigInt(0))}; irq line low first at ${first("IRQ", BigInt(0))}; last read data ${t.values.get("DAT").map(_.last.toString(16)).getOrElse("n/a")}; bus-off (BUSOFF low after high) at ${t.values.get("BUSOFF").map(v => v.indexOf(BigInt(0), v.indexOf(BigInt(1)).max(0))).map(_.toString).getOrElse("n/a")}")
            case other =>
              println(s"  CAN $flow: $other after ${ms}ms")
              if flow == "leave_reset" || flow == "sof" then failures += s"$flow: $other"
        val fails = failures.result()
        if fails.nonEmpty then println(s"  FAILURES: ${fails.mkString("; ")}")
        assert(fails.isEmpty)
