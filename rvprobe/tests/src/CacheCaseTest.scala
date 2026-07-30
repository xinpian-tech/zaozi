// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.rvprobe.cases.cache.*

import utest.*

object CacheCaseTest extends TestSuite:
  private case class CacheCaseSpec(
    name:             String,
    generate:         String => Unit,
    mustContain:      Seq[String],
    mustContainCount: Seq[(String, Int)] = Seq.empty)

  private val cacheCases = Seq(
    CacheCaseSpec("DCacheHitMiss", DCacheHitMiss, Seq("lw", ".word 0x12345678")),
    CacheCaseSpec("DCacheLineFill", DCacheLineFill, Seq("lw", ".zero 256")),
    CacheCaseSpec("DCacheCrossLine", DCacheCrossLine, Seq("addi", "lw")),
    CacheCaseSpec("DCacheConflict", DCacheConflict, Seq("li x22, 0x1000", "add x30, x29, x22", "lw x11, 0(x6)")),
    CacheCaseSpec("DCacheLRU", DCacheLRU, Seq("lw x10, 0(x28)", "lw x11, 0(x7)", "lw x12, 0(x6)")),
    CacheCaseSpec("DCacheWriteHit", DCacheWriteHit, Seq("sw", "lw", ".word 0x12345678")),
    CacheCaseSpec("DCacheWriteMiss", DCacheWriteMiss, Seq("sw", "lw", ".zero 256")),
    CacheCaseSpec("DCacheWriteback", DCacheWriteback, Seq("sw", "fence rw, rw", ".zero 32768")),
    CacheCaseSpec("DCachePartialWrite", DCachePartialWrite, Seq("sb", "sh", "lw")),
    CacheCaseSpec("DCacheStoreLoadForward", DCacheStoreLoadForward, Seq("sw", "lw", ".zero 64")),
    CacheCaseSpec("DCachePartialForward", DCachePartialForward, Seq("sw", "sb", "lw")),
    CacheCaseSpec("DCacheSequentialScan", DCacheSequentialScan, Seq("loop_seq:", "addi x5, x5, 4", ".zero 4096")),
    CacheCaseSpec("DCacheStride", DCacheStride, Seq("li x22, 0x40", "add x5, x5, x22", ".zero 16384")),
    CacheCaseSpec(
      "DCacheCapacityMiss",
      DCacheCapacityMiss,
      Seq("loop1:", "ld x10, 0(x5)", "addi x5, x5, 8", ".zero 65536")
    ),
    CacheCaseSpec("DCacheFence", DCacheFence, Seq("fence rw, rw", "fence.tso")),
    CacheCaseSpec("DCacheLrSc", DCacheLrSc, Seq("lr.w.aq", "sc.w", "retry:")),
    CacheCaseSpec("DCacheAmoOps", DCacheAmoOps, Seq("amoadd.w", "amoswap.w", "lw")),
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
      val actual = os.read(tmp)
      spec.mustContain.foreach(expected => assert(actual.contains(expected)))
      spec.mustContainCount.foreach { case (needle, expectedCount) =>
        assert(countOccurrences(actual, needle) == expectedCount)
      }
    finally os.remove(tmp)

  val tests = Tests:
    test("cache case outputs contain expected signatures"):
      cacheCases.foreach(assertMatchesSpec)
