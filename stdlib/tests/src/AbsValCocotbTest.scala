// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** The VPI binding: cocotb drives the *same* lib model from the *same* `abi.json` as the DPI binding — but through VPI
  * (`dut.drive_<name>.value` / `int(dut.probe_<name>.value)`), so it needs no DPI wrapper, only the lib model. This is
  * what "generate dpi/vpi from abi.json" means: one contract, two frontend bindings, identical behaviour.
  *
  * Requires the cocotb interpreter (`$COCOTB_PYTHON`, set by the dev shell) and `verilator`; it is skipped when cocotb
  * is unavailable.
  */
object AbsValCocotbTest extends TestSuite:
  private val outputRoot = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)
  private val frontend   = os.Path(sys.props("zaozi.stdlib.cocotbFrontend"), os.pwd)

  val tests: Tests = Tests:
    test("cocotb (VPI) drives the same lib model from the same abi.json"):
      sys.env.get("COCOTB_PYTHON") match
        case None     =>
          println("COCOTB_PYTHON not set (not in the dev shell) — skipping the cocotb VPI test")
        case Some(py) =>
          val dir = outputRoot / "AbsValUT-cocotb"
          os.remove.all(dir)
          val gen = UTGenerator(AbsValUT, AbsValParameter(8), outputDirectory = dir)
          gen.saveAbi(dir / "abi.json")
          val lib = gen.emitLib(dir) // the VPI binding needs only the lib model, no DPI wrapper
          os.write.over(dir / "stimulus.json", """{ "A": [5, -3, 7] }""")

          val out = os
            .proc(
              py,
              frontend.toString,
              dir.toString,
              lib.topModule,
              dir / "abi.json",
              dir / "stimulus.json",
              lib.sources.map(_.toString)
            )
            .call(cwd = dir, check = true, mergeErrIntoOut = true)
            .out
            .text()

          assert(out.contains("PY cyc=1 A=5 ABSVAL=5"))
          assert(out.contains("PY cyc=2 A=253 ABSVAL=3"))
          assert(out.contains("PY cyc=3 A=7 ABSVAL=7"))
