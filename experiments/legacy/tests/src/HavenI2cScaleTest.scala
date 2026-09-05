// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** Does circt-bmc solve through a three-module I2C hierarchy at all? The intent is deliberately trivial — one
  * control-register write — because the question under test is scale, not cleverness.
  */
object HavenI2cScaleTest extends TestSuite:
  import FormalGenHarness.*

  val tests: Tests = Tests:
    test("an intent solves through the i2c hierarchy, and we learn what it costs"):
      val dir   = freshDir("HavenI2c-scale")
      val param = HavenI2cCtrlParameter(0x80)
      val files = Seq("i2c_master_top", "i2c_master_byte_ctrl", "i2c_master_bit_ctrl")
        .map(n => resources / "haven" / s"$n.v")

      val t0     = System.currentTimeMillis()
      val model  = FormalUT.lowerGenerator(HavenI2cCtrlUT, param, dir)
      val tLower = System.currentTimeMillis() - t0

      val t1     = System.currentTimeMillis()
      val ip     = SvImport.toHw(files, dir / "imported", include = Some(resources / "haven"))
      val merged = SvImport.mergeForBmc(model.hw, ip)
      val tImp   = System.currentTimeMillis() - t1

      val t2      = System.currentTimeMillis()
      val outcome = FormalUT.generate(model.copy(hw = merged), bound = 3)
      val tSolve  = System.currentTimeMillis() - t2

      println(s"  lower ${tLower}ms | import+merge ${tImp}ms | solve ${tSolve}ms -> ${outcome.getClass.getSimpleName}")
      val txn = solved(outcome)
      println(s"  witness: ${txn.cycles} cycles, ${txn.values.size} traced signals")
      assert(txn.cycles >= 1)

    test("a partially specified temporal intent solves through the i2c hierarchy"):
      // Two anchors — assert arst_i, then write the control register within 1..3 cycles — with the spacing and
      // everything in between left to the solver.
      //
      // Note on the baseline, corrected after reading its driver: HAVEN's `seq_item` declares an `arst_i` field
      // and a constraint for it, but `drive_item` never references that field (zero occurrences), so the pin is
      // never driven from a transaction. The reset paths in bit_ctrl are therefore unreachable by any sequence
      // its LLM writes — not because a constraint forbids them, but because the template generated a field, a
      // constraint and a modport entry without the driver assignment that would connect them, and nothing in the
      // flow detects the gap.
      val dir   = freshDir("HavenI2c-reset")
      val param = HavenI2cCtrlParameter(0x80)
      val files = Seq("i2c_master_top", "i2c_master_byte_ctrl", "i2c_master_bit_ctrl")
        .map(n => resources / "haven" / s"$n.v")

      val model  = FormalUT.lowerGenerator(HavenI2cResetUT, param, dir)
      val ip     = SvImport.toHw(files, dir / "imported", include = Some(resources / "haven"))
      val merged = SvImport.mergeForBmc(model.hw, ip)
      val t0     = System.currentTimeMillis()
      val txn    = solved(FormalUT.generate(model.copy(hw = merged), bound = 6))
      println(s"  solved in ${System.currentTimeMillis() - t0}ms: ${txn.cycles} cycles")

      // The second anchor must appear: a write to the control register (address 2).
      val adr = txn.values("wb_adr_i")
      val we  = txn.values("wb_we_i")
      val hit = adr.indices.exists(i => adr(i) == BigInt(2) && we(i) == BigInt(1))
      assert(hit)
      println(s"  wb_adr_i: ${adr.mkString(",")}  wb_we_i: ${we.mkString(",")}")
