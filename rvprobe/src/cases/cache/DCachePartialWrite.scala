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

      la(x5, "buf")
      val i1 = addi(FreeReg, x0, 0x44) // rd symbolic
      sw(x5, instruction(i1).rd, 0)

      val i2 = addi(FreeReg, x0, 0xaa) // rd symbolic
      sb(x5, instruction(i2).rd, 1) // modify byte 1

      val i3 = addi(FreeReg, x0, 0xbb) // rd symbolic
      sh(x5, instruction(i3).rd, 2) // modify bytes 2-3

      lw(x13, x5, 0) // expected: 0x00BB_AA44 (little-endian)

      exit()
      dataBuffer("buf", CacheLineBytes)
      tohostSection()
  DCachePartialWrite.emit(outputPath)
