// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.Schema
import me.jiuyang.rvprobe.rvv.audit.TomlIntent
import me.jiuyang.rvprobe.rvv.unittest.{Driver, MagicInstrEmit, RvvInsn, RvvInsnRegistry}
import me.jiuyang.rvprobe.rvv.vsetvl.Tests as VsetvlTests
import me.jiuyang.rvprobe.rvv.vtype.Lmul

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import utest.*

/** Regressions for the 6 HIGH + 1 MEDIUM issues Codex round-7 flagged.
 *  Each section addresses one issue.
 */
object Round8FixesTest extends TestSuite:

  val tests = Tests:

    // ---- Codex r7 #1: pspike magic-word encoding ----

    test("Codex r7 #1: magic-word rs2[4:1] is whole-register count, not vlmul"):
      // Upstream gMagicInsn writes `int(lmul1) << 21` where lmul1 is
      // max(lmul, 1). So M1/Mfractional -> 1, M2 -> 2, M4 -> 4, M8 -> 8.
      assert(MagicInstrEmit.wholeRegisterCount(Lmul.M1) == 1)
      assert(MagicInstrEmit.wholeRegisterCount(Lmul.M2) == 2)
      assert(MagicInstrEmit.wholeRegisterCount(Lmul.M4) == 4)
      assert(MagicInstrEmit.wholeRegisterCount(Lmul.M8) == 8)
      assert(MagicInstrEmit.wholeRegisterCount(Lmul.Mf2) == 1)
      assert(MagicInstrEmit.wholeRegisterCount(Lmul.Mf4) == 1)
      assert(MagicInstrEmit.wholeRegisterCount(Lmul.Mf8) == 1)

    test("Codex r7 #1: decode produces whole-register count, not vlmul"):
      val w = MagicInstrEmit.encode(8, Lmul.M4, false)
      MagicInstrEmit.decode(w) match
        case Some((group, rc, _)) =>
          assert(group == 8)
          assert(rc == 4) // NOT 2 (which was the old vlmul value)
        case None                 => assert(false)

    // ---- Codex r7 #2: TomlIntent fail-loud parser ----

    test("Codex r7 #2: parse fails loudly on malformed row (unterminated quote)"):
      val tmp = Files.createTempFile("rvprobe-malformed-", ".toml")
      Files.write(tmp,
        """name = "x.test"
          |format = "vd,vs2,vs1,vm"
          |
          |[tests]
          |sew8 = [
          |  ["unterminated, 0x01]
          |]
          |""".stripMargin.getBytes(StandardCharsets.UTF_8))
      val result = TomlIntent.parse("v", tmp)
      Files.deleteIfExists(tmp)
      assert(result.isLeft)

    test("Codex r7 #2: parse fails loudly on invalid integer token (non-hex)"):
      val tmp = Files.createTempFile("rvprobe-bad-int-", ".toml")
      Files.write(tmp,
        """name = "y.test"
          |format = "vd,vs2,vs1,vm"
          |
          |[tests]
          |sew8 = [
          |  [0x01, not_a_hex_value]
          |]
          |""".stripMargin.getBytes(StandardCharsets.UTF_8))
      val result = TomlIntent.parse("v", tmp)
      Files.deleteIfExists(tmp)
      assert(result.isLeft)
      assert(result.left.toOption.exists(_.contains("not a hex integer literal")))

    test("Codex r7 #2: parse accepts empty test array (upstream vmv*r.v / vsext / vzext pattern)"):
      // Upstream tomls legitimately have empty `sew8 = []` etc. for
      // sub-SEW exclusions (vsext.vf2/4/8) and zero-test mask-set-bit
      // instructions (vmv1r.v, vmsif.m). The parser must accept these,
      // not fail-loud, otherwise the corpus walk would lose 14+ insns.
      val tmp = Files.createTempFile("rvprobe-empty-", ".toml")
      Files.write(tmp,
        """name = "z.test"
          |format = "vd,vs2,vs1,vm"
          |
          |[tests]
          |sew8 = [
          |]
          |sew16 = [[0x01, 0x02]]
          |""".stripMargin.getBytes(StandardCharsets.UTF_8))
      val result = TomlIntent.parse("v", tmp)
      Files.deleteIfExists(tmp)
      assert(result.isRight) // empty arrays are legitimate

    // ---- Codex r7 #4: CLI parity ----

    test("Codex r7 #4: parseCli accepts -flag=value form"):
      val r = Driver.parseCli(Array("-VLEN=256", "-XLEN=64", "-stage1output=/tmp/x"))
      assert(r.isRight)
      val cli = r.toOption.get
      assert(cli.vlen == 256)
      assert(cli.xlen == 64)
      assert(cli.stage1OutputDir == "/tmp/x")

    test("Codex r7 #4: parseCli accepts -integer=true and -integer=false"):
      val rTrue  = Driver.parseCli(Array("-integer=true", "-stage1output=/tmp/x"))
      val rFalse = Driver.parseCli(Array("-integer=false", "-stage1output=/tmp/x"))
      assert(rTrue.isRight && rTrue.toOption.get.integerOnly)
      assert(rFalse.isRight && !rFalse.toOption.get.integerOnly)

    test("Codex r7 #4: parseCli accepts Makefile's exact form"):
      // Upstream Makefile: -VLEN ${VLEN} -XLEN ${XLEN} -split=${SPLIT} -integer=${INTEGER}
      //                    -pattern='${PATTERN}' -testfloat3level='${TF3}' -repeat='${R}'
      //                    -stage1output ${OUT} -configs ${CFG} -march ${MARCH}
      val r = Driver.parseCli(Array(
        "-VLEN", "256", "-XLEN", "64",
        "-split=10000",
        "-integer=false",
        "-pattern=.*",
        "-testfloat3level=2",
        "-repeat=1",
        "-stage1output", "/tmp/x",
        "-configs", "configs/",
        "-march", "rv64gcv"))
      assert(r.isRight)

    test("Codex r7 #4: integer mode preserves vfirst* exception"):
      // Upstream main.go:142 keeps `vfirst*` even when -integer=true.
      val cli = Driver.parseCli(Array("-integer=true", "-stage1output=/tmp/x")).toOption.get
      val selected = Driver.selectInsns(RvvInsnRegistry.all, cli)
      val hasVfirst = selected.exists(_.name == "vfirst.m")
      assert(hasVfirst)
      // And other vf* should be dropped
      val hasVfadd = selected.exists(_.name == "vfadd.vv")
      assert(!hasVfadd)

    // ---- Codex r7 #5: vsetvl/Tests dedicated codepath ----

    test("Codex r7 #5: VsetvlTests.cases produces non-empty sweep for vsetvli"):
      val cs = VsetvlTests.cases(vlen = 256, xlen = 64).filter(_.insn == "vsetvli")
      assert(cs.nonEmpty)

    test("Codex r7 #5: VsetvlTests includes one expectVill case (DEC-9 sub-decision)"):
      val cs   = VsetvlTests.cases(vlen = 256, xlen = 64)
      val vill = cs.filter(_.expectVill)
      assert(vill.nonEmpty)

    test("Codex r7 #5: VsetvlTests covers all 3 variants"):
      val cs       = VsetvlTests.cases(vlen = 256, xlen = 64)
      val variants = cs.map(_.insn).toSet
      assert(variants == Set("vsetvli", "vsetivli", "vsetvl"))

    test("Codex r7 #5 + r8 #4 + r10: VsetvlTests.renderTestS uses scalar CSR TEST_CASE"):
      val s = VsetvlTests.renderTestS("vsetvli", vlen = 256, xlen = 64, envMacro = "RVTEST_RV64UV")
      assert(s.contains("RVTEST_CODE_BEGIN"))
      assert(s.contains("vsetvli"))
      // pspike doesn't read vl/vtype CSRs; the dedicated codepath emits
      // scalar TEST_CASE rows comparing csrr vstart/vtype/vl against
      // expected values (round-10 added vstart per upstream
      // insn_vsetvli.go:59). No vector magic word.
      assert(s.contains("csrr a3, vstart"))
      assert(s.contains("csrr a4, vtype"))
      assert(s.contains("csrr a5, vl"))
      assert(s.contains("TEST_CASE("))
      assert(!s.contains(".word 0x"))
      assert(s.contains("TEST_PASSFAIL"))

    // ---- Codex r7 #6: Driver real emission (not placeholder) ----

    test("Codex r7 #6: emitOne for vadd.vv produces real assembly, not placeholder"):
      val tmp  = Files.createTempDirectory("rvprobe-real-emit-")
      val cli  = Driver.Cli(
        vlen            = 256,
        xlen            = 64,
        stage1OutputDir = tmp.toString,
        march           = "rv64gcv")
      val insn = RvvInsnRegistry.all.find(_.name == "vadd.vv").get
      Driver.emitOne(insn, cli, tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vadd_vv_v-0.S")))
      // Real instruction mnemonic, not "# placeholder"
      assert(content.contains("vadd.vv v8, v16, v24"))
      assert(!content.contains("placeholder"))
      // Real testdata bytes (not empty)
      assert(content.contains(".byte 0x"))
      // Real vle*.v loads to populate vs1/vs2
      assert(content.contains("vle"))

    // ---- Codex r7 #8: InsnsGenerator strict on unknown formats ----

    test("Codex r7 #8: InsnsGenerator.renderInsn throws on unknown schema format"):
      import me.jiuyang.rvprobe.rvv.audit.InsnsGenerator
      val bad = InsnsGenerator.GenInfo(
        extension    = "v",
        name         = "test_unknown",
        format       = "this,is,not,a,real,format",
        vxrm         = false,
        vxsat        = false,
        notestfloat3 = false)
      val ex = intercept[IllegalStateException]:
        InsnsGenerator.renderInsn(bad)
      assert(ex.getMessage.contains("unknown schema format"))

    // ---- Codex r7 #3: Upstream Makefile uses $(GENERATOR) ----

    test("Codex r7 #3: upstream Makefile uses $(GENERATOR) (DEC-3 swap)"):
      // The single-line swap: build/generator -> $(GENERATOR), with
      // GENERATOR ?= build/generator default. Verified in the upstream
      // Makefile at riscv-vector-tests/Makefile.
      val mkPath = java.nio.file.Paths.get("/root/rvprobe-workspace/riscv-vector-tests/Makefile")
      if Files.exists(mkPath) then
        val content = new String(Files.readAllBytes(mkPath))
        assert(content.contains("GENERATOR ?= build/generator"))
        assert(content.contains("$(GENERATOR) -VLEN"))
      else println("[Round8FixesTest] upstream Makefile not present; skipping DEC-3 check")
