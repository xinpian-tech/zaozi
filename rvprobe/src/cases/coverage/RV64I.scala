// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.coverage

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*
import me.jiuyang.rvprobe.cases.coverage.CoverageLib.*

// RV64I word-width operations coverage (9 instructions)
// Run with: mill rvprobe.runMain me.jiuyang.rvprobe.cases.coverage.RV64I
@main def RV64I(outputPath: String = "rvprobe/src/cases/output/asm/coverage/RV64I.S"): Unit =
  val n = 35

  // --- R-type word ---
  object Addw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isAddw())

  object Subw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isSubw())

  object Sllw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isSllw())

  object Srlw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isSrlw())

  object Sraw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isSraw())

  // --- I-type word ---
  object Addiw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = iTypeAlu(n, isAddiw())

  // --- Shift-immediate word ---
  object Slliw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = shiftImm(n, isSlliw())

  object Srliw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = shiftImm(n, isSrliw())

  object Sraiw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = shiftImm(n, isSraiw())

  writeCoverageAsm(outputPath, Addw, Subw, Sllw, Srlw, Sraw, Addiw, Slliw, Srliw, Sraiw)
