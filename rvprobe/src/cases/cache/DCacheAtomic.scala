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

// Sequence U: lr/sc spin lock
@main def DCacheLrSc(outputPath: String): Unit =
  object DCacheLrSc extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      val lock     = freshReg()
      val lrVal    = freshReg()
      val scOne    = freshReg()
      val scResult = freshReg()
      val shared   = freshReg()
      val sdata    = freshReg()

      la(lock, "lock")
      label("retry")
      lrW(lrVal, lock, 0, 1)           // lr.w — aq=1
      bnez(lrVal, "retry")
      addi(scOne, x0, 1)
      scW(scResult, lock, scOne, 0, 0) // sc.w
      bnez(scResult, "retry")

      // critical section
      la(shared, "shared_data")
      li(sdata, 0xbeefL)
      sw(shared, sdata, 0)

      // release
      sw(lock, x0, 0)
      fence(x0, x0, 3, 3, 0)

      finish()
      dataBuffers("lock" -> CacheLineBytes, "shared_data" -> CacheLineBytes)
  DCacheLrSc.emit(outputPath)

// AMO operations: amoadd.w, amoswap.w
@main def DCacheAmoOps(outputPath: String): Unit =
  object DCacheAmoOps extends RVGenerator:
    val sets          = cacheSetsWithCsr()
    def constraints() =
      textStart()

      val base    = freshReg()
      val init    = freshReg()
      val addVal  = freshReg()
      val swapVal = freshReg()

      la(base, "amo_buf")
      li(init, 100L)
      sw(base, init, 0)

      addi(addVal, x0, 5)
      amoaddW(freshReg(), base, addVal, 0, 0) // old=100, mem=105

      li(swapVal, 200L)
      amoswapW(freshReg(), base, swapVal, 0, 0) // old=105, mem=200

      lw(freshReg(), base, 0) // should read 200

      finish()
      dataBuffer("amo_buf", CacheLineBytes)
  DCacheAmoOps.emit(outputPath)
