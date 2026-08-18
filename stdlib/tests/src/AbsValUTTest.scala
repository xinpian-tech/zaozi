// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** The three artifacts the UT flow produces for [[AbsValUT]] — the solved stimulus, the DPI contract, and the flat
  * lib-model SystemVerilog. No simulator is run: the framework stops at these stored artifacts, and driving them is an
  * external concern.
  */
object AbsValUTTest extends TestSuite:
  private val outputRoot = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)

  private def generator(dir: String): UTGenerator[AbsValParameter, AbsValLayers, AbsValIO, AbsValProbe] =
    UTGenerator(AbsValUT, AbsValParameter(8), cycles = 3, outputDirectory = outputRoot / dir)

  val tests: Tests = Tests:
    test("saveStimulus solves the constraints into per-cycle JSON"):
      val gen  = generator("AbsValUT-stimulus")
      val path = gen.outputDirectory / "stimulus.json"
      gen.saveStimulus(path)
      val data = upickle.default.read[ujson.Value](os.read(path))
      assert(data("dut").str == "AbsValUT_width8")
      assert(data("cycles").num.toInt == 3)
      // The constraints are A[0] > 0, A[1] == 0, A[2] < 0. Values serialize as decimal strings.
      val a    = data("inputs")("A").arr.map(_.str.toInt)
      assert(a(0) > 0)
      assert(a(1) == 0)
      assert(a(2) < 0)

    test("saveDpi writes the contract derived from (IO, Probe)"):
      val gen  = generator("AbsValUT-dpi")
      val path = gen.outputDirectory / "AbsValDPI.json"
      val spec = gen.saveDpi(path)
      assert(spec.dut == "AbsValUT_width8")
      assert(os.read(path).contains("\"role\": \"Drive\""))

    test("emitLib lowers the flat lib model with contract-shaped ports"):
      val gen = generator("AbsValUT-lib")
      val lib = gen.emitLib(gen.outputDirectory)
      assert(lib.topModule == "Lib_AbsValUT_width8")
      val sv  = os.read(lib.sources.head)
      assert(sv.contains("module Lib_AbsValUT_width8"))
      assert(sv.contains("drive_A"))
      assert(sv.contains("probe_A"))
      assert(sv.contains("probe_ABSVAL"))
