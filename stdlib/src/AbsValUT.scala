// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

/** The command-line entry the `AbsValUT.sc` lit test drives: solve the UT constraints for
  * [[AbsVal]] and emit the generated SystemVerilog testbench — the harness (with its
  * sim-dialect-lowered `$fwrite`/`$finish` control) and the minimal clock/reset driver top —
  * into the given directory.
  *
  * Running the simulation itself belongs in the lit test, which invokes Verilator over the
  * emitted SV and checks for `HARNESS-DONE`. Nothing here runs a simulator; it stops at SV
  * emission, mirroring how `AbsVal`'s own `design` step stops at `.mlirbc`.
  */
object AbsValUT:
  def main(args: Array[String]): Unit =
    val positional = args.filterNot(_.startsWith("--"))
    val outDir     = os.Path(positional.head, os.pwd)
    val width      = args.sliding(2).collectFirst { case Array("--width", w) => w.toInt }.getOrElse(8)

    val parameter = AbsValParameter(width)
    val generator = UTGenerator(AbsVal, parameter, cycles = 3, outputDirectory = outDir)
    val contract  = generator.freezeDpi(outDir / "AbsValDPI.json")
    os.write.over(outDir / "AbsValDPIShim.mlir", DpiShim.mlirString(contract))
    generator.simulationRequest(generator.solve(), outDir)

    // The DPI closed loop: emit the harness that hands each cycle to the external frontend,
    // and a tiny C frontend that drives A and observes the DUT's ABSVAL — placed next to the
    // emitted SV so Verilator compiles them together.
    val dpiDir = outDir / "dpi"
    os.makeDir.all(dpiDir)
    os.write.over(
      dpiDir / "dpi_frontend.c",
      s"""|#include <stdio.h>
          |extern "C" {
          |static int cyc = 0;
          |// Observes the DUT via its probe (a = input echo, absval = |A|); drives A.
          |void ${AbsVal.moduleName(parameter)}_tick(char a, char absval, char* A) {
          |  int drv = (cyc == 0) ? 5 : (cyc == 1) ? -3 : 7;
          |  printf("DPI-LOOP cyc=%d probe a=%d absval=%d -> drive A=%d\\n",
          |         cyc, (int)a, (int)(unsigned char)absval, drv);
          |  *A = (char)drv;
          |  cyc++;
          |}
          |}
          |""".stripMargin
    )
    generator.dpiSimulationRequest(dpiDir, runCycles = 4, cSources = Seq(dpiDir / "dpi_frontend.c"))

    println(s"UT testbench, DPI contract, shim and closed-loop harness emitted for ${AbsVal.moduleName(parameter)} in $outDir")
    println(contract.toJson)
