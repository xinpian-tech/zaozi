// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.cache

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.cache.CacheProbeLib.*

// Sequence R: fence rw/tso semantics
@main def DCacheFence(outputPath: String): Unit =
  object DCacheFence extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVZIFENCEI())
    def constraints() =
      textStart()

      la(x5, "buf")
      li(x10, 0xcafebabeL)
      sw(x5, x10, 0)
      fence(x0, x0, 3, 3, 0) // fence rw, rw
      lw(x11, x5, 0)         // should see stored value

      la(x6, "buf2")
      li(x10, 0xdeadcafeL)
      sw(x6, x10, 0)
      fenceTso(x0, x0) // fence.tso
      lw(x12, x6, 0)   // should see stored value

      exitSeq()
      dataBuffers("buf" -> 64, "buf2" -> 64)
      tohostSection()
  DCacheFence.emit(outputPath)
