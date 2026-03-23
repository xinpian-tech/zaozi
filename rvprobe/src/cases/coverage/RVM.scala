// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.coverage

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*
import me.jiuyang.rvprobe.cases.coverage.CoverageLib.*

// M-extension coverage: multiply/divide/remainder (13 instructions)
// Run with: mill rvprobe.runMain me.jiuyang.rvprobe.cases.coverage.RVM
@main def RVM(outputPath: String = "rvprobe/src/cases/output/coverage/RVM.S"): Unit =
  val n = 35

  // --- Base M (rv_m) ---
  object Mul extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isMul())

  object Mulh extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isMulh())

  object Mulhsu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isMulhsu())

  object Mulhu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isMulhu())

  object Div extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isDiv())

  object Divu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isDivu())

  object Rem extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isRem())

  object Remu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isRemu())

  // --- 64-bit word variants (rv64_m) ---
  object Mulw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isMulw())

  object Divw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isDivw())

  object Divuw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isDivuw())

  object Remw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isRemw())

  object Remuw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(n, isRemuw())

  writeCoverageAsm(outputPath, Mul, Mulh, Mulhsu, Mulhu, Div, Divu, Rem, Remu, Mulw, Divw, Divuw, Remw, Remuw)
