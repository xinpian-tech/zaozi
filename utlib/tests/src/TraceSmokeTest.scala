// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*

import utest.*

object TraceSmokeTest extends TestSuite:
  private val outputRoot = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)

  val tests: Tests = Tests:
    test("a traced run writes a VCD with real signals"):
      val parameter = MixedInputParameter(4)
      val dut       = UTGenerator(
        MixedInput,
        parameter,
        cycles = 1,
        outputDirectory = outputRoot / "trace-smoke"
      )
      val result    = dut.run(trace = true)
      assert(result.exitCode == 0)

      val vcd = dut.outputDirectory / "trace.vcd"
      assert(os.exists(vcd))
      val content = os.read(vcd)
      // A real dump declares variables and records value changes, not just a header.
      assert(content.contains("$var"))
      assert(content.linesIterator.exists(_.startsWith("#")))
