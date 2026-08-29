// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** Constraining *internal* design state: `fp_counter` is a register inside the HAVEN ALU's FP pipeline, present on no
  * port. `SvImport.promoteState` lifts it to a port of the imported model and the ordinary typed state semantics
  * names it, so the solver has to discover on its own which opcode starts the FP pipeline and how long to hold it —
  * a scenario no constraint over the DUT's IO can even express.
  */
object HavenAluStateFormalGenTest extends TestSuite:
  import FormalGenHarness.*

  val tests: Tests = Tests:
    test("an intent over promoted internal state solves: fp_counter reaches 3"):
      val dir    = freshDir("HavenAluState-formalgen")
      val param  = HavenAluParameter()
      val svFile = resources / "haven" / "alu_top.v"

      val model  = FormalUT.lowerGenerator(HavenAluStateUT, param, dir, delayedDrives = Seq("A"))
      val ip     = SvImport.promoteState(
        SvImport.toHw(Seq(svFile), dir / "imported"),
        Seq(StateSignal("fp_counter", 3))
      )
      val merged = SvImport.mergeForBmc(model.hw, ip)
      val txn    = solved(FormalUT.generate(model.copy(hw = merged), bound = 8))

      // The witness must actually drive an FP opcode with start asserted — the route the solver had to find.
      val gen  = UTGenerator(HavenAluStateUT, param, outputDirectory = dir)
      val stim = Stimulus.save(txn, gen.abi.spec, dir / "stimulus.txt")
      val a    = os.read(stim).linesIterator.map(_.trim.toLong).toVector
      val fpStart = a.exists { v =>
        val op = (v >>> 32) & 0xf
        val st = (v >>> 36) & 1
        st == 1 && (op == 0 || op == 1) // OP_FP_ADD / OP_FP_SUB with start
      }
      assert(fpStart)

      // And the model reached the state the intent named: the promoted register surfaces through the wrapper's
      // COUNTER port, climbing to 3 while the FP pipeline is active.
      assert(txn.values("COUNTER").contains(BigInt(3)))
      assert(txn.values("instance/fp_active_state").contains(BigInt(1)))
