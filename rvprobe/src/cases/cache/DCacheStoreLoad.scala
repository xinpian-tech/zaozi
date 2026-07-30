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
// All registers are symbolic (solver-picked via FreeReg).
@main def DCacheStoreLoadForward(outputPath: String): Unit =
  object DCacheStoreLoadForward extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      val base = freshReg()
      val data = freshReg()
      la(base, "buf")         // rd = base
      addi(data, x0, 42)      // rd = data
      sw(base, data, 0)       // store: rs1 = base, rs2 = data
      lw(freshReg(), base, 0) // load: rs1 = base → forwarding

      finish()
      dataBuffer("buf", CacheLineBytes)
  DCacheStoreLoadForward.emit(outputPath)

// Sequence N: partial forwarding — byte write then full word read
@main def DCachePartialForward(outputPath: String): Unit =
  object DCachePartialForward extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      val base = freshReg()
      val data = freshReg()
      la(base, "buf")
      sw(base, x0, 0)         // zero out
      addi(data, x0, 0xaa)
      sb(base, data, 1)       // partial byte write
      lw(freshReg(), base, 0) // full word read → merge: 0x0000AA00

      finish()
      dataBuffer("buf", CacheLineBytes)
  DCachePartialForward.emit(outputPath)
