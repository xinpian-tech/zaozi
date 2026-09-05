// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** The machine-facing report: one call from a constrained UT to JSON + stimulus artifacts, as the LLM harness
  * consumes it.
  */
object UTCliTest extends TestSuite:
  private val outputRoot = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)

  val tests: Tests = Tests:
    test("generateReport solves AbsValOddUT and reports JSON with the stimulus file"):
      val dir    = outputRoot / "UTCli-report"
      os.remove.all(dir)
      val report = UTCli.generateReport(AbsValOddUT, AbsValParameter(8), bound = 1, dir)
      assert(report("status").str == "generated")
      assert(report("dut").str == "AbsValOddUT_width8")
      assert(report("cycles").num == 1)
      val stim   = os.Path(report("stimulusFile").str)
      assert(os.exists(stim))
      assert((os.read(stim).linesIterator.next().trim.toLong & 1) == 1)
