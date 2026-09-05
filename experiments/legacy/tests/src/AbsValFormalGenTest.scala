// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** The full Semantic-to-Stimulus loop on [[AbsValOddUT]]: the typed-DSL constraint (A is odd, as SVA) → circt-bmc
  * witness ([[FormalUT.generateGenerator]]) → `stimulus.txt` ([[Stimulus]]) → Model B replay under Verilator — and the
  * replayed DUT observes the constrained input with the right |A|.
  */
object AbsValFormalGenTest extends TestSuite:
  import FormalGenHarness.*

  val tests: Tests = Tests:
    test("constraint → circt-bmc witness → Model B replay: A is odd and |A| is right"):
      val dir = freshDir("AbsValOdd-formalgen")
      val param = AbsValParameter(8)

      // 1. Solve: the module asserts ¬(A odd); the violation trace is the transaction.
      val txn = solved(FormalUT.generateGenerator(AbsValOddUT, param, bound = 1, dir))

      // 2. Bridge: witness → stimulus.txt in the callback's representation.
      val gen  = UTGenerator(AbsValOddUT, param, outputDirectory = dir)
      val spec = gen.abi.spec
      val stim = Stimulus.save(txn, spec, dir / "stimulus.txt")
      val a0   = os.read(stim).linesIterator.next().trim.toLong
      assert((a0 & 1) == 1)

      // 3. Replay: build the Model B testbench and observe the constrained drive.
      val tb  = gen.emitTestbench(dir, runCycles = txn.cycles + 1)
      val out = buildAndReplay(tb, dir)

      // The probe observed at cycle 2 is the DUT's response to the cycle-1 drive. An odd 8-bit witness is in
      // [-127, 127], so |A| never overflows.
      val expAbs = math.abs(a0)
      assert(out.contains(s"TB cyc=2 A=$a0 ABSVAL=$expAbs"))
