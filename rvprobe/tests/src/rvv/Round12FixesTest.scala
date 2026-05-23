// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.{OperandRole, Schema}
import me.jiuyang.rvprobe.rvv.audit.InsnsGenerator
import me.jiuyang.rvprobe.rvv.eew.{OperandWidthProfile, WidthScale}
import me.jiuyang.rvprobe.rvv.unittest.{Driver, RvvInsn, RvvInsnRegistry, TestSEmit}
import me.jiuyang.rvprobe.rvv.vsetvl.Tests as VsetvlTests
import me.jiuyang.rvprobe.rvv.vtype.{Lmul, Sew, VType, VTypeEnvelope, Vma, Vta}

import java.nio.file.{Files, Paths}

import utest.*

/** Regressions for Codex round-11 review HIGH/MEDIUM issues fixed in round 12. */
object Round12FixesTest extends TestSuite:

  private def newCli(out: String): Driver.Cli =
    Driver.Cli(vlen = 256, xlen = 64, stage1OutputDir = out, march = "rv64gcv")

  val tests = Tests:

    // ---- Codex r11 #1: env.vl materialized, not VLMAX ----

    test("Codex r11 #1: vsetvliAsm uses env.vl via li t0, not x0"):
      val env = VTypeEnvelope.unsafe(
        VType(Sew.Sew32, Lmul.M1, Vta.Agnostic, Vma.Agnostic),
        vl = 4, vlen = 128, xlen = 64)
      val s = TestSEmit.vsetvliAsm(env)
      assert(s.contains("li t0, 4"))
      assert(s.contains("vsetvli x5, t0,"))
      assert(!s.contains("vsetvli x5, x0,"))

    test("Codex r11 #1: vsetvliAsmVlmax still uses x0 for VLMAX (result stores)"):
      val env = VTypeEnvelope.unsafe(
        VType(Sew.Sew32, Lmul.M1, Vta.Agnostic, Vma.Agnostic),
        vl = 4, vlen = 128, xlen = 64)
      val s = TestSEmit.vsetvliAsmVlmax(env)
      assert(s.contains("vsetvli x5, x0,"))

    test("Codex r11 #1: emitVdVs2Vs1Vm output has no `vsetvli x5, x0,` before the instruction"):
      val insn = RvvInsnRegistry.all.find(_.name == "vadd.vv").get
      val tmp  = Files.createTempDirectory("rvprobe-r12-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vadd_vv_v-0.S")))
      // The instruction-under-test must NOT be preceded by `vsetvli x5, x0,`
      // (= VLMAX). The instruction's own vsetvli must use env.vl via t0.
      // Find the vadd.vv line and check the prior 5 lines for the wrong form.
      val lines = content.split("\n").toList
      val idx   = lines.indexWhere(_.trim.startsWith("vadd.vv "))
      assert(idx > 0)
      val precursor = lines.slice(idx - 5, idx).mkString("\n")
      // Must contain `li t0,` before the instruction (the env.vl setup).
      assert(precursor.contains("li t0,"))
      // The vsetvli right before the instruction must be `x5, t0,` form.
      assert(precursor.contains("vsetvli x5, t0,"))

    // ---- Codex r11 #2: store paths reload from memory before result store ----

    test("Codex r11 #2: vse32.v store path reloads from dst label before resultdata"):
      val insn = RvvInsnRegistry.all.find(_.name == "vse32.v").get
      val tmp  = Files.createTempDirectory("rvprobe-r12-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vse32_v_v-0.S")))
      // After the store there must be a reload from st_dst_0 into v8. The
      // first `vle32.v` is the source-data load; we need the one AFTER
      // the store (i.e. lastIndexOf, or first occurrence past storePos).
      val storePos        = content.indexOf("vse32.v v8, (a1)")
      val reloadPos       = content.indexOf("vle32.v v8, (a1)", storePos)
      val resultPos       = content.indexOf("la a0, resultdata")
      assert(storePos > 0)
      assert(reloadPos > storePos)
      assert(resultPos > reloadPos)
      // The reload's address-register setup must reference the dst label.
      assert(content.contains("st_dst_0"))

    // ---- Codex r11 #3: vsseg2e32.v real segmented store ----

    test("Codex r11 #3: vsseg2e32.v dispatches to emitSegmentedStore, not unit-stride"):
      val insn = RvvInsnRegistry.all.find(_.name == "vsseg2e32.v").get
      assert(insn.nfields == 2)
      val tmp = Files.createTempDirectory("rvprobe-r12-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vsseg2e32_v_v-0.S")))
      // Real segmented path: NFIELDS separate vle loads (v8, v9) + the
      // segmented store mnemonic + segmented load to reload.
      assert(content.contains("vsseg2e32.v v8, (a1)"))
      assert(content.contains("vle32.v v8, (a1)"))
      assert(content.contains("vle32.v v9, (a1)"))
      assert(content.contains("vlseg2e32.v v8, (a1)"))
      assert(content.contains("seg_src"))
      assert(content.contains("seg_dst"))

    // ---- Codex r11 #5: Driver routes FP through emitFp ----

    test("Codex r11 #5: vfadd.vv dispatches to FP emission (testfloat3 or fallback)"):
      val insn = RvvInsnRegistry.all.find(_.name == "vfadd.vv").get
      val tmp  = Files.createTempDirectory("rvprobe-r12-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vfadd_vv_v-0.S")))
      // Whether testfloat_gen is present or not, the FP path emits
      // `csrwi frm, N` lines per FRM mode. The integer-witness path
      // for VdVs2Vs1Vm would emit `vadd.vv v8, v16, v24`-style only.
      assert(content.contains("csrwi frm,"))
      // FRM sweep: 5 csrwi lines (one per mode).
      val frmCount = content.split("\n").count(_.trim.startsWith("csrwi frm,"))
      assert(frmCount >= 5)

    // ---- Codex r11 #6a: vfncvt.f.f.w narrowing detected ----

    test("Codex r11 #6a: vfncvt.f.f.w has Vs2 By2 narrowing profile"):
      val insn = RvvInsnRegistry.all.find(_.name == "vfncvt.f.f.w").get
      // Two declarations exist (v + zvfhmin); both should have the profile.
      val all = RvvInsnRegistry.all.filter(_.name == "vfncvt.f.f.w")
      assert(all.size >= 1)
      for i <- all do
        assert(i.widthProfile.scaleOf(OperandRole.Vs2) == WidthScale.By2)

    test("Codex r11 #6a: vfncvt.x.f.w has Vs2 By2 narrowing profile"):
      val insn = RvvInsnRegistry.all.find(_.name == "vfncvt.x.f.w").get
      assert(insn.widthProfile.scaleOf(OperandRole.Vs2) == WidthScale.By2)

    // ---- Codex r11 #6b: widening reductions excluded from By2 dest footprint ----

    test("Codex r11 #6b: vwredsum.vs has default profile (NOT By2 dest)"):
      val insn = RvvInsnRegistry.all.find(_.name == "vwredsum.vs").get
      assert(!insn.widthProfile.maskDest)
      // Default scale is One (no widening) for reductions.
      assert(insn.widthProfile.scaleOf(OperandRole.Vd) == WidthScale.One)

    test("Codex r11 #6b: vfwredusum.vs has default profile (NOT By2 dest)"):
      val insn = RvvInsnRegistry.all.find(_.name == "vfwredusum.vs").get
      assert(insn.widthProfile.scaleOf(OperandRole.Vd) == WidthScale.One)

    test("Codex r11 #6b: regular vwadd.vv still has By2 (not affected by reduction exclusion)"):
      val insn = RvvInsnRegistry.all.find(_.name == "vwadd.vv").get
      assert(insn.widthProfile.scaleOf(OperandRole.Vd) == WidthScale.By2)

    // ---- Codex r11 #6c (= round-10 vsetvl vill): expectVtypeBits = vill bit only ----

    test("Codex r11 #6c: vsetvl vill expectVtypeBits is vill-bit-only, not OR with vtypeImm"):
      val cs   = VsetvlTests.cases(vlen = 256, xlen = 64)
      val vill = cs.find(_.expectVill).get
      val expectedVillOnly = 1L << (64 - 1)
      assert(vill.expectVtypeBits == expectedVillOnly)

    // ---- Codex r11 #7: CI workflow pinned to specific upstream commit ----

    test("Codex r11 #7: .github/workflows/drift-check.yml pins upstream to a commit hash"):
      val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath
      val wf  = LazyList.iterate(cwd: java.nio.file.Path)(_.getParent).takeWhile(_ != null)
        .map(_.resolve(".github/workflows/drift-check.yml"))
        .find(p => Files.exists(p))
      assert(wf.isDefined)
      val text = new String(Files.readAllBytes(wf.get))
      // ref: <40-char hex> indicates a pinned commit; ref: main would be a moving branch.
      val pinnedHash = """ref:\s+[0-9a-f]{40}""".r
      assert(pinnedHash.findFirstIn(text).isDefined)
      // The "ref: main" line must NOT appear (would be a moving baseline).
      assert(!text.contains("ref: main"))
