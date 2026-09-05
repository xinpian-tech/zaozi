// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** The SDRAM controller's flows under the JasperGold backend: every one is an unbounded wait for something the
  * controller does on its own clock (initialization, refresh) or in response to a held Wishbone request. Skipped
  * without an engine.
  */
object HavenSdramJgTest extends TestSuite:
  import FormalGenHarness.*

  val rtlDir = resources / "haven" / "sdram"
  val files  = Seq(
    "sdrc_define", "async_fifo", "sync_fifo", "sdrc_bank_fsm", "sdrc_bank_ctl", "sdrc_bs_convert", "sdrc_req_gen",
    "sdrc_xfr_ctl", "sdrc_core", "wb2sdrc", "sdrc_top_split"
  ).map(n => rtlDir / s"$n.v")

  val tests: Tests = Tests:
    test("init, write, read, precharge and refresh flows solve"):
      if !JasperGold.available then println("  SKIPPED: no JasperGold reachable")
      else
        val dir      = freshDir("HavenSdram-jg")
        val failures = Seq.newBuilder[String]
        for flow <- Seq("init", "write", "read", "precharge", "refresh", "wrrd", "rdwr", "powerdown") do
          val param = HavenSdramFlowParameter(flow)
          val model = JasperGold.lower(HavenSdramFlowUT, param, dir / flow, files, include = Some(rtlDir))
          val t0    = System.currentTimeMillis()
          val out   = JasperGold.generate(model, dir / flow / "jg", timeLimit = "900s")
          val ms    = System.currentTimeMillis() - t0
          out match
            case GenerateOutcome.Generated(t) =>
              val init = t.values("INIT")
              // The command bus, from the instance pins ({cs_n, ras_n, cas_n, we_n}); `CMD` is a concatenation in
              // the wrapper's SV and not a plain alias the trace reader recovers.
              // (The dumped window is the property's cone, so the pins are present only when the flow names them.)
              def pin(n: String) = t.values.collectFirst { case (k, v) if k.endsWith(s"/$n") => v }
              val cmd  = for cs <- pin("sdr_cs_n"); ras <- pin("sdr_ras_n"); cas <- pin("sdr_cas_n"); we <- pin("sdr_we_n")
              yield cs.indices.map(i => (cs(i) << 3) | (ras(i) << 2) | (cas(i) << 1) | we(i)).toVector
              val seen = cmd.map(_.distinct.map(_.toString(2)).mkString(",")).getOrElse("n/a")
              println(s"  SDRAM $flow: witness ${t.cycles} cycles in ${ms}ms; init_done at ${init.indexOf(BigInt(1))}; commands $seen")
              val expected = Map("write" -> 4, "read" -> 5, "precharge" -> 2, "refresh" -> 1, "wrrd" -> 5, "rdwr" -> 4).get(flow)
              if !init.contains(BigInt(1)) then failures += s"$flow: init_done never seen"
              if flow == "powerdown" && !(init.indexOf(BigInt(1)) >= 0 && init.drop(init.indexOf(BigInt(1))).contains(BigInt(0))) then failures += "powerdown: init_done never fell"
              for e <- expected if !cmd.exists(_.contains(BigInt(e))) do failures += s"$flow: command $e not observed"
            case other =>
              println(s"  SDRAM $flow: $other after ${ms}ms")
              failures += s"$flow: $other"
        val fails = failures.result()
        if fails.nonEmpty then println(s"  FAILURES: ${fails.mkString("; ")}")
        assert(fails.isEmpty)
