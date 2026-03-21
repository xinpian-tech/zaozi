// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.cache

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.cache.CacheProbeLib.*

// Sequence J: sequential scan — spatial locality test
@main def DCacheSequentialScan(outputPath: String): Unit =
  object DCacheSequentialScan extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      la(x5, "array")
      li(x20, 1024L)
      label("loop_seq")
      lw(x10, x5, 0)
      addi(x5, x5, 4)
      addi(x20, x20, -1)
      bnez(x20, "loop_seq")

      exitSeq()
      dataBuffer("array", CacheLineBytes * 64)
      tohostSection()
  DCacheSequentialScan.emit(outputPath)

// Sequence K: stride access — one load per cache line
@main def DCacheStride(outputPath: String): Unit =
  object DCacheStride extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      la(x5, "array")
      li(x20, 256L)
      li(x22, CacheLineBytes.toLong) // stride = line size
      label("loop_stride")
      lw(x10, x5, 0)
      add(x5, x5, x22)
      addi(x20, x20, -1)
      bnez(x20, "loop_stride")

      exitSeq()
      dataBuffer("array", CacheLineBytes * 256)
      tohostSection()
  DCacheStride.emit(outputPath)

// Sequence L: two-pass scan — capacity miss detection
@main def DCacheCapacityMiss(outputPath: String): Unit =
  object DCacheCapacityMiss extends RVGenerator:
    val sets          = cacheSetsWithCounters()
    def constraints() =
      textStart()

      la(x5, "big_array")
      li(x20, 8192L)     // 32KB @ 4B each
      rdcycle(x14)
      label("loop1")
      lw(x10, x5, 0)
      addi(x5, x5, 4)
      addi(x20, x20, -1)
      bnez(x20, "loop1")
      rdcycle(x15)
      sub(x16, x15, x14) // T1

      la(x5, "big_array")
      li(x20, 8192L)
      rdcycle(x14)
      label("loop2")
      lw(x10, x5, 0)
      addi(x5, x5, 4)
      addi(x20, x20, -1)
      bnez(x20, "loop2")
      rdcycle(x15)
      sub(x17, x15, x14) // T2 (compare with T1)

      exitSeq()
      dataBuffer("big_array", ConflictRegionBytes * 2)
      tohostSection()
  DCacheCapacityMiss.emit(outputPath)
