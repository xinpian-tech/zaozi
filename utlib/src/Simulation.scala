// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

/** Elaborate a harness, emit its SystemVerilog, and run it on the configured simulator.
  *
  * This is the simulator-agnostic half: everything here is true whichever backend [[Toolchain.simulator]] resolves to.
  */
object Simulation:

  /** Build and run the harness.
    *
    * `trace` adds a waveform of the whole design. It costs build time and disk, so it is off by default and turned on
    * when a run needs debugging rather than for every run in a suite.
    */
  def run(parameter: HarnessParameter, outDir: os.Path, trace: Boolean = false): RunResult =
    Toolchain.check()
    os.makeDir.all(outDir)
    Toolchain.simulator.simulate(
      SimulationRequest(
        sources = Harness.emit(parameter, outDir, trace),
        workDir = outDir,
        topModule = "top",
        trace = trace,
        traceFile = Harness.traceFileName,
        coverageFile = "coverage.dat"
      )
    )
