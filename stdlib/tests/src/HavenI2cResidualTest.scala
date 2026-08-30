// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** Intents aimed at i2c's DUT residual: the reset branches, the clock-stretch path, and arbitration loss. Each is
  * anchors-with-gaps, and each needs bus behaviour (SCL/SDA driven as inputs) that HAVEN can only produce through a
  * hand-written slave BFM.
  */
object HavenI2cResidualTest extends TestSuite:
  import FormalGenHarness.*

  val tests: Tests = Tests:
    test("each residual class yields a witness"):
      val dir   = freshDir("HavenI2c-residual")
      val files = Seq("i2c_master_top", "i2c_master_byte_ctrl", "i2c_master_bit_ctrl")
        .map(n => resources / "haven" / s"$n.v")
      val ip    = SvImport.toHw(files, dir / "imported", include = Some(resources / "haven"))

      val results = I2cTarget.values.toSeq.map { t =>
        val param  = HavenI2cTargetParameter(t)
        val model  = FormalUT.lowerGenerator(HavenI2cTargetUT, param, dir / t.toString)
        val merged = SvImport.mergeForBmc(model.hw, ip)
        val t0     = System.currentTimeMillis()
        // Per-target bound: arbitration loss involves a full START plus bit activity, and the solver established
        // that 12 cycles genuinely is not enough — Infeasible at 12, witness at 20.
        val bound  = t match
          case I2cTarget.ArbitrationLost => 20
          case _                         => 12
        val out    = FormalUT.generate(model.copy(hw = merged), bound = bound)
        val ms     = System.currentTimeMillis() - t0
        val shape  = out match
          case GenerateOutcome.Generated(tr) => s"witness, ${tr.cycles} cycles"
          case GenerateOutcome.Infeasible    => "INFEASIBLE within the bound"
          case GenerateOutcome.Unknown(d)    => s"unknown: ${d.take(60)}"
        println(f"  ${t.toString}%-16s ${ms}%6dms  $shape")
        t -> out
      }

      // Reset and clock-stretch must be reachable; both need bus behaviour a solver supplies and a template
      // testbench needs a hand-written BFM for.
      val byTarget = results.toMap
      assert(byTarget(I2cTarget.ResetPulse).isInstanceOf[GenerateOutcome.Generated])
      assert(byTarget(I2cTarget.ClockStretch).isInstanceOf[GenerateOutcome.Generated])
      assert(byTarget(I2cTarget.ArbitrationLost).isInstanceOf[GenerateOutcome.Generated])
