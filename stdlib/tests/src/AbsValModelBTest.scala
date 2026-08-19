// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** Model B end-to-end: the sim-dialect harness (calling `import "DPI-C" <dut>_tick` each cycle) plus the tiny
  * clock-oscillator top and the generated tick callback, built with `verilator --binary`. The SV owns the loop; the
  * callback (generated from `abi.json`) supplies the per-cycle drive from `stimulus.txt` and observes the DUT's probes.
  */
object AbsValModelBTest extends TestSuite:
  private val outputRoot   = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)
  private val verilatorBin = sys.env.getOrElse("VERILATOR", "verilator")

  val tests: Tests = Tests:
    test("the sim-dialect harness + tick callback drive the DUT"):
      val dir = outputRoot / "AbsValUT-modelB"
      os.remove.all(dir)
      val gen = UTGenerator(AbsValUT, AbsValParameter(8), outputDirectory = dir)
      val tb  = gen.emitTestbench(dir, runCycles = 4)
      os.write.over(dir / "stimulus.txt", "5\n-3\n7\n9\n")

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
      // The probe observed each cycle is the DUT's response to the *previous* drive (the DPI call
      // is clocked): drive 5 at cyc 1 -> observe A=5, ABSVAL=5 at cyc 2, and so on.
      assert(out.contains("TB cyc=2 A=5 ABSVAL=5 assumeOk=1 drive=-3"))
      assert(out.contains("TB cyc=3 A=-3 ABSVAL=3 assumeOk=1 drive=7"))
      assert(out.contains("TB cyc=4 A=7 ABSVAL=7 assumeOk=1 drive=9"))
      assert(out.contains("HARNESS-DONE"))
