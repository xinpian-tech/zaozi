// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.coverage

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*

import java.nio.file.{Files, Paths}
import scala.util.control.NonFatal

// M-extension: mul, mulh, mulhsu, mulhu, div, divu, rem, remu + 64-bit word variants
// Run with: mill rvprobe.runMain me.jiuyang.rvprobe.cases.coverage.RVM
@main def RVM(outputPath: String = "out/rvm.bin"): Unit =
  val n = 35

  object Mul extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isMul()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Mulh extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isMulh()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Mulhsu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isMulhsu()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Mulhu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isMulhu()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Div extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isDiv()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Divu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isDivu()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Rem extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isRem()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Remu extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isRemu()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Mulw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isMulw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Divw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isDivw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Divuw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isDivuw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Remw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isRemw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  object Remuw extends RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until n).foreach { i =>
        instruction(i, isRemuw()) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
      }
      sequence(0, n).coverBins(_.rd, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs1, (1 until 32).map(_.S))
      sequence(0, n).coverBins(_.rs2, (1 until 32).map(_.S))
      sequence(0, n).coverRAW()
      sequence(0, n).coverWAR()
      sequence(0, n).coverWAW()
      sequence(0, n).coverNoHazard()

  try Files.deleteIfExists(Paths.get(outputPath))
  catch case NonFatal(e) => System.err.println(s"warning: failed to delete ${outputPath}: ${e.getMessage}")

  Mul.writeInstructionsToFile(outputPath)
  Mulh.writeInstructionsToFile(outputPath)
  Mulhsu.writeInstructionsToFile(outputPath)
  Mulhu.writeInstructionsToFile(outputPath)
  Div.writeInstructionsToFile(outputPath)
  Divu.writeInstructionsToFile(outputPath)
  Rem.writeInstructionsToFile(outputPath)
  Remu.writeInstructionsToFile(outputPath)
  Mulw.writeInstructionsToFile(outputPath)
  Divw.writeInstructionsToFile(outputPath)
  Divuw.writeInstructionsToFile(outputPath)
  Remw.writeInstructionsToFile(outputPath)
  Remuw.writeInstructionsToFile(outputPath)
