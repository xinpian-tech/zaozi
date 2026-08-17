// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

object AbsValUT extends TestSuite:
  private val parameter       = AbsValParameter(8)
  private val outputDirectory = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd) / AbsVal.moduleName(parameter)
  private val generator       = UTGenerator(AbsVal, parameter, cycles = 3, outputDirectory = outputDirectory)

  private def testOutputDirectory(name: String): os.Path = generator.outputDirectory / name

  private def assertCoversInputClasses(stimulus: SolvedStimulus[AbsValIO]): Unit =
    assert(stimulus.io.A.at(0) > 0)
    assert(stimulus.io.A.at(1) == 0)
    assert(stimulus.io.A.at(2) < 0)

  val tests: Tests = Tests:
    test("module-owned constraints produce positive, zero, and negative inputs"):
      val stimulus = generator.solve()
      assertCoversInputClasses(stimulus)

    test("AbsVal passes its verification-layer assertion"):
      val result = generator.run(testOutputDirectory("run"))
      assert(result.exitCode == 0)
      assert(result.log.contains("HARNESS-DONE"))
      assert(!result.log.contains("HARNESS-TIMEOUT"))

    test("a traced run writes a VCD containing the DUT ports"):
      val result = generator.run(testOutputDirectory("trace"), trace = true)
      val vcd    = os.read(result.tracePath.get)
      assert(vcd.contains("ABSVAL"))
      assert(vcd.contains(" A ") || vcd.contains(" A["))

    test("a frozen stimulus replays without solving"):
      val dir      = testOutputDirectory("replay")
      val path     = dir / "stimulus.json"
      val frozen   = generator.freeze(path)
      val reloaded = generator.loadStimulus(path)
      assert(reloaded == frozen)
      assertCoversInputClasses(reloaded)
      val result   = generator.runStimulus(reloaded, dir / "run")
      assert(result.exitCode == 0)
