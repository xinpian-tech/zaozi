// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.cache

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.HTIFLib.*
import me.jiuyang.rvprobe.cases.cache.CacheProbeLib.*

// Sequence O: sequential fetch spanning multiple I-cache lines
@main def ICacheSequentialFetch(outputPath: String): Unit =
  object ICacheSequentialFetch extends RVGenerator:
    val sets          = cacheSetsWithFenceI()
    def constraints() =
      textStart()

      li(x20, 100L)
      label("icache_loop")
      // 16 nops = 64 bytes = 1 I-cache line
      nops(CacheLineBytes / 4)
      addi(x20, x20, -1)
      bnez(x20, "icache_loop")

      finish()
  ICacheSequentialFetch.emit(outputPath)

// Sequence P: jump over cold code area — non-sequential fetch
@main def ICacheJumpCold(outputPath: String): Unit =
  object ICacheJumpCold extends RVGenerator:
    val sets          = cacheSetsWithFenceI()
    def constraints() =
      textStart()

      j("target_far")
      space(CacheLineBytes * 4) // cold code gap spanning multiple I-cache lines
      label("target_far")
      addi(x10, x10, 1)

      finish()
  ICacheJumpCold.emit(outputPath)

// Sequence Q: self-modifying code + fence.i — I-cache invalidation
@main def ICacheSelfModify(outputPath: String): Unit =
  object ICacheSelfModify extends RVGenerator:
    val sets          = cacheSetsWithFenceI()
    def constraints() =
      textStart()

      jal(x1, "code_area") // execute original (addi x10, x0, 1; ret)
      // x10 = 1

      la(x5, "code_area")
      li(x6, 0x00200513L) // encoding of "addi x10, x0, 2"
      sw(x5, x6, 0)       // overwrite instruction

      fenceI(x0, x0, 0) // invalidate I-cache

      jal(x1, "code_area") // execute modified
      // x10 = 2

      exit()

      balign(8)
      label("code_area")
      dword(0x0000806700100513L) // addi a0, x0, 1; ret

      tohostSection()
  ICacheSelfModify.emit(outputPath)
