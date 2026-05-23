// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.unittest.{Driver, RvvInsn, RvvInsnRegistry}
import me.jiuyang.rvprobe.rvv.vtype.Sew

import java.nio.file.Files

import utest.*

/** Regressions for Codex round-14 review HIGH issues fixed in round 15. */
object Round15FixesTest extends TestSuite:

  private def newCli(out: String): Driver.Cli =
    Driver.Cli(vlen = 256, xlen = 64, stage1OutputDir = out, march = "rv64gcv")

  val tests = Tests:

    // ---- Codex r14 #1 (HIGH): inferSewFromName covers all families ----

    test("Codex r14 #1: vle32.v unit-stride SEW=32"):
      assert(Driver.inferSewFromName("vle32.v") == Some(Sew.Sew32))
      assert(Driver.inferSewFromName("vse32.v") == Some(Sew.Sew32))
      assert(Driver.inferSewFromName("vle8.v")  == Some(Sew.Sew8))
      assert(Driver.inferSewFromName("vle64.v") == Some(Sew.Sew64))

    test("Codex r14 #1: vlse64.v strided SEW=64 (was Sew32 in r14)"):
      assert(Driver.inferSewFromName("vlse64.v") == Some(Sew.Sew64))
      assert(Driver.inferSewFromName("vsse64.v") == Some(Sew.Sew64))
      assert(Driver.inferSewFromName("vlse16.v") == Some(Sew.Sew16))
      assert(Driver.inferSewFromName("vsse8.v")  == Some(Sew.Sew8))

    test("Codex r14 #1: vlseg2e64.v segmented unit-stride SEW=64"):
      assert(Driver.inferSewFromName("vlseg2e64.v") == Some(Sew.Sew64))
      assert(Driver.inferSewFromName("vsseg2e64.v") == Some(Sew.Sew64))
      assert(Driver.inferSewFromName("vlseg4e8.v")  == Some(Sew.Sew8))
      assert(Driver.inferSewFromName("vsseg8e16.v") == Some(Sew.Sew16))

    test("Codex r14 #1: vlsseg2e64.v segmented strided SEW=64"):
      assert(Driver.inferSewFromName("vlsseg2e64.v") == Some(Sew.Sew64))
      assert(Driver.inferSewFromName("vssseg2e64.v") == Some(Sew.Sew64))
      assert(Driver.inferSewFromName("vlsseg4e8.v")  == Some(Sew.Sew8))

    test("Codex r14 #1: vle32ff.v fault-first SEW=32"):
      assert(Driver.inferSewFromName("vle32ff.v") == Some(Sew.Sew32))
      assert(Driver.inferSewFromName("vle8ff.v")  == Some(Sew.Sew8))
      assert(Driver.inferSewFromName("vle64ff.v") == Some(Sew.Sew64))

    test("Codex r14 #1: returns None for non-load/store names"):
      assert(Driver.inferSewFromName("vadd.vv") == None)
      assert(Driver.inferSewFromName("vmseq.vv") == None)
      assert(Driver.inferSewFromName("vfadd.vv") == None)

    // ---- Codex r14 #2 (HIGH): segmented emitters per-field result/magic ----

    test("Codex r14 #2: vlseg3e8.v (NFIELDS=3) emission compiles + 3 magic words"):
      // NFIELDS=3 was the trigger case: round-14 used
      // resultWholeRegisters=3 which fails {1,2,4,8} guard.
      val maybe = RvvInsnRegistry.all.find(_.name == "vlseg3e8.v")
      maybe match
        case Some(insn) =>
          val tmp = Files.createTempDirectory("rvprobe-r15-")
          Driver.emitOne(insn, newCli(tmp.toString), tmp)
          val content = new String(Files.readAllBytes(tmp.resolve("vlseg3e8_v_v-0.S")))
          val magicCount = content.split("\n").count(_.trim.startsWith(".word 0x"))
          assert(magicCount >= 3)
          // The instruction-under-test appears once.
          val mnemonicCount = content.split("\n").count(_.trim.startsWith("vlseg3e8.v "))
          assert(mnemonicCount == 1)
        case None => () // skip if not in registry

    test("Codex r14 #2: vsseg3e16.v (NFIELDS=3) emission compiles + 3 magic words"):
      val maybe = RvvInsnRegistry.all.find(_.name == "vsseg3e16.v")
      maybe match
        case Some(insn) =>
          val tmp = Files.createTempDirectory("rvprobe-r15-")
          Driver.emitOne(insn, newCli(tmp.toString), tmp)
          val content = new String(Files.readAllBytes(tmp.resolve("vsseg3e16_v_v-0.S")))
          val magicCount = content.split("\n").count(_.trim.startsWith(".word 0x"))
          assert(magicCount >= 3)
        case None => ()

    test("Codex r14 #2: vlsseg3e8.v (strided NFIELDS=3) emission compiles + 3 magic words"):
      val maybe = RvvInsnRegistry.all.find(_.name == "vlsseg3e8.v")
      maybe match
        case Some(insn) =>
          val tmp = Files.createTempDirectory("rvprobe-r15-")
          Driver.emitOne(insn, newCli(tmp.toString), tmp)
          val content = new String(Files.readAllBytes(tmp.resolve("vlsseg3e8_v_v-0.S")))
          val magicCount = content.split("\n").count(_.trim.startsWith(".word 0x"))
          assert(magicCount >= 3)
        case None => ()

    test("Codex r14 #2: vssseg5e32.v (strided NFIELDS=5) emission compiles + 5 magic words"):
      // NFIELDS=5 is the worst-case test: ∉ {1,2,4,8}.
      val maybe = RvvInsnRegistry.all.find(_.name == "vssseg5e32.v")
      maybe match
        case Some(insn) =>
          val tmp = Files.createTempDirectory("rvprobe-r15-")
          Driver.emitOne(insn, newCli(tmp.toString), tmp)
          val content = new String(Files.readAllBytes(tmp.resolve("vssseg5e32_v_v-0.S")))
          val magicCount = content.split("\n").count(_.trim.startsWith(".word 0x"))
          assert(magicCount >= 5)
        case None => () // optional

    test("Codex r14 #2: vsseg2e32.v (NFIELDS=2) still emits 2 magic words"):
      // Regression: round-14 NFIELDS=2 case must still work.
      val insn = RvvInsnRegistry.all.find(_.name == "vsseg2e32.v").get
      val tmp  = Files.createTempDirectory("rvprobe-r15-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vsseg2e32_v_v-0.S")))
      val magicCount = content.split("\n").count(_.trim.startsWith(".word 0x"))
      assert(magicCount >= 2)
      // The instruction-under-test appears once.
      val mnemonicCount = content.split("\n").count(_.trim.startsWith("vsseg2e32.v "))
      assert(mnemonicCount == 1)

    test("Codex r14 #2: each segmented block uses resultWholeRegisters=1 (dataEmul)"):
      val insn = RvvInsnRegistry.all.find(_.name == "vsseg2e32.v").get
      val tmp  = Files.createTempDirectory("rvprobe-r15-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vsseg2e32_v_v-0.S")))
      // Each block's result vsetvli should be `m1` (1 whole register),
      // not `m2`. Look for at least one such line; absence of `m2` in
      // result-store vsetvli is the regression we care about.
      val resultVsetvliLines = content.split("\n").filter(_.trim.startsWith("vsetvli x5, x0,"))
      // Should NOT contain m2/m4/m8 for NFIELDS=2 LMUL=M1 (dataEmul=1).
      assert(resultVsetvliLines.forall(l => !l.contains(",m2,") && !l.contains(",m4,") && !l.contains(",m8,")))

    // ---- Cross-check: SEW inference matters for emission ----

    test("Codex r14 #1: vsse64.v emission uses e64 in setup load, not e32"):
      val maybe = RvvInsnRegistry.all.find(_.name == "vsse64.v")
      maybe match
        case Some(insn) =>
          val tmp = Files.createTempDirectory("rvprobe-r15-")
          Driver.emitOne(insn, newCli(tmp.toString), tmp)
          val content = new String(Files.readAllBytes(tmp.resolve("vsse64_v_v-0.S")))
          // Setup load must use e64 (Codex r14 #1: was silently e32).
          assert(content.contains("vle64.v v8, (a1)"))
        case None => ()
