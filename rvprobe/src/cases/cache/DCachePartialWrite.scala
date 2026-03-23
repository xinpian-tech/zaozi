// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.cache

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.cache.CacheProbeLib.*

// Sequence H: SB/SH/SW mixed byte mask — partial write verification
@main def DCachePartialWrite(outputPath: String): Unit =
  object DCachePartialWrite extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      la(x5, "buf")
      li(x10, 0x11223344L)
      sw(x5, x10, 0)

      addi(x11, x0, 0xaa)
      sb(x5, x11, 1) // modify byte 1

      li(x12, 0xbbbbL)
      sh(x5, x12, 2) // modify bytes 2-3

      lw(x13, x5, 0) // expected: 0xBBBBAA44 (little-endian)

      exit()
      dataBuffer("buf", CacheLineBytes)
      tohostSection()
  DCachePartialWrite.emit(outputPath)
