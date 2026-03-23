// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.cache

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.cache.CacheProbeLib.*

// Sequence M: store-load forwarding — immediate load after store
@main def DCacheStoreLoadForward(outputPath: String): Unit =
  object DCacheStoreLoadForward extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      la(x5, "buf")
      li(x10, 0x12345678L)
      sw(x5, x10, 0)
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
      sw(x5, x0, 0)  // zero out
      addi(x10, x0, 0xaa)
      sb(x5, x10, 1) // partial byte write
      lw(x11, x5, 0) // full word read → merge: 0x0000AA00

      exit()
      dataBuffer("buf", CacheLineBytes)
      tohostSection()
  DCachePartialForward.emit(outputPath)
