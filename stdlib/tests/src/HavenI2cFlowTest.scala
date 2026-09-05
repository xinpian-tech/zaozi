// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** Transaction-flow intents on the i2c master: the solver supplies the structure (the register writes that launch a
  * command, and where a mid-transfer reset lands), at bounds it solves in under two seconds. Proving the transfer's
  * *completion* in the model is not attempted: at prescaler zero a command takes ~50 cycles, and circt-bmc gave no
  * verdict in hours at bound 68 on this design (docs/date2027/data/i2c-flow.json) — the replay fills that time with
  * status polls and the simulation observes SR.IF instead.
  */
object HavenI2cFlowTest extends TestSuite:
  import FormalGenHarness.*

  val files = Seq("i2c_master_top", "i2c_master_byte_ctrl", "i2c_master_bit_ctrl")

  val tests: Tests = Tests:
    test("a command flow solves as its five writes in order"):
      val dir    = freshDir("HavenI2c-flow")
      val param  = HavenI2cFlowParameter("wr_sto", txr = 0xa0, cmd = 0xd0, gapStyle = "none")
      val ip     =
        SvImport.toHw(
          files.map(n => resources / "haven" / s"$n.v"),
          dir / "imported",
          include = Some(resources / "haven")
        )
      val model  = FormalUT.lowerGenerator(HavenI2cFlowUT, param, dir)
      val merged = SvImport.mergeForBmc(model.hw, ip)
      val txn    = solved(FormalUT.generate(model.copy(hw = merged), bound = 12))

      val adr    = txn.values("wb_adr_i")
      val dat    = txn.values("wb_dat_i")
      val we     = txn.values("wb_we_i")
      val writes = adr.indices.filter(we(_) == 1).map(i => (adr(i).toInt, dat(i).toInt))
      // Prescaler, control (enable), transmit byte, then the command — in that order, each on an acknowledged cycle.
      assert(writes.containsSlice(Seq((0, param.prer), (1, 0), (2, 0x80), (3, 0xa0), (4, 0xd0))))
      val ack = txn.values("ACK")
      assert(adr.indices.filter(we(_) == 1).forall(i => ack(i) == 1))

    test("a reset-midway flow places the reset after the command, with reset released around it"):
      val dir    = freshDir("HavenI2c-flow-rst")
      val param  = HavenI2cFlowParameter("rst_wr", txr = 0xa0, cmd = 0xd0, resetMidway = true, maxWait = 12)
      val ip     =
        SvImport.toHw(
          files.map(n => resources / "haven" / s"$n.v"),
          dir / "imported",
          include = Some(resources / "haven")
        )
      val model  = FormalUT.lowerGenerator(HavenI2cFlowUT, param, dir)
      val merged = SvImport.mergeForBmc(model.hw, ip)
      val txn    = solved(FormalUT.generate(model.copy(hw = merged), bound = 28))

      val adr   = txn.values("wb_adr_i")
      val dat   = txn.values("wb_dat_i")
      val we    = txn.values("wb_we_i")
      val arst  = txn.values("arst_i")
      val cmdAt = adr.indices.find(i => we(i) == 1 && adr(i) == 4 && dat(i) == BigInt(0xd0)).get
      val rstAt = arst.indices.find(i => i > cmdAt && arst(i) == 0).get
      // The beats between command and reset are polls with reset released; the reset is one beat.
      assert((cmdAt + 1 until rstAt).forall(i => we(i) == 0 && arst(i) == 1))
      assert(arst(rstAt + 1) == 1)

    test("the same shapes under circt-bmc, for the record"):
      // Unbounded repetition, `throughout` and goto repetition have no lowering in the bounded flow; what each one
      // does when asked is recorded here (an exception at lowering, or a non-verdict), not asserted.
      val dir = freshDir("HavenI2c-flow-shapes")
      val ip  =
        SvImport.toHw(files.map(n => resources / "haven" / s"$n.v"), dir / "imported", include = Some(resources / "haven"))
      for style <- Seq("unbounded", "throughout", "goto") do
        val param = HavenI2cFlowParameter(s"wr_sto_$style", txr = 0xa0, cmd = 0xd0, gapStyle = style)
        val shape = scala.util.Try {
          val model = FormalUT.lowerGenerator(HavenI2cFlowUT, param, dir / style)
          FormalUT.generate(model.copy(hw = SvImport.mergeForBmc(model.hw, ip)), bound = 12)
        } match
          case scala.util.Success(GenerateOutcome.Generated(t)) => s"witness ${t.cycles} cycles"
          case scala.util.Success(other)                        => other.toString.take(160)
          case scala.util.Failure(e)                            => s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("").linesIterator.take(2).mkString(" | ").take(200)}"
        println(s"  CIRCT-BMC $style: $shape")
