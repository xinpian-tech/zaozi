// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.coverage

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*

import java.nio.file.{Files, Paths}
import scala.util.control.NonFatal

// Load/store coverage: lb, lbu, lh, lhu, lw, lwu, ld, sb, sh, sw, sd
// Covers register bins and immediate boundary values for offsets.
// Run with: mill rvprobe.runMain me.jiuyang.rvprobe.cases.coverage.RVLoadStore
@main def RVLoadStore(outputPath: String = "out/rvloadstore.bin"): Unit =
  val n = 35

  // --- Loads ---

  object Lb extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isLb()) { rdRange(1, 32) & rs1Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.imm12, Seq((-2048).S, (-1).S, 0.S, 1.S, 2047.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverNoHazard()

  object Lbu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isLbu()) { rdRange(1, 32) & rs1Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.imm12, Seq((-2048).S, (-1).S, 0.S, 1.S, 2047.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverNoHazard()

  object Lh extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isLh()) { rdRange(1, 32) & rs1Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.imm12, Seq((-2048).S, (-1).S, 0.S, 1.S, 2047.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverNoHazard()

  object Lhu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isLhu()) { rdRange(1, 32) & rs1Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.imm12, Seq((-2048).S, (-1).S, 0.S, 1.S, 2047.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverNoHazard()

  object Lw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isLw()) { rdRange(1, 32) & rs1Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.imm12, Seq((-2048).S, (-1).S, 0.S, 1.S, 2047.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverNoHazard()

  object Lwu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isLwu()) { rdRange(1, 32) & rs1Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.imm12, Seq((-2048).S, (-1).S, 0.S, 1.S, 2047.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverNoHazard()

  object Ld extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isLd()) { rdRange(1, 32) & rs1Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.imm12, Seq((-2048).S, (-1).S, 0.S, 1.S, 2047.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverNoHazard()

  // --- Stores ---
  // Stores have split immediates (imm12hi/imm12lo), so we cover rs1/rs2 register bins.

  object Sb extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isSb()) { rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverNoHazard()

  object Sh extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isSh()) { rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverNoHazard()

  object Sw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isSw()) { rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverNoHazard()

  object Sd extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isSd()) { rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverNoHazard()

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
