// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.cache

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.tpe.FreeReg
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

      val base = freshReg()
      val data = freshReg()
      la(base, "buf")
      lw(freshReg(), base, 0) // pull into cache
      addi(data, x0, 42)
      sw(base, data, 0)       // write hit
      lw(freshReg(), base, 0) // read back

      finish()
      initializedWordBuffer("buf", 0x12345678L)
  DCacheWriteHit.emit(outputPath)

// Sequence F: write miss — write to untouched address
@main def DCacheWriteMiss(outputPath: String): Unit =
  object DCacheWriteMiss extends RVGenerator:
    val sets          = cacheSetsWithCounters()
    def constraints() =
      textStart()

      val base = freshReg()
      val data = freshReg()
      la(base, "buf2")
      li(data, 0x55667788L)
      timed(x14, x15, x16) { sw(base, data, 0) } // write miss
      timed(x14, x15, x17) { lw(freshReg(), base, 0) } // if write-allocate → hit

      finish()
      dataBuffer("buf2", CacheLineBytes * 4)
  DCacheWriteMiss.emit(outputPath)

// Sequence G: dirty writeback — dirty line eviction
@main def DCacheWriteback(outputPath: String): Unit =
  object DCacheWriteback extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      val data = freshReg()
      la(x5, "buf")
      li(x22, SameSetStrideBytes.toLong)
      sameSetAddresses(x5, x22, x6, x7, x28) // A/B/C — infrastructure regs stay fixed

      lw(freshReg(), x6, 0) // fill A
      addi(data, x0, 42)
      sw(x6, data, 0)       // dirty A

      lw(freshReg(), x7, 0)  // fill B
      lw(freshReg(), x28, 0) // fill C → evict A (writeback)

      fence(x0, x0, 3, 3, 0) // fence rw, rw
      lw(freshReg(), x6, 0)  // re-read A

      finish()
      dataBuffers("buf" -> ConflictRegionBytes, "buf2" -> (CacheLineBytes * 4))
  DCacheWriteback.emit(outputPath)
