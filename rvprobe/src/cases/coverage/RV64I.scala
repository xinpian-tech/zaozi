// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.coverage

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*

import java.nio.file.{Files, Paths}
import scala.util.control.NonFatal

// RV64I word-width operations: addw, subw, sllw, srlw, sraw, addiw, slliw, srliw, sraiw
// Run with: mill rvprobe.runMain me.jiuyang.rvprobe.cases.coverage.RV64I
@main def RV64I(outputPath: String = "out/rv64i.bin"): Unit =
  val n = 35

  object Addw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isAddw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Subw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isSubw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Sllw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isSllw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Srlw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isSrlw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Sraw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isSraw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Addiw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isAddiw()) { rdRange(1, 32) & rs1Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.imm12, Seq((-1).S, 0.S, 1.S, (-2048).S, 2047.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Slliw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isSlliw()) { rdRange(1, 32) & rs1Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Srliw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isSrliw()) { rdRange(1, 32) & rs1Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Sraiw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isSraiw()) { rdRange(1, 32) & rs1Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

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
