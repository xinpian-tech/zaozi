// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.cache

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.HTIFLib.*
import me.jiuyang.rvprobe.cases.cache.CacheProbeLib.*

// Sequence A: same-address repeated reads — hit/miss latency
@main def DCacheHitMiss(outputPath: String): Unit =
  object DCacheHitMiss extends RVGenerator:
    val sets          = cacheSetsWithCounters()
    def constraints() =
      textStart()

      la(x5, "buf")
      timed(x14, x15, x16) { lw(x10, x5, 0) } // miss
      timed(x14, x15, x17) { lw(x11, x5, 0) } // hit
      lw(x12, x5, 0) // hit

      exit()
      initializedWordBuffer("buf", 0x12345678L)
      tohostSection()
  DCacheHitMiss.emit(outputPath)

// Sequence B: same-line multi-offset reads — line fill verification
@main def DCacheLineFill(outputPath: String): Unit =
  object DCacheLineFill extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      la(x5, "buf")
      lw(x10, x5, 0)                  // miss, line fill
      lw(x11, x5, 4)                  // hit (same line)
      lw(x12, x5, 8)                  // hit
      lw(x13, x5, CacheLineBytes - 4) // hit (near end of 64B line)
      lw(x10, x5, CacheLineBytes)     // miss (next line)
      lw(x11, x5, CacheLineBytes + 4) // hit

      exit()
      dataBuffer("buf", CacheLineBytes * 4)
      tohostSection()
  DCacheLineFill.emit(outputPath)

// Cross cache line boundary access with aligned word loads
@main def DCacheCrossLine(outputPath: String): Unit =
  object DCacheCrossLine extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      la(x5, "buf")
      addi(x5, x5, CacheLineBytes - 4)
      lw(x10, x5, 0) // offset 60, within line
      lw(x11, x5, 4) // offset 64, crosses into next line

      exit()
      dataBuffer("buf", CacheLineBytes * 4)
      tohostSection()
  DCacheCrossLine.emit(outputPath)
