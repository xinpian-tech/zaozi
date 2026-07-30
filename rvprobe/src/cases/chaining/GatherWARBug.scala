// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.chaining

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.*
import me.jiuyang.rvprobe.cases.HTIFLib.*
import me.jiuyang.rvprobe.cases.chaining.ChainingLib.*

// D3×C6 Gather WAR Bug — Complete T1-compatible test.
//
// Demonstrates RVProbe's white-box constraint composition:
//   - Architectural constraint: WAR dependency (B writes what A reads)
//   - Microarchitectural constraint: isGather() from T1 Object Model
//   - Sequence-level: back-to-back gather + ALU write
//
// The eDSL generates a complete test sequence:
//   1. vsetvli configuration
//   2. Source data initialization (vid.v)
//   3. Non-sequential index vector (vid XOR (vl-1) → reversed)
//   4. Reference gather (safe baseline)
//   5. WAR pair: vrgather.vv + vadd.vv (solver-constrained)
//   6. Result verification (vmsne + vcpop)
//
// All scalar registers are solver-allocated via freshReg().
// The critical WAR pair uses solver-constrained instruction() calls
// with isVrgatherVv() and isVaddVv() opcodes.
//
// Confirmed to trigger data corruption on pre-fix T1 (097ec761):
//   44 corrupted VRF writes (0x7CE) in RTL event trace
//   vs 0 corrupted writes on post-fix (50986c9d)
//
// Run:
//   mill rvprobe.runMain me.jiuyang.rvprobe.cases.chaining.GatherWARBug <output.S>
//
// Build for T1:
//   riscv64-*-gcc -march=rv32gcv -mabi=ilp32d -nostdlib -nostartfiles \
//     -T t1.ld t1_main.S stubs.S GatherWARBug.S -o gather_war.elf
@main def GatherWARBug(outputPath: String): Unit =
  object GatherWARBugTest extends RVGenerator:
    val sets: Seq[Recipe ?=> SetConstraint] = Seq(isRVI(), isRVV())
    def constraints() =
      useFixed(x5, x6)
      textStart()
      // Setup and WAR pair use raw() to avoid index conflicts with finish()
      war_GatherxALU_full()
      raw("ret")

  GatherWARBugTest.emit(outputPath)
