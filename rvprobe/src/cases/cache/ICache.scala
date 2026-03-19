// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.cache

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.cache.CacheProbeLib.*

// Sequence O: sequential fetch spanning multiple I-cache lines
@main def ICacheSequentialFetch(outputPath: String): Unit =
  object ICacheSequentialFetch extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVZIFENCEI())
    def constraints() =
      textStart()

      li(x20, 100L)
      label("icache_loop")
      // 16 nops = 64 bytes = 1 I-cache line
      nop(); nop(); nop(); nop()
      nop(); nop(); nop(); nop()
      nop(); nop(); nop(); nop()
      nop(); nop(); nop(); nop()
      addi(x20, x20, -1)
      bnez(x20, "icache_loop")

      exitSeq()
      tohostSection()
  ICacheSequentialFetch.emit(outputPath)

// Sequence P: jump over cold code area — non-sequential fetch
@main def ICacheJumpCold(outputPath: String): Unit =
  object ICacheJumpCold extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVZIFENCEI())
    def constraints() =
      textStart()

      j("target_far")
      raw("    .space 256") // cold code gap spanning multiple I-cache lines
      label("target_far")
      addi(x10, x10, 1)

      exitSeq()
      tohostSection()
  ICacheJumpCold.emit(outputPath)

// Sequence Q: self-modifying code + fence.i — I-cache invalidation
@main def ICacheSelfModify(outputPath: String): Unit =
  object ICacheSelfModify extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVZIFENCEI())
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

      exitSeq()

      align(2)
      label("code_area")
      addi(x10, x0, 1)
      jalr(x0, x1, 0) // ret

      tohostSection()
  ICacheSelfModify.emit(outputPath)
