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
    generator.simulationRequest(generator.solve(), outDir)
    println(s"UT testbench and DPI contract emitted for ${AbsVal.moduleName(parameter)} in $outDir")
    println(contract.toJson)
