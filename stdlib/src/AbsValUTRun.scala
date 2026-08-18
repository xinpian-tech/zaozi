// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

/** Command-line runner the `AbsValUT.sc` lit test drives: harness the [[AbsValUT]] module into the single lib model and
  * emit two frontends that drive it.
  *
  * The lib harness (Verilated model) is the one artifact; a frontend is just one caller. This emits two callers of the
  * *same* model to make that concrete: a driver fed a fixed vector, and a driver fed the solver's stimulus — replay is
  * only one frontend among many.
  *
  * This is a stop-gap entry — the run flow should eventually be a Generator capability rather than a hand-written main
  * — so it is deliberately thin.
  */
object AbsValUTRun:
  def main(args: Array[String]): Unit =
    val positional = args.filterNot(_.startsWith("--"))
    val outDir     = os.Path(positional.head, os.pwd)
    val width      = args.sliding(2).collectFirst { case Array("--width", w) => w.toInt }.getOrElse(8)

    val parameter = AbsValParameter(width)
    val generator = UTGenerator(AbsValUT, parameter, cycles = 3, outputDirectory = outDir)
    val contract  = generator.saveDpi(outDir / "AbsValDPI.json")

    // Build the single lib harness — the Verilated model both frontends call.
    val lib = generator.libSimulationRequest(outDir)

    // Frontend #1: a driver fed a fixed vector (positive, negative, positive).
    val driveSeq = Seq(BigInt(5), BigInt(-3), BigInt(7)).map(v => Map("A" -> v))
    os.write.over(outDir / "frontend_drive.cpp", Frontend.driver(contract, lib.topModule, driveSeq, "DRIVE"))

    // Frontend #2: the SAME model, driven by the solver's per-cycle stimulus. Replay is just
    // another frontend — the harness is unchanged.
    val stimulus  = generator.solve()
    val replaySeq = (0 until stimulus.cycles).map(cycle =>
      contract.drive.map(port => port.name -> stimulus.io.field(port.name).at(cycle)).toMap
    )
    os.write.over(outDir / "frontend_replay.cpp", Frontend.driver(contract, lib.topModule, replaySeq, "REPLAY"))

    println(s"lib model ${lib.topModule} and two frontends emitted for ${AbsValUT.moduleName(parameter)} in $outDir")
    println(contract.toJson)
