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

// Sequence H: SB/SH/SW mixed byte mask — partial write verification
@main def DCachePartialWrite(outputPath: String): Unit =
  object DCachePartialWrite extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      val base = freshReg()
      val v1   = freshReg()
      val v2   = freshReg()
      val v3   = freshReg()
      la(base, "buf")
      addi(v1, x0, 0x44)
      sw(base, v1, 0)

      addi(v2, x0, 0xaa)
      sb(base, v2, 1) // modify byte 1

      addi(v3, x0, 0xbb)
      sh(base, v3, 2) // modify bytes 2-3

      lw(freshReg(), base, 0) // expected: 0x00BB_AA44 (little-endian)

      finish()
      dataBuffer("buf", CacheLineBytes)
  DCachePartialWrite.emit(outputPath)
