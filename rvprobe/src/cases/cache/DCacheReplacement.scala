// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.cache

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.cache.CacheProbeLib.*

// Sequence C: force same-set eviction — conflict replacement
@main def DCacheConflict(outputPath: String): Unit =
  object DCacheConflict extends RVGenerator:
    val sets          = cacheSetsWithCounters()
    def constraints() =
      textStart()

      la(x5, "buf")
      li(x22, SameSetStrideBytes.toLong)               // stride = set_count * line_size
      sameSetAddresses(x5, x22, x6, x7, x28, x29, x30) // A/B/C/D/E (exceeds 4-way)

      lw(x10, x6, 0)  // miss, fill way 0
      lw(x10, x7, 0)  // miss, fill way 1
      lw(x10, x28, 0) // miss, fill way 2
      lw(x10, x29, 0) // miss, fill way 3
      lw(x10, x30, 0) // miss, triggers replacement

      timed(x14, x15, x16) { lw(x11, x6, 0) } // hit or miss? (was A replaced?)

      exit()
      dataBuffer("buf", ConflictRegionBytes)
      tohostSection()
  DCacheConflict.emit(outputPath)

// Sequence D: verify LRU replacement policy
@main def DCacheLRU(outputPath: String): Unit =
  object DCacheLRU extends RVGenerator:
    val sets          = cacheSetsWithCounters()
    def constraints() =
      textStart()

      la(x5, "buf")
      li(x22, SameSetStrideBytes.toLong)
      sameSetAddresses(x5, x22, x6, x7, x28) // A/B/C

      lw(x10, x6, 0)  // fill A
      lw(x10, x7, 0)  // fill B
      lw(x10, x6, 0)  // hit A → A becomes MRU
      lw(x10, x28, 0) // fill C → should evict B (LRU)

      timed(x14, x15, x16) { lw(x11, x7, 0) } // should miss (B evicted)
      timed(x14, x15, x17) { lw(x12, x6, 0) } // should hit (A kept)

      exit()
      dataBuffer("buf", ConflictRegionBytes)
      tohostSection()
  DCacheLRU.emit(outputPath)
