// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** End-to-end DPI closed loop for [[AbsValUT]] over `export "DPI-C"`:
  *
  * emit lib model + DPI-export wrapper → `verilator --lib-create` into a `.so` → drive it from Python (ctypes) via the
  * exported poke/peek symbols.
  *
  * The Python frontend owns the loop; the framework only produced the artifacts. Requires `verilator` and `python3` on
  * PATH (the dev shell), so it is a heavier integration test.
  */
object AbsValDpiFrontendTest extends TestSuite:
  private val outputRoot   = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)
  private val frontend     = os.Path(sys.props("zaozi.stdlib.dpiFrontend"), os.pwd)
  private val verilatorBin = sys.env.getOrElse("VERILATOR", "verilator")

  val tests: Tests = Tests:
    test("Python drives the DUT through the generated export \"DPI-C\" wrapper"):
      val dir = outputRoot / "AbsValUT-dpi-frontend"
      os.remove.all(dir)
      val gen = UTGenerator(AbsValUT, AbsValParameter(8), outputDirectory = dir)

      // Artifacts: the DPI contract, the lib model, and the export-"DPI-C" wrapper + C shim.
      gen.saveDpi(dir / "AbsValDPI.json")
      val lib     = gen.emitLib(dir)
      val wrapper = gen.emitDpiWrapper(lib.topModule, dir)
      // A demo stimulus: positive, negative, positive. (Where stimulus comes from is external.)
      os.write.over(dir / "stimulus.json", """{ "A": [5, -3, 7] }""")

      // Build the lib model + wrapper into a shared library the frontend loads.
      os.proc(
        verilatorBin,
        "--cc",
        "--lib-create",
        "Vsim",
        "--build",
        "--timing",
        "-Wno-fatal",
        "-I" + dir.toString,
        "--top-module",
        wrapper.top,
        (lib.sources ++ wrapper.sources).map(_.toString)
      ).call(cwd = dir, check = true, mergeErrIntoOut = true)

      val so = dir / "obj_dir" / "libVsim.so"
      assert(os.exists(so))

      val run = os
        .proc("python3", frontend.toString, so.toString, dir / "AbsValDPI.json", dir / "stimulus.json")
        .call(cwd = dir, check = true, mergeErrIntoOut = true)
      val out = run.out.text()

      // |A|: A echoes the driven input, ABSVAL is its magnitude. The probe ports are unsigned
      // `Bits` in the contract, so the driven -3 reads back as its 8-bit value 253 while ABSVAL
      // (the DUT interprets A as signed) is 3 — the frontend honours each port's `signed` flag.
      assert(out.contains("PY cyc=1 A=5 ABSVAL=5"))
      assert(out.contains("PY cyc=2 A=253 ABSVAL=3"))
      assert(out.contains("PY cyc=3 A=7 ABSVAL=7"))
