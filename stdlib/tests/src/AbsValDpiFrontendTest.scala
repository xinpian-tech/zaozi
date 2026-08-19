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

  /** Emit the artifacts for a given width, build the `.so`, drive it from Python, return stdout. */
  private def drive(dir: os.Path, width: Int, stimulusJson: String): String =
    os.remove.all(dir)
    val gen     = UTGenerator(AbsValUT, AbsValParameter(width), outputDirectory = dir)
    gen.saveDpi(dir / "AbsValDPI.json")
    val lib     = gen.emitLib(dir)
    val wrapper = gen.emitDpiWrapper(lib.topModule, dir)
    os.write.over(dir / "stimulus.json", stimulusJson)
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
    val so      = dir / "obj_dir" / "libVsim.so"
    assert(os.exists(so))
    os.proc("python3", frontend.toString, so.toString, dir / "AbsValDPI.json", dir / "stimulus.json")
      .call(cwd = dir, check = true, mergeErrIntoOut = true)
      .out
      .text()

  val tests: Tests = Tests:
    test("Python drives an 8-bit DUT through the generated export \"DPI-C\" wrapper"):
      val out = drive(outputRoot / "AbsValUT-dpi-8", 8, """{ "A": [5, -3, 7] }""")
      // |A|: A echoes the driven input, ABSVAL is its magnitude. The probe ports are unsigned
      // `Bits`, so the driven -3 reads back as its 8-bit value 253 while ABSVAL is 3.
      assert(out.contains("PY cyc=1 A=5 ABSVAL=5"))
      assert(out.contains("PY cyc=2 A=253 ABSVAL=3"))
      assert(out.contains("PY cyc=3 A=7 ABSVAL=7"))

    test("Python drives a 128-bit DUT — wide ports cross as svBitVecVal"):
      val wide = (BigInt(1) << 70) + 5 // > 64 bits, positive (bit 127 clear), so |A| == A
      val out  = drive(outputRoot / "AbsValUT-dpi-128", 128, s"""{ "A": [5, $wide] }""")
      assert(out.contains("PY cyc=1 A=5 ABSVAL=5"))
      assert(out.contains(s"PY cyc=2 A=$wide ABSVAL=$wide"))
