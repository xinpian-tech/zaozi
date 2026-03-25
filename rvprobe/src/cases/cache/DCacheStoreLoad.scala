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

// Sequence M: store-load forwarding — immediate load after store
@main def DCacheStoreLoadForward(outputPath: String): Unit =
  object DCacheStoreLoadForward extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      la(x5, "buf")
      val i = addi(FreeReg, x0, 42) // rd symbolic
      sw(x5, instruction(i).rd, 0)
      lw(x11, x5, 0) // immediate load → forwarding

      exit()
      dataBuffer("buf", CacheLineBytes)
      tohostSection()
  DCacheStoreLoadForward.emit(outputPath)

// Sequence N: partial forwarding — byte write then full word read
@main def DCachePartialForward(outputPath: String): Unit =
  object DCachePartialForward extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      la(x5, "buf")
      sw(x5, x0, 0) // zero out
      val i = addi(FreeReg, x0, 0xaa) // rd symbolic
      sb(x5, instruction(i).rd, 1) // partial byte write — rs2 = addi's rd
      lw(x11, x5, 0) // full word read → merge: 0x0000AA00

      exit()
      dataBuffer("buf", CacheLineBytes)
      tohostSection()
  DCachePartialForward.emit(outputPath)
