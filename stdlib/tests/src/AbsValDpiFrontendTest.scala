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
  * Covers replay (`drive`) and constrained-random generation (`generate`, the CRV core sampling against the module's
  * `assumeOk` probe). The Python frontend owns the loop; the framework only produced the artifacts. Requires
  * `verilator` and `python3` on PATH (the dev shell).
  */
object AbsValDpiFrontendTest extends TestSuite:
  private val outputRoot   = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)
  private val frontend     = os.Path(sys.props("zaozi.stdlib.dpiFrontend"), os.pwd)
  private val verilatorBin = sys.env.getOrElse("VERILATOR", "verilator")

  /** Emit the artifacts for `width` and build the lib model + DPI wrapper into `libVsim.so`. `extraArgs` lets a caller
    * pass e.g. `--no-assert` (CRV searches the space, so the SVA `assume` must not fire). Returns the `.so` and the
    * `port.json`.
    */
  private def build(dir: os.Path, width: Int, extraArgs: Seq[String] = Seq.empty): (os.Path, os.Path) =
    os.remove.all(dir)
    val gen     = UTGenerator(AbsValUT, AbsValParameter(width), outputDirectory = dir)
    gen.savePorts(dir / "port.json")
    val lib     = gen.emitLib(dir)
    val wrapper = gen.emitDpiWrapper(lib.topModule, dir)
    os.proc(
      verilatorBin,
      "--cc",
      "--lib-create",
      "Vsim",
      "--build",
      "--timing",
      "-Wno-fatal",
      extraArgs,
      "-I" + dir.toString,
      "--top-module",
      wrapper.top,
      (lib.sources ++ wrapper.sources).map(_.toString)
    ).call(cwd = dir, check = true, mergeErrIntoOut = true)
    val so      = dir / "obj_dir" / "libVsim.so"
    assert(os.exists(so))
    (so, dir / "port.json")

  private def run(dir: os.Path, args: os.Shellable*): String =
    os.proc("python3", frontend.toString, args).call(cwd = dir, check = true, mergeErrIntoOut = true).out.text()

  val tests: Tests = Tests:
    test("drive: an 8-bit DUT through the generated export \"DPI-C\" wrapper"):
      val dir       = outputRoot / "AbsValUT-dpi-8"
      val (so, abi) = build(dir, 8)
      os.write.over(dir / "stimulus.json", """{ "A": [5, -3, 7] }""")
      val out       = run(dir, "drive", so, abi, dir / "stimulus.json")
      // |A|: A echoes the driven input, ABSVAL is its magnitude. The probe ports are unsigned
      // `Bits`, so the driven -3 reads back as its 8-bit value 253 while ABSVAL is 3.
      assert(out.contains("PY cyc=1 A=5 ABSVAL=5"))
      assert(out.contains("PY cyc=2 A=253 ABSVAL=3"))
      assert(out.contains("PY cyc=3 A=7 ABSVAL=7"))

    test("drive: a 128-bit DUT — wide ports cross as svBitVecVal"):
      val dir       = outputRoot / "AbsValUT-dpi-128"
      val wide      = (BigInt(1) << 70) + 5 // > 64 bits, positive and odd, so |A| == A
      val (so, abi) = build(dir, 128)
      os.write.over(dir / "stimulus.json", s"""{ "A": [5, $wide] }""")
      val out       = run(dir, "drive", so, abi, dir / "stimulus.json")
      assert(out.contains("PY cyc=1 A=5 ABSVAL=5"))
      assert(out.contains(s"PY cyc=2 A=$wide ABSVAL=$wide"))

    test("generate: CRV samples stimulus satisfying the assumption (A is odd)"):
      val dir       = outputRoot / "AbsValUT-dpi-gen"
      val (so, abi) = build(dir, 8, Seq("--no-assert"))
      val genPath   = dir / "generated-stimulus.json"
      run(dir, "generate", so, abi, "8", "0", genPath)
      val a         = ujson.read(os.read(genPath))("A").arr.map(_.num.toInt)
      assert(a.length == 8)
      assert(a.forall(_ % 2 == 1)) // every generated value satisfies the SVA assumption
