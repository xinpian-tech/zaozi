// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.unittest.{Driver, RvvInsn, RvvInsnRegistry}

import java.nio.file.Files

import utest.*

/** Regressions for Codex round-16 review fixed in round 17. */
object Round17FixesTest extends TestSuite:

  private def newCli(out: String): Driver.Cli =
    Driver.Cli(vlen = 256, xlen = 64, stage1OutputDir = out, march = "rv64gcv")

  private def emittedTestdataBytes(content: String): Int =
    val lines = content.split("\n").iterator
    var afterTestdata = false
    var afterDataEnd  = false
    var total         = 0
    for line <- lines if !afterDataEnd do
      val t = line.trim
      if t == "testdata:" then afterTestdata = true
      else if t == "RVTEST_DATA_END" then afterDataEnd = true
      else if afterTestdata then
        if t.startsWith(".byte ") then
          total += t.stripPrefix(".byte ").split(",").length
        else if t.startsWith(".zero ") then
          total += t.stripPrefix(".zero ").trim.toInt
    total

  val tests = Tests:

    // ---- Codex r16 blocking #1: ordinary strided sizing ----

    test("Codex r16 #1: vlse64.v ordinary strided has at least (vl-1)*stride+elemBytes = 56 bytes"):
      val insn = RvvInsnRegistry.all.find(_.name == "vlse64.v").get
      val tmp  = Files.createTempDirectory("rvprobe-r17-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vlse64_v_v-0.S")))
      val bytes   = emittedTestdataBytes(content)
      // vl=4, stride=2*elemBytes=16, elemBytes=8 → (4-1)*16 + 8 = 56.
      assert(bytes >= 56)

    test("Codex r16 #1: vsse64.v ordinary strided dst has at least 56 bytes"):
      val insn = RvvInsnRegistry.all.find(_.name == "vsse64.v").get
      val tmp  = Files.createTempDirectory("rvprobe-r17-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vsse64_v_v-0.S")))
      val bytes   = emittedTestdataBytes(content)
      // src(>=32) + dst(>=56) = ≥ 88; assert at least the dst part.
      assert(bytes >= 56 + 32)

    test("Codex r16 #1: vlse8.v ordinary strided has at least vl*stride bytes"):
      val insn = RvvInsnRegistry.all.find(_.name == "vlse8.v").get
      val tmp  = Files.createTempDirectory("rvprobe-r17-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vlse8_v_v-0.S")))
      val bytes   = emittedTestdataBytes(content)
      // vl=4, stride=2*1=2, elemBytes=1 → (4-1)*2 + 1 = 7. Already easy
      // to satisfy; assert anyway to lock the formula in place.
      assert(bytes >= 7)

    // ---- Codex r16 blocking #2: Driver dispatch routes vsetvl* ----

    test("Codex r16 #2: Driver.emitOne(vsetvli) produces real .S, not # TODO placeholder"):
      val insn = RvvInsnRegistry.all.find(_.name == "vsetvli").get
      val tmp  = Files.createTempDirectory("rvprobe-r17-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vsetvli_v-0.S")))
      // Must NOT contain the placeholder marker.
      assert(!content.contains("# TODO real emission"))
      // Must contain the vsetvli mnemonic as a real .S line.
      val mnemonicPresent = content.split("\n").exists { line =>
        val t = line.trim
        !t.startsWith("#") && t.startsWith("vsetvli ")
      }
      assert(mnemonicPresent)
      // CSR-checking reads.
      assert(content.contains("csrr a3, vstart"))
      assert(content.contains("csrr a4, vtype"))
      assert(content.contains("csrr a5, vl"))
      // TEST_CASE rows for the CSR comparisons.
      assert(content.split("\n").exists(_.trim.startsWith("TEST_CASE(")))

    test("Codex r16 #2: Driver.emitOne(vsetvl) routes through renderTestS"):
      val insn = RvvInsnRegistry.all.find(_.name == "vsetvl").get
      val tmp  = Files.createTempDirectory("rvprobe-r17-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vsetvl_v-0.S")))
      assert(!content.contains("# TODO real emission"))
      val mnemonicPresent = content.split("\n").exists { line =>
        val t = line.trim
        !t.startsWith("#") && t.startsWith("vsetvl ")
      }
      assert(mnemonicPresent)

    test("Codex r16 #2: Driver.emitOne(vsetivli) routes through renderTestS"):
      val insn = RvvInsnRegistry.all.find(_.name == "vsetivli").get
      val tmp  = Files.createTempDirectory("rvprobe-r17-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vsetivli_v-0.S")))
      assert(!content.contains("# TODO real emission"))
      val mnemonicPresent = content.split("\n").exists { line =>
        val t = line.trim
        !t.startsWith("#") && t.startsWith("vsetivli ")
      }
      assert(mnemonicPresent)

    test("Codex r16 #2: vsetvl* .S does NOT contain vector magic .word"):
      // The CSR-checking codepath uses scalar TEST_CASE rows, NOT
      // pspike vector magic words. Document this with an assertion.
      val insn = RvvInsnRegistry.all.find(_.name == "vsetvli").get
      val tmp  = Files.createTempDirectory("rvprobe-r17-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vsetvli_v-0.S")))
      // Vector magic words for pspike start with `.word 0x0...`
      // (opcode 0x0B encoding). The CSR-only renderer must not emit
      // those.
      assert(!content.split("\n").exists(_.trim.startsWith(".word 0x")))
