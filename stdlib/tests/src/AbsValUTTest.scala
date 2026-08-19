// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** The two artifacts the UT flow produces for [[AbsValUT]] — the DPI contract and the flat lib-model SystemVerilog,
  * both derived from `(IO, Probe)`. No simulator is run and no stimulus is solved: the framework stops at these stored
  * artifacts, and both generating stimulus and driving them are external concerns.
  */
object AbsValUTTest extends TestSuite:
  private val outputRoot = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)

  private def generator(dir: String): UTGenerator[AbsValParameter, AbsValLayers, AbsValIO, AbsValUTProbe] =
    UTGenerator(AbsValUT, AbsValParameter(8), outputDirectory = outputRoot / dir)

  val tests: Tests = Tests:
    test("saveAbi writes the contract derived from (IO, Probe)"):
      val gen  = generator("AbsValUT-dpi")
      val path = gen.outputDirectory / "abi.json"
      val spec = gen.saveAbi(path)
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
