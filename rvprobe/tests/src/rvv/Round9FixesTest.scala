// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.Schema
import me.jiuyang.rvprobe.rvv.audit.TomlIntent
import me.jiuyang.rvprobe.rvv.eew.{OperandWidthProfile, WidthScale}
import me.jiuyang.rvprobe.rvv.unittest.{Driver, MagicInstrEmit, RvvInsn, RvvInsnRegistry, TestSEmit}
import me.jiuyang.rvprobe.rvv.vsetvl.Tests as VsetvlTests
import me.jiuyang.rvprobe.rvv.vtype.{Lmul, Sew, VType, VTypeEnvelope, Vma, Vta}

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import utest.*

/** Regressions for the round-8 Codex review HIGH issues fixed in round 9. */
object Round9FixesTest extends TestSuite:

  private def envSew32M1: VTypeEnvelope =
    VTypeEnvelope.unsafe(VType(Sew.Sew32, Lmul.M1, Vta.Agnostic, Vma.Agnostic), 4, 128, 64)

  val tests = Tests:

    // ---- Codex r8 #1: resultdata store-before-magic contract ----

    test("Codex r8 #1: TestSEmit emits `la a0, resultdata` + `vse*.v` BEFORE magic word"):
      val block = TestSEmit.TestBlock(
        envSew32M1, vectorGroup = 8, vxsat = false,
        insnAsm              = "vadd.vv v8, v16, v24",
        setupAsm             = Nil,
        dataLabel            = None,
        resultEew            = 32,
        resultGroup          = 8,
        resultWholeRegisters = 1)
      val s = TestSEmit.render("vadd.vv", "RVTEST_RV64UV", List(block), Vector.empty, 64)
      // The resultdata store must appear BEFORE the magic word.
      val laPos    = s.indexOf("la a0, resultdata")
      val vsePos   = s.indexOf("vse32.v v8, (a0)")
      val magicPos = s.indexOf(".word 0x")
      assert(laPos > 0)
      assert(vsePos > laPos)
      assert(magicPos > vsePos)

    // ---- Codex r8 #3: widening result-EMUL in magic ----

    test("Codex r8 #3: TestSEmit honors resultWholeRegisters distinct from base LMUL"):
      // Simulate vwadd.vv at LMUL=4: dest EMUL is 8 registers, not 4.
      val envM4   = VTypeEnvelope.unsafe(
        VType(Sew.Sew32, Lmul.M4, Vta.Agnostic, Vma.Agnostic),
        vl = 4, vlen = 128, xlen = 64)
      val block   = TestSEmit.TestBlock(
        envelope             = envM4,
        vectorGroup          = 8,
        vxsat                = false,
        insnAsm              = "vwadd.vv v8, v16, v24",
        setupAsm             = Nil,
        dataLabel            = None,
        resultEew            = 64,     // 2 * SEW
        resultGroup          = 8,
        resultWholeRegisters = 8)      // dest EMUL = max(4 * 2, 1) = 8
      val s          = TestSEmit.render("vwadd.vv", "RVTEST_RV64UV", List(block), Vector.empty, 256)
      val magicLine  = s.split("\n").find(_.contains(".word 0x")).get
      // Decode the magic word — rs2[4:1] must be 8 (dest whole-reg count).
      val hex        = magicLine.trim.stripPrefix(".word 0x").take(8)
      val word       = java.lang.Long.parseLong(hex, 16).toInt
      val rs2Field   = (word >>> 21) & 0xf
      assert(rs2Field == 8)

    test("Codex r8 #3: MagicInstrEmit.encodeWithCount allows arbitrary {1,2,4,8}"):
      assert(((MagicInstrEmit.encodeWithCount(0, 1, false) >>> 21) & 0xf) == 1)
      assert(((MagicInstrEmit.encodeWithCount(0, 2, false) >>> 21) & 0xf) == 2)
      assert(((MagicInstrEmit.encodeWithCount(0, 4, false) >>> 21) & 0xf) == 4)
      assert(((MagicInstrEmit.encodeWithCount(0, 8, false) >>> 21) & 0xf) == 8)

    test("Codex r8 #3: encodeWithCount rejects non-{1,2,4,8} counts"):
      val ex = intercept[IllegalArgumentException]:
        MagicInstrEmit.encodeWithCount(0, 3, false)
      assert(ex.getMessage.contains("wholeRegisters"))

    test("Codex r8 #3: Driver vwadd.vv-style emission encodes dest EMUL"):
      // Build a synthetic widening RvvInsn matching vwadd.vv semantics.
      val widening = RvvInsn(
        name         = "vwadd.vv",
        extension    = "v",
        sourceToml   = "v/vwadd.vv.toml",
        schema       = Schema.VdVs2Vs1Vm,
        widthProfile = OperandWidthProfile(Map(me.jiuyang.rvprobe.rvv.OperandRole.Vd -> WidthScale.By2)))
      val tmp = Files.createTempDirectory("rvprobe-r9-")
      val cli = Driver.Cli(
        vlen            = 256,
        xlen            = 64,
        stage1OutputDir = tmp.toString,
        march           = "rv64gcv")
      Driver.emitOne(widening, cli, tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vwadd_vv_v-0.S")))
      // At least one magic word in the emission should have rs2[4:1] = 8
      // (one of the LMUL=4 blocks has dest EMUL = 8).
      val magicHexes = content.split("\n").toList
        .filter(_.contains(".word 0x"))
        .map(_.trim.stripPrefix(".word 0x").take(8))
        .map(s => java.lang.Long.parseLong(s, 16).toInt)
      val rs2Fields = magicHexes.map(w => (w >>> 21) & 0xf).toSet
      assert(rs2Fields.contains(8))

    // ---- Codex r8 #4: vsetvl scalar CSR check (no magic word) ----

    test("Codex r8 #4 + r10: vsetvl/Tests emits scalar TEST_CASE for vstart + vtype + vl"):
      val s = VsetvlTests.renderTestS("vsetvli", vlen = 256, xlen = 64, envMacro = "RVTEST_RV64UV")
      assert(s.contains("csrr a3, vstart"))
      assert(s.contains("csrr a4, vtype"))
      assert(s.contains("csrr a5, vl"))
      assert(s.contains("TEST_CASE("))
      assert(!s.contains(".word 0x"))

    test("Codex r8 #4: VsetvlCase.expectVl computed correctly"):
      // VLMAX for VLEN=256, SEW=32, LMUL=M1 = (256 * 1) / 32 = 8.
      val vt = VType(Sew.Sew32, Lmul.M1, Vta.Agnostic, Vma.Agnostic)
      assert(VsetvlTests.expectedVl(
        VsetvlTests.AvlSource.Imm(4), vt, vlen = 256, vill = false) == 4L)
      assert(VsetvlTests.expectedVl(
        VsetvlTests.AvlSource.Imm(16), vt, vlen = 256, vill = false) == 8L)
      assert(VsetvlTests.expectedVl(
        VsetvlTests.AvlSource.Imm(0), vt, vlen = 256, vill = false) == 0L)
      assert(VsetvlTests.expectedVl(
        VsetvlTests.AvlSource.Imm(4), vt, vlen = 256, vill = true) == 0L)

    test("Codex r8 #4: vtypeImmediate encodes vsew/vlmul/vta/vma"):
      val vt = VType(Sew.Sew32, Lmul.M1, Vta.Agnostic, Vma.Agnostic)
      val imm = VsetvlTests.vtypeImmediate(vt)
      // vsew=2 (Sew32), vlmul=0 (M1), vta=1, vma=1
      // 2 << 3 | 0 | 1 << 6 | 1 << 7 = 16 | 64 | 128 = 208
      assert(imm == 0xd0L)

    // ---- Codex r8 #5: parser fail-loud edges ----

    test("Codex r8 #5: parser rejects empty token rows `[]`"):
      val tmp = Files.createTempFile("rvprobe-empty-row-", ".toml")
      Files.write(tmp,
        """name = "test.empty"
          |format = "vd,vs2,vs1,vm"
          |
          |[tests]
          |sew8 = [
          |  [0x01, 0x02],
          |  [],
          |  [0x03, 0x04]
          |]
          |""".stripMargin.getBytes(StandardCharsets.UTF_8))
      val result = TomlIntent.parse("v", tmp)
      Files.deleteIfExists(tmp)
      assert(result.isLeft)
      assert(result.left.toOption.exists(_.contains("zero tokens")))

    test("Codex r8 #5: parser ACCEPTS empty per-key array (vmv1r.v upstream pattern)"):
      val tmp = Files.createTempFile("rvprobe-empty-per-key-", ".toml")
      Files.write(tmp,
        """name = "test.empty_per_key"
          |format = "vd,vs2"
          |
          |[tests]
          |""".stripMargin.getBytes(StandardCharsets.UTF_8))
      val result = TomlIntent.parse("v", tmp)
      Files.deleteIfExists(tmp)
      // Empty [tests] section is legitimate (upstream vmsif.m / vmv*r.v
      // pattern). Don't fail-loud on this.
      assert(result.isRight)

    // ---- Codex r8 #7: AC-2 source-link metadata ----

    test("Codex r8 #7: every RvvInsn has a non-empty sourceToml path"):
      val missing = RvvInsnRegistry.all.filter(_.sourceToml.isEmpty)
      assert(missing.isEmpty)

    test("Codex r8 #7: sourceToml format is <ext>/<name>.toml matching upstream"):
      val sample = RvvInsnRegistry.all.find(_.name == "vadd.vv").get
      assert(sample.sourceToml == "v/vadd.vv.toml")
      val dup = RvvInsnRegistry.all.filter(_.name == "vfncvt.f.f.w")
      // Both extensions, distinct sourceToml paths.
      assert(dup.map(_.sourceToml).toSet ==
        Set("v/vfncvt.f.f.w.toml", "zvfhmin/vfncvt.f.f.w.toml"))

    test("Codex r8 #7: every sourceToml matches a real upstream file (smoke)"):
      val configsRoot = java.nio.file.Paths.get("/root/rvprobe-workspace/riscv-vector-tests/configs")
      if Files.isDirectory(configsRoot) then
        val missing = RvvInsnRegistry.all.filterNot(insn =>
          Files.exists(configsRoot.resolve(insn.sourceToml)))
        if missing.nonEmpty then
          println(s"[Round9FixesTest] sourceToml paths not matching upstream: ${missing.take(5).map(_.sourceToml).mkString(", ")} (showing first 5 of ${missing.size})")
        assert(missing.isEmpty)
      else println("[Round9FixesTest] upstream configs/ unavailable; skipping sourceToml smoke check")
