// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.cache

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.cache.CacheProbeLib.*

// Sequence U: lr/sc spin lock
@main def DCacheLrSc(outputPath: String): Unit =
  object DCacheLrSc extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR())
    def constraints() =
      textStart()

      la(x5, "lock")
      label("retry")
      lrW(x10, x5, 0, 1)      // lr.w x10, (x5) — aq=1
      bnez(x10, "retry")
      addi(x11, x0, 1)
      scW(x12, x5, x11, 0, 0) // sc.w x12, x11, (x5)
      bnez(x12, "retry")

      // critical section
      la(x6, "shared_data")
      li(x13, 0xbeefL)
      sw(x6, x13, 0)

      // release
      sw(x5, x0, 0)
      fence(x0, x0, 3, 3, 0) // fence rw, rw

      exitSeq()
      dataBuffers("lock" -> 64, "shared_data" -> 64)
      tohostSection()
  DCacheLrSc.emit(outputPath)

// AMO operations: amoadd.w, amoswap.w
@main def DCacheAmoOps(outputPath: String): Unit =
  object DCacheAmoOps extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR())
    def constraints() =
      textStart()

      la(x5, "amo_buf")
      li(x10, 100L)
      sw(x5, x10, 0)

      addi(x11, x0, 5)
      amoaddW(x12, x5, x11, 0, 0) // old=100, mem=105

      li(x13, 200L)
      amoswapW(x14, x5, x13, 0, 0) // old=105, mem=200

      lw(x15, x5, 0) // should read 200

      exitSeq()
      dataBuffer("amo_buf", 64)
      tohostSection()
  DCacheAmoOps.emit(outputPath)
