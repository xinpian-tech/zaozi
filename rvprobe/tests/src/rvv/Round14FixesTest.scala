// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.audit.InsnsGenerator
import me.jiuyang.rvprobe.rvv.unittest.{Driver, RvvInsn, RvvInsnRegistry}

import java.nio.file.Files

import utest.*

/** Regressions for Codex round-13 review HIGH/MEDIUM issues fixed in round 14. */
object Round14FixesTest extends TestSuite:

  private def newCli(out: String): Driver.Cli =
    Driver.Cli(vlen = 256, xlen = 64, stage1OutputDir = out, march = "rv64gcv")

  val tests = Tests:

    // ---- Codex r13 #3 (HIGH): generator regex for segmented indexed/strided ----

    test("Codex r13 #3: indexedEewFor recognizes vluxseg2ei32 (segmented indexed load)"):
      assert(InsnsGenerator.indexedEewFor("vluxseg2ei32.v") == Some(32))
      assert(InsnsGenerator.indexedEewFor("vloxseg4ei8.v")  == Some(8))
      assert(InsnsGenerator.indexedEewFor("vsuxseg2ei32.v") == Some(32))
      assert(InsnsGenerator.indexedEewFor("vsoxseg8ei16.v") == Some(16))

    test("Codex r13 #3: indexedEewFor still recognizes unit-stride indexed"):
      assert(InsnsGenerator.indexedEewFor("vluxei32.v") == Some(32))
      assert(InsnsGenerator.indexedEewFor("vsuxei8.v")  == Some(8))

    test("Codex r13 #3: indexedEewFor returns None for non-indexed names"):
      assert(InsnsGenerator.indexedEewFor("vadd.vv")     == None)
      assert(InsnsGenerator.indexedEewFor("vlseg2e32.v") == None) // strided seg has no indexedEew
      assert(InsnsGenerator.indexedEewFor("vle32.v")     == None)

    test("Codex r13 #3: nfieldsFor recognizes unit-stride segmented"):
      assert(InsnsGenerator.nfieldsFor("vlseg2e32.v") == 2)
      assert(InsnsGenerator.nfieldsFor("vsseg4e16.v") == 4)
      assert(InsnsGenerator.nfieldsFor("vlseg8e8.v")  == 8)

    test("Codex r13 #3: nfieldsFor recognizes strided segmented"):
      assert(InsnsGenerator.nfieldsFor("vlsseg2e32.v") == 2)
      assert(InsnsGenerator.nfieldsFor("vssseg4e16.v") == 4)

    test("Codex r13 #3: nfieldsFor recognizes indexed segmented"):
      assert(InsnsGenerator.nfieldsFor("vluxseg2ei32.v") == 2)
      assert(InsnsGenerator.nfieldsFor("vloxseg4ei8.v")  == 4)
      assert(InsnsGenerator.nfieldsFor("vsuxseg8ei16.v") == 8)
      assert(InsnsGenerator.nfieldsFor("vsoxseg2ei64.v") == 2)

    test("Codex r13 #3: nfieldsFor returns 1 for non-segmented names"):
      assert(InsnsGenerator.nfieldsFor("vadd.vv")    == 1)
      assert(InsnsGenerator.nfieldsFor("vluxei32.v") == 1)
      assert(InsnsGenerator.nfieldsFor("vle32.v")    == 1)

    // ---- Codex r13 #3 (registry-level): regenerated declarations ----

    test("Codex r13 #3: registry vluxseg2ei32.v carries indexedEew=32 + nfields=2"):
      val insn = RvvInsnRegistry.all.find(_.name == "vluxseg2ei32.v")
      assert(insn.isDefined)
      assert(insn.get.indexedEew == Some(32))
      assert(insn.get.nfields == 2)

    test("Codex r13 #3: registry vsuxseg2ei32.v carries indexedEew=32 + nfields=2"):
      val insn = RvvInsnRegistry.all.find(_.name == "vsuxseg2ei32.v")
      assert(insn.isDefined)
      assert(insn.get.indexedEew == Some(32))
      assert(insn.get.nfields == 2)

    test("Codex r13 #3: registry vlsseg2e32.v carries nfields=2 (no indexedEew)"):
      val insn = RvvInsnRegistry.all.find(_.name == "vlsseg2e32.v")
      assert(insn.isDefined)
      assert(insn.get.nfields == 2)
      assert(insn.get.indexedEew == None)

    // ---- Codex r13 #4 (HIGH): strided segmented dispatch ----

    test("Codex r13 #4: vlsseg2e32.v dispatches to emitSegmentedStridedLoad"):
      val insn = RvvInsnRegistry.all.find(_.name == "vlsseg2e32.v")
      assert(insn.isDefined)
      val tmp  = Files.createTempDirectory("rvprobe-r14-")
      Driver.emitOne(insn.get, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vlsseg2e32_v_v-0.S")))
      // Strided segmented load emits the mnemonic + stride register.
      assert(content.contains("vlsseg2e32.v v8, (a1), a2"))
      assert(content.contains("li a2,"))
      assert(content.contains("segst_src"))

    test("Codex r13 #4: vssseg2e32.v dispatches to emitSegmentedStridedStore"):
      val insn = RvvInsnRegistry.all.find(_.name == "vssseg2e32.v")
      assert(insn.isDefined)
      val tmp = Files.createTempDirectory("rvprobe-r14-")
      Driver.emitOne(insn.get, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vssseg2e32_v_v-0.S")))
      assert(content.contains("vssseg2e32.v v8, (a1), a2"))
      // Memory witness via matching vlsseg reload.
      assert(content.contains("vlsseg2e32.v v8, (a1), a2"))

    // ---- Codex r13 #5 (MED): indexed segmented one-block-per-field ----

    test("Codex r13 #5: vluxseg2ei32.v emits 2 result stores + 2 magic words (one per field)"):
      val insn = RvvInsnRegistry.all.find(_.name == "vluxseg2ei32.v")
      assert(insn.isDefined)
      val tmp = Files.createTempDirectory("rvprobe-r14-")
      Driver.emitOne(insn.get, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vluxseg2ei32_v_v-0.S")))
      // The mnemonic itself appears once (the SUT).
      val mnemonicCount = content.split("\n").count(_.trim.startsWith("vluxseg2ei32.v "))
      assert(mnemonicCount == 1)
      // 2 magic words (.word 0x...) — one per field.
      val magicCount = content.split("\n").count(_.trim.startsWith(".word 0x"))
      assert(magicCount >= 2)
      // resultdata-store sequence "la a0, resultdata" appears NFIELDS=2
      // times (once per block).
      val laResCount = content.split("\n").count(_.trim.startsWith("la a0, resultdata"))
      assert(laResCount >= 2)

    test("Codex r13 #5: vsuxseg2ei32.v emits 2 magic words + reload via vluxseg2ei32"):
      val insn = RvvInsnRegistry.all.find(_.name == "vsuxseg2ei32.v")
      assert(insn.isDefined)
      val tmp = Files.createTempDirectory("rvprobe-r14-")
      Driver.emitOne(insn.get, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vsuxseg2ei32_v_v-0.S")))
      assert(content.contains("vsuxseg2ei32.v v8, (a1), v16"))
      assert(content.contains("vluxseg2ei32.v v8, (a1), v16")) // memory witness reload
      val magicCount = content.split("\n").count(_.trim.startsWith(".word 0x"))
      assert(magicCount >= 2)

    // ---- Codex r13 blocking #1: default envelopes tu,mu ----

    test("Codex r13 blocking #1: vadd.vv emission contains tu,mu (not ta,ma) in per-block vsetvli"):
      val insn = RvvInsnRegistry.all.find(_.name == "vadd.vv").get
      val tmp  = Files.createTempDirectory("rvprobe-r14-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vadd_vv_v-0.S")))
      // Every block-level vsetvli (the t0-based form) should use tu,mu.
      val lines = content.split("\n").map(_.trim).filter(l =>
        l.startsWith("vsetvli x5, t0,"))
      assert(lines.nonEmpty)
      assert(lines.forall(_.endsWith("tu,mu")))

    test("Codex r13 blocking #1: result-store VLMAX vsetvli still uses ta,ma"):
      val insn = RvvInsnRegistry.all.find(_.name == "vwadd.vv").get // result-store activates
      val tmp  = Files.createTempDirectory("rvprobe-r14-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vwadd_vv_v-0.S")))
      // VLMAX form `vsetvli x5, x0, ...` is the result-store; must keep ta,ma.
      val vlmaxLines = content.split("\n").map(_.trim).filter(l =>
        l.startsWith("vsetvli x5, x0,"))
      assert(vlmaxLines.nonEmpty)
      assert(vlmaxLines.forall(_.endsWith("ta,ma")))

    // ---- Codex r13 #1 (HIGH): FP gate marker for xorshift fallback ----

    test("Codex r13 #1: vfadd.vv xorshift-fallback emission contains the gate sentinel"):
      val insn = RvvInsnRegistry.all.find(_.name == "vfadd.vv").get
      val tmp  = Files.createTempDirectory("rvprobe-r14-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vfadd_vv_v-0.S")))
      // In any sandbox without TestFloat-3 on PATH, the fallback runs,
      // and the sentinel MUST appear so PocGateTest can detect it.
      // (When TestFloat-3 IS available the marker is absent — that
      // case is the other branch of the gate.)
      val hasMarker     = content.contains(Driver.FpXorshiftFallbackMarker)
      val hasTestfloat3 = content.contains("fp_rne_data") // testfloat3 path's label prefix
      assert(hasMarker || hasTestfloat3)
