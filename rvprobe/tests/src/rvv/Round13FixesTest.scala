// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.unittest.{Driver, RvvInsn, RvvInsnRegistry, TestSEmit, Testfloat3Driver}
import me.jiuyang.rvprobe.rvv.vtype.{Lmul, Sew, VType, VTypeEnvelope, Vma, Vta}

import java.nio.file.{Files, Paths}

import utest.*

/** Regressions for Codex round-12 review HIGH/MEDIUM issues fixed in round 13. */
object Round13FixesTest extends TestSuite:

  private def newCli(out: String): Driver.Cli =
    Driver.Cli(vlen = 256, xlen = 64, stage1OutputDir = out, march = "rv64gcv")

  val tests = Tests:

    // ---- Codex r12 #1 (HIGH): indexed EMUL/nfields real handling ----

    test("Codex r12 #1: vluxei32.v emits aligned data + index register choice"):
      val insn = RvvInsnRegistry.all.find(_.name == "vluxei32.v").get
      val tmp  = Files.createTempDirectory("rvprobe-r13-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vluxei32_v_v-0.S")))
      // Data group at v8 (aligned), index group at v16 (aligned, disjoint).
      assert(content.contains("vluxei32.v v8, (a1), v16"))
      // Index vector load at indexedEew (32 bits → vle32.v).
      assert(content.contains("vle32.v v16, (a1)"))

    test("Codex r12 #1: vluxei8.v fractional index EMUL collapses to 1 register"):
      // indexEew=8, dataSew=32, LMUL=M1 → indexEmul = 8/32 * 1 = 1/4
      // → fractional → 1 whole register. vd at v8, vs2 at v16. Should
      // still emit successfully.
      val maybe = RvvInsnRegistry.all.find(_.name == "vluxei8.v")
      maybe match
        case Some(insn) =>
          val tmp = Files.createTempDirectory("rvprobe-r13-")
          Driver.emitOne(insn, newCli(tmp.toString), tmp)
          val content = new String(Files.readAllBytes(tmp.resolve("vluxei8_v_v-0.S")))
          assert(content.contains("vluxei8.v v8, (a1), v16"))
          assert(content.contains("vle8.v v16, (a1)"))
        case None => () // not in registry, skip

    test("Codex r12 #1: indexed store reload uses correct index EEW"):
      val insn = RvvInsnRegistry.all.find(_.name == "vsuxei32.v").get
      val tmp  = Files.createTempDirectory("rvprobe-r13-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vsuxei32_v_v-0.S")))
      // Store, then reload-via-indexed-load to capture written bytes
      // (Codex r11 #2 + r12 #1: reload must use matching index EEW).
      assert(content.contains("vsuxei32.v v8, (a1), v16"))
      val storePos  = content.indexOf("vsuxei32.v v8, (a1), v16")
      val reloadPos = content.indexOf("vluxei32.v v8, (a1), v16", storePos)
      assert(reloadPos > storePos)

    // ---- Codex r12 #2 (HIGH): FP fallback emits real mnemonic ----

    test("Codex r12 #2: vfadd.vv fallback emits mnemonic, not comment-only body"):
      val insn = RvvInsnRegistry.all.find(_.name == "vfadd.vv").get
      val tmp  = Files.createTempDirectory("rvprobe-r13-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vfadd_vv_v-0.S")))
      // The mnemonic MUST appear as a real .S line (not just inside a
      // `# TODO` or `# FP fallback` comment).
      val mnemonicLines = content.split("\n").iterator
        .map(_.trim)
        .filter(line => !line.startsWith("#") && line.startsWith("vfadd.vv "))
        .toList
      assert(mnemonicLines.nonEmpty)
      // Must NOT contain the old "# FP fallback for" line as the
      // instruction body.
      assert(!content.contains("# FP fallback for vfadd.vv"))
      // FRM sweep still present (5 modes).
      val frmCount = content.split("\n").count(_.trim.startsWith("csrwi frm,"))
      assert(frmCount >= 5)

    test("Codex r12 #2: fallback embeds operand data via vle loads"):
      val insn = RvvInsnRegistry.all.find(_.name == "vfadd.vv").get
      val tmp  = Files.createTempDirectory("rvprobe-r13-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vfadd_vv_v-0.S")))
      // The fallback must load operands from data section into v16/v24,
      // not leave them uninitialized.
      assert(content.contains("vle32.v v16, (a1)"))
      assert(content.contains("vle32.v v24, (a1)"))

    // ---- Codex r12 #3 (HIGH): Testfloat3Driver CLI semantics ----

    test("Codex r12 #3: Testfloat3Driver parses ASCII hex output to little-endian bytes"):
      // f32_add output: "<a> <b> <result> <flags>" per line, where
      // each value is big-endian hex matching the SEW width.
      val sample =
        """3f800000 40000000 40400000 00
          |bf800000 c0000000 c0400000 00
          |""".stripMargin.getBytes("ASCII")
      val parsed = Testfloat3Driver.parseAsciiOutput(sample, Sew.Sew32)
      assert(parsed.isRight)
      val outBytes = parsed.toOption.get
      // 2 lines × 2 operands × 4 bytes = 16 total bytes.
      // Layout: [a0 a1] [b0 b1] (all-A then all-B per call site).
      assert(outBytes.length == 16)
      // First A operand 0x3f800000 LE = 00 00 80 3f.
      assert(outBytes(0) == 0x00.toByte)
      assert(outBytes(1) == 0x00.toByte)
      assert(outBytes(2) == 0x80.toByte)
      assert(outBytes(3) == 0x3f.toByte)
      // After 2 A operands (8 bytes), first B operand 0x40000000
      // LE = 00 00 00 40.
      assert(outBytes(8) == 0x00.toByte)
      assert(outBytes(9) == 0x00.toByte)
      assert(outBytes(10) == 0x00.toByte)
      assert(outBytes(11) == 0x40.toByte)

    test("Codex r12 #3: hexToLittleEndian pads short tokens"):
      // f16 width = 2 bytes = 4 hex digits. Input "3c00" → LE bytes
      // 0x00, 0x3c.
      val hex = Testfloat3Driver.hexToLittleEndian("3c00", 2)
      assert(hex.isRight)
      val hexBytes = hex.toOption.get
      assert(hexBytes.length == 2)
      assert(hexBytes(0) == 0x00.toByte)
      assert(hexBytes(1) == 0x3c.toByte)

    test("Codex r12 #3: parseAsciiOutput rejects non-hex tokens"):
      val bad    = "zzzz wwww 0000 00\n".getBytes("ASCII")
      val badRes = Testfloat3Driver.parseAsciiOutput(bad, Sew.Sew16)
      assert(badRes.isLeft)
      val empty    = "\n\n".getBytes("ASCII")
      val emptyRes = Testfloat3Driver.parseAsciiOutput(empty, Sew.Sew32)
      assert(emptyRes.isLeft)

    // ---- Codex r12 #4 (HIGH): PocGateTest tightening (covered in
    //      PocGateTest.scala directly; here we cross-check the gate
    //      logic standalone) ----

    test("Codex r12 #4: real .S with mnemonic + 5 FRMs passes the gate"):
      val insn = RvvInsnRegistry.all.find(_.name == "vfadd.vv").get
      val tmp  = Files.createTempDirectory("rvprobe-r13-")
      Driver.emitOne(insn, newCli(tmp.toString), tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vfadd_vv_v-0.S")))
      val mnemonicPresent = content.split("\n").exists { line =>
        val trimmed = line.trim
        !trimmed.startsWith("#") && trimmed.startsWith("vfadd.vv ")
      }
      val frmCount = content.split("\n").count(_.trim.startsWith("csrwi frm,"))
      assert(mnemonicPresent)
      assert(frmCount >= 5)

    test("Codex r12 #4: synthetic # TODO-only body fails the gate criterion"):
      val synthetic = "# TODO vfadd.vv unavailable\n.word 0xdeadbeef\n"
      val mnemonicPresent = synthetic.split("\n").exists { line =>
        val trimmed = line.trim
        !trimmed.startsWith("#") && trimmed.startsWith("vfadd.vv ")
      }
      assert(!mnemonicPresent)

    // ---- Codex r12 #5 (MED): segmented load fan-out ----

    test("Codex r12 #5: vlseg2e32.v dispatches to emitSegmentedLoad (not unit-stride)"):
      // Search registry for a VdRs1mVm-shaped insn with nfields=2.
      val maybe = RvvInsnRegistry.all.find(i =>
        i.name == "vlseg2e32.v" && i.nfields == 2)
      maybe match
        case Some(insn) =>
          val tmp = Files.createTempDirectory("rvprobe-r13-")
          Driver.emitOne(insn, newCli(tmp.toString), tmp)
          val content = new String(Files.readAllBytes(tmp.resolve("vlseg2e32_v_v-0.S")))
          assert(content.contains("vlseg2e32.v v8, (a1)"))
          assert(content.contains("segld_src"))
        case None => () // optional: not all toml configs declared

    // ---- Codex r12 blocking side issue #1: vta/vma from envelope ----

    test("vta/vma from envelope: Undisturbed envelope yields tu,mu suffix"):
      val env = VTypeEnvelope.unsafe(
        VType(Sew.Sew32, Lmul.M1, Vta.Undisturbed, Vma.Undisturbed),
        vl = 4, vlen = 128, xlen = 64)
      val s = TestSEmit.vsetvliAsm(env)
      assert(s.contains("tu,mu"))
      assert(!s.contains("ta,ma"))

    test("vta/vma from envelope: Agnostic envelope yields ta,ma suffix"):
      val env = VTypeEnvelope.unsafe(
        VType(Sew.Sew32, Lmul.M1, Vta.Agnostic, Vma.Agnostic),
        vl = 4, vlen = 128, xlen = 64)
      val s = TestSEmit.vsetvliAsm(env)
      assert(s.contains("ta,ma"))
      assert(!s.contains("tu,mu"))

    test("vta/vma from envelope: mixed Undisturbed×Agnostic yields tu,ma"):
      val env = VTypeEnvelope.unsafe(
        VType(Sew.Sew32, Lmul.M1, Vta.Undisturbed, Vma.Agnostic),
        vl = 4, vlen = 128, xlen = 64)
      val s = TestSEmit.vsetvliAsm(env)
      assert(s.contains("tu,ma"))

    test("vta/vma: result-store VLMAX vsetvli always uses ta,ma"):
      // Result-store path dumps the full register-group footprint;
      // agnostic policy is the correct choice there regardless of the
      // envelope's ordinary policy.
      val env = VTypeEnvelope.unsafe(
        VType(Sew.Sew32, Lmul.M1, Vta.Undisturbed, Vma.Undisturbed),
        vl = 4, vlen = 128, xlen = 64)
      val s = TestSEmit.vsetvliAsmVlmax(env)
      assert(s.contains("ta,ma"))
