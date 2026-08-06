// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

/** Run an already-built, simulator-independent request on the configured backend. */
object Simulation:

  def run(request: SimulationRequest): RunResult =
    Toolchain.check()
    os.makeDir.all(request.workDir)
    Toolchain.simulator.simulate(request)
