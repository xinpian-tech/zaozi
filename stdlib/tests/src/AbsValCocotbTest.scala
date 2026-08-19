// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** The VPI binding: cocotb drives the *same* lib model from the *same* `port.json` as the DPI binding — through VPI
  * (`dut.drive_<name>.value` / `int(dut.probe_<name>.value)`), so it needs no DPI wrapper, only the lib model. Both
  * bindings realize the same operation ABI (poke/peek/step) and share the same drivers (`replay`, `crv.generate`).
  *
  * Requires the cocotb interpreter (`$COCOTB_PYTHON`, set by the dev shell) and `verilator`; it is skipped when cocotb
  * is unavailable.
  */
object AbsValCocotbTest extends TestSuite:
  private val outputRoot = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)
  private val frontend   = os.Path(sys.props("zaozi.stdlib.cocotbFrontend"), os.pwd)

  /** Emit `port.json` + lib model for AbsVal(8) into `dir`; returns the top module and sv sources. */
  private def emit(dir: os.Path): (String, Seq[os.Path]) =
    os.remove.all(dir)
    val gen = UTGenerator(AbsValUT, AbsValParameter(8), outputDirectory = dir)
    gen.savePorts(dir / "port.json")
    val lib = gen.emitLib(dir) // the VPI binding needs only the lib model, no DPI wrapper
    (lib.topModule, lib.sources)

  val tests: Tests = Tests:
    test("cocotb (VPI) drives the same lib model from the same port.json"):
      sys.env.get("COCOTB_PYTHON") match
        case None     => println("COCOTB_PYTHON not set (not in the dev shell) — skipping")
        case Some(py) =>
          val dir            = outputRoot / "AbsValUT-cocotb"
          val (top, sources) = emit(dir)
          os.write.over(dir / "stimulus.json", """{ "A": [5, -3, 7] }""")
          val out            = os
            .proc(py, frontend.toString, dir, top, dir / "port.json", dir / "stimulus.json", sources.map(_.toString))
            .call(cwd = dir, check = true, mergeErrIntoOut = true)
            .out
            .text()
          assert(out.contains("PY cyc=1 A=5 ABSVAL=5"))
          assert(out.contains("PY cyc=2 A=253 ABSVAL=3"))
          assert(out.contains("PY cyc=3 A=7 ABSVAL=7"))

    test("cocotb (VPI) also generates via the shared CRV — every A is odd"):
      sys.env.get("COCOTB_PYTHON") match
        case None     => println("COCOTB_PYTHON not set (not in the dev shell) — skipping")
        case Some(py) =>
          val dir            = outputRoot / "AbsValUT-cocotb-gen"
          val (top, sources) = emit(dir)
          val genPath        = dir / "generated-stimulus.json"
          os.proc(
            py,
            frontend.toString,
            "generate",
            dir,
            top,
            dir / "port.json",
            "8",
            "0",
            genPath,
            sources.map(_.toString)
          ).call(cwd = dir, check = true, mergeErrIntoOut = true)
          val a              = ujson.read(os.read(genPath))("A").arr.map(_.num.toInt)
          assert(a.length == 8)
          assert(a.forall(_ % 2 == 1))
