// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** The flow with its completion in the model — the property circt-bmc gave no verdict on in hours — through the
  * JasperGold backend: the witness must run the whole command (~220 cycles at prescaler 2) and observe the interrupt
  * flag rise with arbitration intact. Skipped when no engine is reachable (`ZAOZI_EDA_SHELL` or `jg` on the path).
  */
object HavenI2cFlowJgTest extends TestSuite:
  import FormalGenHarness.*

  val files = Seq("i2c_master_top", "i2c_master_byte_ctrl", "i2c_master_bit_ctrl").map(n => resources / "haven" / s"$n.v")

  val tests: Tests = Tests:
    test("a flow proves its own completion under JasperGold"):
      if !JasperGold.available then println("  SKIPPED: no JasperGold reachable")
      else
        val dir   = freshDir("HavenI2c-flow-jg")
        val param = HavenI2cFlowParameter("wr_sto", txr = 0xa0, cmd = 0xd0)
        val model = JasperGold.lower(HavenI2cFlowUT, param, dir, files, include = Some(resources / "haven"))
        val t0    = System.currentTimeMillis()
        val out   = JasperGold.generate(model, dir / "jg")
        println(s"  jg ${System.currentTimeMillis() - t0}ms -> ${out.getClass.getSimpleName}")
        val txn   = solved(out)
        println(s"  witness: ${txn.cycles} cycles, ${txn.values.size} signals")

        val adr = txn.values("wb_adr_i")
        val dat = txn.values("wb_dat_i")
        val we  = txn.values("wb_we_i")
        val ack = txn.values("ACK")
        val cmdAt = adr.indices.find(i => we(i) == 1 && ack(i) == 1 && adr(i) == 4 && dat(i) == BigInt(0xd0))
        assert(cmdAt.nonEmpty)
        // The transfer ran: the DUT's interrupt flag rises after the command and is read back on DAT.
        val irq = txn.values.collectFirst { case (k, v) if k.endsWith("irq_flag") => v }.get
        val rose = irq.indices.find(i => i > cmdAt.get && irq(i) == 1)
        assert(rose.nonEmpty)
        assert(txn.values("DAT").drop(rose.get).exists(v => (v & 1) == 1))
        // …and it completed, rather than losing arbitration to its own looped-back bus.
        val al = txn.values.collectFirst { case (k, v) if k.endsWith("/al") => v }.get
        assert(al.forall(_ == 0))
        println(s"  command at ${cmdAt.get}, irq_flag rises at ${rose.get}")

    test("the shapes only JasperGold takes: unbounded repetition, throughout, goto repetition"):
      if !JasperGold.available then println("  SKIPPED: no JasperGold reachable")
      else
        val dir = freshDir("HavenI2c-flow-jg-shapes")
        for style <- Seq("unbounded", "throughout", "goto") do
          val param = HavenI2cFlowParameter(s"wr_sto_$style", txr = 0xa0, cmd = 0xd0, gapStyle = style)
          val model = JasperGold.lower(HavenI2cFlowUT, param, dir / style, files, include = Some(resources / "haven"))
          val t0    = System.currentTimeMillis()
          val out   = JasperGold.generate(model, dir / style / "jg")
          val ms    = System.currentTimeMillis() - t0
          val txn   = out match
            case GenerateOutcome.Generated(t) => t
            case other                        => throw java.lang.AssertionError(s"$style: expected Generated, got $other after ${ms}ms")
          val irq   = txn.values.collectFirst { case (k, v) if k.endsWith("irq_flag") => v }.get
          val al    = txn.values.collectFirst { case (k, v) if k.endsWith("/al") => v }.get
          assert(irq.contains(BigInt(1)) && al.forall(_ == 0))
          println(s"  SHAPE $style: witness ${txn.cycles} cycles in ${ms}ms, irq_flag at ${irq.indexOf(BigInt(1))}")
