// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.unittest.{Driver, RvvInsn, RvvInsnRegistry}
import me.jiuyang.rvprobe.rvv.vsetvl.Tests as VsetvlTests

import java.nio.file.Files

import utest.*

/** Regressions for Codex round-15 review HIGH issues fixed in round 16. */
object Round16FixesTest extends TestSuite:

  private def newCli(out: String): Driver.Cli =
    Driver.Cli(vlen = 256, xlen = 64, stage1OutputDir = out, march = "rv64gcv")

  /** Extract the byte count from .byte / .zero declarations in testdata. */
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

    // ---- Codex r15 #1 (HIGH): strided source-data sizing ----

    test("Codex r15 #1: vlsseg2e32.v has at least vl*nfields*elemBytes = 32 bytes testdata"):
      val maybe = RvvInsnRegistry.all.find(_.name == "vlsseg2e32.v")
      maybe match
        case Some(insn) =>
          val tmp = Files.createTempDirectory("rvprobe-r16-")
          Driver.emitOne(insn, newCli(tmp.toString), tmp)
          val content = new String(Files.readAllBytes(tmp.resolve("vlsseg2e32_v_v-0.S")))
          val bytes   = emittedTestdataBytes(content)
          assert(bytes >= 4 * 2 * 4) // vl=4 * nfields=2 * elemBytes=4 = 32
        case None => ()

    test("Codex r15 #1: vlsseg5e32.v has at least 80 bytes testdata (was 64 in r15)"):
      val maybe = RvvInsnRegistry.all.find(_.name == "vlsseg5e32.v")
      maybe match
        case Some(insn) =>
          val tmp = Files.createTempDirectory("rvprobe-r16-")
          Driver.emitOne(insn, newCli(tmp.toString), tmp)
          val content = new String(Files.readAllBytes(tmp.resolve("vlsseg5e32_v_v-0.S")))
          val bytes   = emittedTestdataBytes(content)
          assert(bytes >= 4 * 5 * 4) // vl=4 * nfields=5 * elemBytes=4 = 80
        case None => ()

    test("Codex r15 #1: vlsseg8e64.v has at least 256 bytes testdata (was 128 in r15)"):
      val maybe = RvvInsnRegistry.all.find(_.name == "vlsseg8e64.v")
      maybe match
        case Some(insn) =>
          val tmp = Files.createTempDirectory("rvprobe-r16-")
          Driver.emitOne(insn, newCli(tmp.toString), tmp)
          val content = new String(Files.readAllBytes(tmp.resolve("vlsseg8e64_v_v-0.S")))
          val bytes   = emittedTestdataBytes(content)
          assert(bytes >= 4 * 8 * 8) // vl=4 * nfields=8 * elemBytes=8 = 256
        case None => ()

    test("Codex r15 #1: vssseg5e32.v dst buffer is at least 80 bytes"):
      val maybe = RvvInsnRegistry.all.find(_.name == "vssseg5e32.v")
      maybe match
        case Some(insn) =>
          val tmp = Files.createTempDirectory("rvprobe-r16-")
          Driver.emitOne(insn, newCli(tmp.toString), tmp)
          val content = new String(Files.readAllBytes(tmp.resolve("vssseg5e32_v_v-0.S")))
          // src + dst combined ≥ src(20*5) + dst(vl*nfields*elemBytes = 80).
          val bytes = emittedTestdataBytes(content)
          assert(bytes >= 80 + 80)
        case None => ()

    test("Codex r15 #1: vlseg8e64.v unit-stride segmented has 256 bytes testdata"):
      val maybe = RvvInsnRegistry.all.find(_.name == "vlseg8e64.v")
      maybe match
        case Some(insn) =>
          val tmp = Files.createTempDirectory("rvprobe-r16-")
          Driver.emitOne(insn, newCli(tmp.toString), tmp)
          val content = new String(Files.readAllBytes(tmp.resolve("vlseg8e64_v_v-0.S")))
          val bytes = emittedTestdataBytes(content)
          assert(bytes >= 4 * 8 * 8) // vl=4 * nfields=8 * elemBytes=8 = 256
        case None => ()

    // ---- Codex r15 blocking #2: vsetvl renderTestS wired into PocGate ----

    test("Codex r15 blocking #2: vsetvl.Tests.renderTestS produces valid vsetvli .S"):
      val s = VsetvlTests.renderTestS("vsetvli", vlen = 256, xlen = 64, envMacro = "RVTEST_RV64UV")
      assert(s.nonEmpty)
      assert(s.contains("vsetvli "))
      assert(s.contains("csrr a3, vstart"))
      assert(s.contains("csrr a4, vtype"))
      assert(s.contains("csrr a5, vl"))
      val mnemonicPresent = s.split("\n").exists { line =>
        val t = line.trim
        !t.startsWith("#") && t.startsWith("vsetvli ")
      }
      assert(mnemonicPresent)

    test("Codex r15 blocking #2: renderTestS for vsetvl/vsetivli also produce valid output"):
      for variant <- List("vsetvl", "vsetivli") do
        val s = VsetvlTests.renderTestS(variant, vlen = 256, xlen = 64, envMacro = "RVTEST_RV64UV")
        assert(s.contains("RVTEST_CODE_BEGIN"))
        assert(s.contains("RVTEST_CODE_END"))
        val mnemonicPresent = s.split("\n").exists { line =>
          val t = line.trim
          !t.startsWith("#") && t.startsWith(s"$variant ")
        }
        assert(mnemonicPresent)
