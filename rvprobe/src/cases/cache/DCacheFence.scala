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

      val base1 = freshReg()
      val base2 = freshReg()
      val data1 = freshReg()
      val data2 = freshReg()

      la(base1, "buf")
      addi(data1, x0, 42)
      sw(base1, data1, 0)
      fence(x0, x0, 3, 3, 0)   // fence rw, rw
      lw(freshReg(), base1, 0) // should see stored value

      la(base2, "buf2")
      addi(data2, x0, 99)
      sw(base2, data2, 0)
      fenceTso(x0, x0)         // fence.tso
      lw(freshReg(), base2, 0) // should see stored value

      finish()
      dataBuffers("buf" -> CacheLineBytes, "buf2" -> CacheLineBytes)
  DCacheFence.emit(outputPath)
