// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.cache

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.cache.CacheProbeLib.*

// Sequence A: same-address repeated reads — hit/miss latency
@main def DCacheHitMiss(outputPath: String): Unit =
  object DCacheHitMiss extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVZICNTR())
    def constraints() =
      textStart()

      la(x5, "buf")
      timed(x14, x15, x16) { lw(x10, x5, 0) } // miss
      timed(x14, x15, x17) { lw(x11, x5, 0) } // hit
      lw(x12, x5, 0) // hit

      exitSeq()

      section(".data")
      balign(64)
      label("buf")
      word(0x12345678)
      zero(60)

      tohostSection()
  DCacheHitMiss.emit(outputPath)

// Sequence B: same-line multi-offset reads — line fill verification
@main def DCacheLineFill(outputPath: String): Unit =
  object DCacheLineFill extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR())
    def constraints() =
      textStart()

      la(x5, "buf")
      lw(x10, x5, 0)  // miss, line fill
      lw(x11, x5, 4)  // hit (same line)
      lw(x12, x5, 8)  // hit
      lw(x13, x5, 60) // hit (near end of 64B line)
      lw(x10, x5, 64) // miss (next line)
      lw(x11, x5, 68) // hit

      exitSeq()
      dataBuffer("buf", 256)
      tohostSection()
  DCacheLineFill.emit(outputPath)

// Cross cache line boundary access
@main def DCacheCrossLine(outputPath: String): Unit =
  object DCacheCrossLine extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR())
    def constraints() =
      textStart()

      la(x5, "buf")
      addi(x5, x5, 60)
      lw(x10, x5, 0) // offset 60, within line
      lw(x11, x5, 4) // offset 64, crosses into next line

      // RV64 doubleword cross-line
      la(x6, "buf")
      addi(x6, x6, 60)
      ld(x12, x6, 0) // 8-byte load straddling 64B boundary

      exitSeq()
      dataBuffer("buf", 256)
      tohostSection()
  DCacheCrossLine.emit(outputPath)
