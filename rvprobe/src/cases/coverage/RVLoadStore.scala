// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.coverage

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*
import me.jiuyang.rvprobe.cases.coverage.CoverageLib.*

// Load/store instruction coverage (11 instructions)
// Run with: mill rvprobe.runMain me.jiuyang.rvprobe.cases.coverage.RVLoadStore
@main def RVLoadStore(outputPath: String = "rvprobe/src/cases/output/asm/coverage/RVLoadStore.S"): Unit =
  val n = 35

  // --- Loads ---
  object Lb extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = load(n, isLb())

  object Lbu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = load(n, isLbu())

  object Lh extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = load(n, isLh())

  object Lhu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = load(n, isLhu())

  object Lw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = load(n, isLw())

  object Lwu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = load(n, isLwu())

  object Ld extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = load(n, isLd())

  // --- Stores ---
  object Sb extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = store(n, isSb())

  object Sh extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = store(n, isSh())

  object Sw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = store(n, isSw())

  object Sd extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = store(n, isSd())

  writeCoverageAsm(outputPath, Lb, Lbu, Lh, Lhu, Lw, Lwu, Ld, Sb, Sh, Sw, Sd)
