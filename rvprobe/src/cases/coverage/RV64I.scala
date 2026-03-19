// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.coverage

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*
import me.jiuyang.rvprobe.cases.coverage.CoverageLib.*

import java.nio.file.{Files, Paths}
import scala.util.control.NonFatal

// RV64I word-width operations coverage (9 instructions)
// Run with: mill rvprobe.runMain me.jiuyang.rvprobe.cases.coverage.RV64I
@main def RV64I(outputPath: String = "out/rv64i.bin"): Unit =
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

  try Files.deleteIfExists(Paths.get(outputPath))
  catch case NonFatal(e) => System.err.println(s"warning: failed to delete ${outputPath}: ${e.getMessage}")

  Addw.writeInstructionsToFile(outputPath)
  Subw.writeInstructionsToFile(outputPath)
  Sllw.writeInstructionsToFile(outputPath)
  Srlw.writeInstructionsToFile(outputPath)
  Sraw.writeInstructionsToFile(outputPath)
  Addiw.writeInstructionsToFile(outputPath)
  Slliw.writeInstructionsToFile(outputPath)
  Srliw.writeInstructionsToFile(outputPath)
  Sraiw.writeInstructionsToFile(outputPath)
