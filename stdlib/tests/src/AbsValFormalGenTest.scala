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
  private val outputRoot   = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)
  private val verilatorBin = sys.env.getOrElse("VERILATOR", "verilator")

  val tests: Tests = Tests:
    test("constraint → circt-bmc witness → Model B replay: A is odd and |A| is right"):
      val dir   = outputRoot / "AbsValOdd-formalgen"
      os.remove.all(dir)
      val param = AbsValParameter(8)

      // 1. Solve: the module asserts ¬(A odd); the violation trace is the transaction.
      val txn = FormalUT.generateGenerator(AbsValOddUT, param, bound = 1, dir) match
        case GenerateOutcome.Generated(t) => t
        case other                        => throw java.lang.AssertionError(s"expected Generated, got $other")

      // 2. Bridge: witness → stimulus.txt in the callback's representation.
      val gen  = UTGenerator(AbsValOddUT, param, outputDirectory = dir)
      val spec = gen.abi.spec
      val stim = Stimulus.save(txn, spec, dir / "stimulus.txt")
      val a0   = os.read(stim).linesIterator.next().trim.toLong
      assert((a0 & 1) == 1)

      // 3. Replay: build the Model B testbench and observe the constrained drive.
      val tb = gen.emitTestbench(dir, runCycles = txn.cycles + 1)
      os.proc(
        verilatorBin,
        "--binary",
        "--timing",
        "--no-assert",
        "--top-module",
        tb.top,
        "-Wno-fatal",
        "-I" + dir.toString,
        tb.sources.map(_.toString),
        "-o",
        "sim"
      ).call(cwd = dir, check = true, mergeErrIntoOut = true)
      val out =
        os.proc((dir / "obj_dir" / "sim").toString).call(cwd = dir, check = false, mergeErrIntoOut = true).out.text()

      // The probe observed at cycle 2 is the DUT's response to the cycle-1 drive. An odd 8-bit witness is in
      // [-127, 127], so |A| never overflows.
      val expAbs = math.abs(a0)
      assert(out.contains(s"TB cyc=2 A=$a0 ABSVAL=$expAbs"))
