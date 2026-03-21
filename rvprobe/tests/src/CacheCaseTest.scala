// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.rvprobe.cases.cache.*

import utest.*

object CacheCaseTest extends TestSuite:
  private case class CacheCaseSpec(
    name:            String,
    generate:        String => Unit,
    mustContain:     Seq[String],
    mustContainCount: Seq[(String, Int)] = Seq.empty
  )

  private val cacheCases = Seq(
    CacheCaseSpec("DCacheHitMiss", DCacheHitMiss, Seq("lw x10, 0(x5)", "lw x11, 0(x5)", ".word 0x12345678")),
    CacheCaseSpec("DCacheLineFill", DCacheLineFill, Seq("lw x13, 60(x5)", "lw x10, 64(x5)", ".zero 256")),
    CacheCaseSpec("DCacheCrossLine", DCacheCrossLine, Seq("addi x5, x5, 60", "lw x11, 4(x5)", "ld x12, 0(x6)")),
    CacheCaseSpec("DCacheConflict", DCacheConflict, Seq("li x22, 0x1000", "add x30, x29, x22", "lw x11, 0(x6)")),
    CacheCaseSpec("DCacheLRU", DCacheLRU, Seq("lw x10, 0(x28)", "lw x11, 0(x7)", "lw x12, 0(x6)")),
    CacheCaseSpec("DCacheWriteHit", DCacheWriteHit, Seq("li x11, 0x11223344", "sw x11, 0(x5)", ".word 0x12345678")),
    CacheCaseSpec("DCacheWriteMiss", DCacheWriteMiss, Seq("li x11, 0x55667788", "sw x11, 0(x5)", ".zero 256")),
    CacheCaseSpec("DCacheWriteback", DCacheWriteback, Seq("li x11, 0xdeadbeef", "lw x10, 0(x28)", ".zero 32768")),
    CacheCaseSpec("DCachePartialWrite", DCachePartialWrite, Seq("sb x11, 1(x5)", "sh x12, 2(x5)", "lw x13, 0(x5)")),
    CacheCaseSpec("DCacheStoreLoadForward", DCacheStoreLoadForward, Seq("sw x10, 0(x5)", "lw x11, 0(x5)", ".zero 64")),
    CacheCaseSpec("DCachePartialForward", DCachePartialForward, Seq("sw x0, 0(x5)", "sb x10, 1(x5)", "lw x11, 0(x5)")),
    CacheCaseSpec("DCacheSequentialScan", DCacheSequentialScan, Seq("loop_seq:", "addi x5, x5, 4", ".zero 4096")),
    CacheCaseSpec("DCacheStride", DCacheStride, Seq("li x22, 0x40", "add x5, x5, x22", ".zero 16384")),
    CacheCaseSpec("DCacheCapacityMiss", DCacheCapacityMiss, Seq("loop1:", "loop2:", ".zero 65536")),
    CacheCaseSpec("DCacheFence", DCacheFence, Seq("fence x0, x0", "fence.tso", ".zero 64")),
    CacheCaseSpec("DCacheLrSc", DCacheLrSc, Seq("lr.w x10, x5", "sc.w x12, x5, x11", "retry:")),
    CacheCaseSpec("DCacheAmoOps", DCacheAmoOps, Seq("amoadd.w x12, x5, x11", "amoswap.w x14, x5, x13", "lw x15, 0(x5)")),
    CacheCaseSpec(
      "ICacheSequentialFetch",
      ICacheSequentialFetch,
      Seq("icache_loop:", "addi x20, x20, -1", "bnez x20, icache_loop"),
      Seq("    nop" -> 16)
    ),
    CacheCaseSpec("ICacheJumpCold", ICacheJumpCold, Seq("j target_far", ".space 256", "target_far:")),
    CacheCaseSpec("ICacheSelfModify", ICacheSelfModify, Seq("fence.i", "code_area:", "li x6, 0x200513"))
  )

  private def countOccurrences(haystack: String, needle: String): Int =
    haystack.sliding(needle.length).count(_ == needle)

  private def assertMatchesSpec(spec: CacheCaseSpec): Unit =
    val tmp = os.temp(prefix = s"${spec.name}_", suffix = ".S")
    try
      spec.generate(tmp.toString)
      val actual   = os.read(tmp)
      spec.mustContain.foreach(expected => assert(actual.contains(expected)))
      spec.mustContainCount.foreach { case (needle, expectedCount) =>
        assert(countOccurrences(actual, needle) == expectedCount)
      }
    finally
      os.remove(tmp)

  val tests = Tests:
    test("cache case outputs contain expected signatures"):
      cacheCases.foreach(assertMatchesSpec)
