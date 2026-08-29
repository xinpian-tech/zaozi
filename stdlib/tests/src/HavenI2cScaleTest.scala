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
