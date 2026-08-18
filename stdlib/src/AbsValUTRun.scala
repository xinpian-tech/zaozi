// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

/** Command-line runner the `AbsValUT.sc` lit test drives: harness the [[AbsValUT]] module and emit the UT testbench,
  * DPI contract, shim, and closed-loop harness.
  *
  * This is a stop-gap entry — the run flow should eventually be a Generator capability rather than a hand-written main
  * — so it is deliberately thin and unpolished.
  */
object AbsValUTRun:
  def main(args: Array[String]): Unit =
    val positional = args.filterNot(_.startsWith("--"))
    val outDir     = os.Path(positional.head, os.pwd)
    val width      = args.sliding(2).collectFirst { case Array("--width", w) => w.toInt }.getOrElse(8)

    val parameter = AbsValParameter(width)
    val generator = UTGenerator(AbsValUT, parameter, cycles = 3, outputDirectory = outDir)
    val contract  = generator.freezeDpi(outDir / "AbsValDPI.json")
    os.write.over(outDir / "AbsValDPIShim.mlir", DpiShim.mlirString(contract))
    generator.simulationRequest(generator.solve(), outDir)

    // The DPI closed loop: emit the harness plus a tiny C frontend that observes the DUT
    // through its probe (a = input echo, absval = |A|) and drives A.
    val dpiDir = outDir / "dpi"
    os.makeDir.all(dpiDir)
    os.write.over(
      dpiDir / "dpi_frontend.c",
      s"""|#include <stdio.h>
          |extern "C" {
          |static int cyc = 0;
          |// Probes A (input echo) and ABSVAL (result); drives A. C params are positional, so the
          |// two A ports (probe + drive) just get distinct local names here.
          |void ${AbsValUT.moduleName(parameter)}_tick(char probeA, char probeABSVAL, char* driveA) {
          |  int drv = (cyc == 0) ? 5 : (cyc == 1) ? -3 : 7;
          |  printf("DPI-LOOP cyc=%d probe a=%d absval=%d -> drive A=%d\\n",
          |         cyc, (int)probeA, (int)(unsigned char)probeABSVAL, drv);
          |  *driveA = (char)drv;
          |  cyc++;
          |}
          |}
          |""".stripMargin
    )
    generator.dpiSimulationRequest(dpiDir, runCycles = 4, cSources = Seq(dpiDir / "dpi_frontend.c"))

    println(
      s"UT testbench, DPI contract, shim and closed-loop harness emitted for ${AbsValUT.moduleName(parameter)} in $outDir"
    )
    println(contract.toJson)
