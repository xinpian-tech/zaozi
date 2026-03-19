// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.coverage

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*
import me.jiuyang.rvprobe.cases.coverage.CoverageLib.*

import java.nio.file.{Files, Paths}
import scala.util.control.NonFatal

// Load/store instruction coverage (11 instructions)
// Run with: mill rvprobe.runMain me.jiuyang.rvprobe.cases.coverage.RVLoadStore
@main def RVLoadStore(outputPath: String = "out/rvloadstore.bin"): Unit =
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

  try Files.deleteIfExists(Paths.get(outputPath))
  catch case NonFatal(e) => System.err.println(s"warning: failed to delete ${outputPath}: ${e.getMessage}")

  Lb.writeInstructionsToFile(outputPath)
  Lbu.writeInstructionsToFile(outputPath)
  Lh.writeInstructionsToFile(outputPath)
  Lhu.writeInstructionsToFile(outputPath)
  Lw.writeInstructionsToFile(outputPath)
  Lwu.writeInstructionsToFile(outputPath)
  Ld.writeInstructionsToFile(outputPath)
  Sb.writeInstructionsToFile(outputPath)
  Sh.writeInstructionsToFile(outputPath)
  Sw.writeInstructionsToFile(outputPath)
  Sd.writeInstructionsToFile(outputPath)
