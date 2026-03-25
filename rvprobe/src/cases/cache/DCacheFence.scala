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

// Sequence R: fence rw/tso semantics
@main def DCacheFence(outputPath: String): Unit =
  object DCacheFence extends RVGenerator:
    val sets          = cacheSetsWithFenceI()
    def constraints() =
      textStart()

      la(x5, "buf")
      val i1 = addi(FreeReg, x0, 42) // rd symbolic
      sw(x5, instruction(i1).rd, 0)
      fence(x0, x0, 3, 3, 0) // fence rw, rw
      lw(x11, x5, 0) // should see stored value

      la(x6, "buf2")
      val i2 = addi(FreeReg, x0, 99) // rd symbolic
      sw(x6, instruction(i2).rd, 0)
      fenceTso(x0, x0) // fence.tso
      lw(x12, x6, 0) // should see stored value

      exit()
      dataBuffers("buf" -> CacheLineBytes, "buf2" -> CacheLineBytes)
      tohostSection()
  DCacheFence.emit(outputPath)
