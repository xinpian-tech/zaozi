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

      val base = freshReg()
      la(base, "buf")
      timed(x14, x15, x16) { lw(freshReg(), base, 0) } // miss
      timed(x14, x15, x17) { lw(freshReg(), base, 0) } // hit
      lw(freshReg(), base, 0) // hit

      finish()
      initializedWordBuffer("buf", 0x12345678L)
  DCacheHitMiss.emit(outputPath)

// Sequence B: same-line multi-offset reads — line fill verification
@main def DCacheLineFill(outputPath: String): Unit =
  object DCacheLineFill extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      val base = freshReg()
      la(base, "buf")
      lw(freshReg(), base, 0)                  // miss, line fill
      lw(freshReg(), base, 4)                  // hit (same line)
      lw(freshReg(), base, 8)                  // hit
      lw(freshReg(), base, CacheLineBytes - 4) // hit (near end of 64B line)
      lw(freshReg(), base, CacheLineBytes)     // miss (next line)
      lw(freshReg(), base, CacheLineBytes + 4) // hit

      finish()
      dataBuffer("buf", CacheLineBytes * 4)
  DCacheLineFill.emit(outputPath)

// Cross cache line boundary access with aligned word loads
@main def DCacheCrossLine(outputPath: String): Unit =
  object DCacheCrossLine extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      val base = freshReg()
      la(base, "buf")
      addi(base, base, CacheLineBytes - 4)
      lw(freshReg(), base, 0) // offset 60, within line
      lw(freshReg(), base, 4) // offset 64, crosses into next line

      finish()
      dataBuffer("buf", CacheLineBytes * 4)
  DCacheCrossLine.emit(outputPath)
