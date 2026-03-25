// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.cache

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.HTIFLib.*
import me.jiuyang.rvprobe.cases.cache.CacheProbeLib.*

// Sequence E: write hit — pull into cache then write
@main def DCacheWriteHit(outputPath: String): Unit =
  object DCacheWriteHit extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      la(x5, "buf")
      lw(x10, x5, 0) // pull into cache
      li(x11, 0x11223344L)
      sw(x5, x11, 0) // write hit
      lw(x12, x5, 0) // read back → 0x11223344

      exit()
      initializedWordBuffer("buf", 0x12345678L)
      tohostSection()
  DCacheWriteHit.emit(outputPath)

// Sequence F: write miss — write to untouched address
@main def DCacheWriteMiss(outputPath: String): Unit =
  object DCacheWriteMiss extends RVGenerator:
    val sets          = cacheSetsWithCounters()
    def constraints() =
      textStart()

      la(x5, "buf2")
      li(x11, 0x55667788L)
      timed(x14, x15, x16) { sw(x5, x11, 0) } // write miss
      timed(x14, x15, x17) { lw(x12, x5, 0) } // if write-allocate → hit

      exit()
      dataBuffer("buf2", CacheLineBytes * 4)
      tohostSection()
  DCacheWriteMiss.emit(outputPath)

// Sequence G: dirty writeback — dirty line eviction
@main def DCacheWriteback(outputPath: String): Unit =
  object DCacheWriteback extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      la(x5, "buf")
      li(x22, SameSetStrideBytes.toLong)
      sameSetAddresses(x5, x22, x6, x7, x28) // A/B/C

      lw(x10, x6, 0) // fill A
      li(x11, 0xdeadbeefL)
      sw(x6, x11, 0) // dirty A

      lw(x10, x7, 0)  // fill B
      lw(x10, x28, 0) // fill C → evict A (writeback)

      fence(x0, x0, 3, 3, 0) // fence rw, rw
      lw(x12, x6, 0)         // re-read A → should be 0xDEADBEEF

      exit()
      dataBuffers("buf" -> ConflictRegionBytes, "buf2" -> (CacheLineBytes * 4))
      tohostSection()
  DCacheWriteback.emit(outputPath)
