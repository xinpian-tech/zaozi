// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.Schema
import me.jiuyang.rvprobe.rvv.unittest.{Driver, RvvInsn, Testfloat3Driver}
import me.jiuyang.rvprobe.rvv.vtype.Sew

import java.nio.file.{Files, Path, Paths}

import utest.*

/** Regressions for Codex round-8/9 HIGH issues addressed in round 11
 *  (real indexed/segmented/strided emission + testfloat3 wiring +
 *  CI workflow).
 */
object Round11FixesTest extends TestSuite:

  private def newTempDir(prefix: String): Path =
    Files.createTempDirectory(prefix).toAbsolutePath

  private val baseCli = Driver.Cli(
    vlen            = 256,
    xlen            = 64,
    stage1OutputDir = "/tmp/r11-stub",
    march           = "rv64gcv")

  val tests = Tests:

    // ---- Indexed load ----

    test("vluxei32.v real emission: index load + data buffer + magic"):
      val insn = RvvInsn(
        name       = "vluxei32.v",
        extension  = "v",
        sourceToml = "v/vluxei32.v.toml",
        schema     = Schema.VdRs1mVs2Vm,
        indexedEew = Some(32))
      val tmp = newTempDir("rvprobe-r11-")
      val cli = baseCli.copy(stage1OutputDir = tmp.toString)
      Driver.emitOne(insn, cli, tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vluxei32_v_v-0.S")))
      // Real instruction mnemonic with indexed-load form `vd, (rs1), vs2`
      assert(content.contains("vluxei32.v v8, (a1), v16"))
      // Real index buffer load
      assert(content.contains("vle32.v v16, (a1)"))
      // Two data labels: data + index
      assert(content.contains("indexed_data"))
      assert(content.contains("indexed_idx"))
      // No placeholder TODO
      assert(!content.contains("# TODO"))

    // ---- Indexed store ----

    test("vsuxei32.v real emission: src buffer + index buffer + dst label"):
      val insn = RvvInsn(
        name       = "vsuxei32.v",
        extension  = "v",
        sourceToml = "v/vsuxei32.v.toml",
        schema     = Schema.Vs3Rs1mVs2Vm,
        indexedEew = Some(32))
      val tmp = newTempDir("rvprobe-r11-")
      val cli = baseCli.copy(stage1OutputDir = tmp.toString)
      Driver.emitOne(insn, cli, tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vsuxei32_v_v-0.S")))
      assert(content.contains("vsuxei32.v v8, (a1), v16"))
      assert(content.contains("idxst_src"))
      assert(content.contains("idxst_idx"))
      assert(content.contains("idxst_dst"))
      assert(!content.contains("# TODO"))

    // ---- Strided load/store ----

    test("vlse32.v real emission: stride register + (rs1) base"):
      val insn = RvvInsn(
        name       = "vlse32.v",
        extension  = "v",
        sourceToml = "v/vlse32.v.toml",
        schema     = Schema.VdRs1mRs2Vm)
      val tmp = newTempDir("rvprobe-r11-")
      val cli = baseCli.copy(stage1OutputDir = tmp.toString)
      Driver.emitOne(insn, cli, tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vlse32_v_v-0.S")))
      assert(content.contains("vlse32.v v8, (a1), a2"))
      assert(content.contains("li a2, ")) // stride loaded into a2
      assert(content.contains("strided_data"))
      assert(!content.contains("# TODO"))

    test("vsse32.v real emission: stride register + (rs1) base for store"):
      val insn = RvvInsn(
        name       = "vsse32.v",
        extension  = "v",
        sourceToml = "v/vsse32.v.toml",
        schema     = Schema.Vs3Rs1mRs2Vm)
      val tmp = newTempDir("rvprobe-r11-")
      val cli = baseCli.copy(stage1OutputDir = tmp.toString)
      Driver.emitOne(insn, cli, tmp)
      val content = new String(Files.readAllBytes(tmp.resolve("vsse32_v_v-0.S")))
      assert(content.contains("vsse32.v v8, (a1), a2"))
      assert(content.contains("strided_src"))
      assert(content.contains("strided_dst"))
      assert(!content.contains("# TODO"))

    // ---- Testfloat3 subprocess ----

    test("Testfloat3Driver.resolveBinary returns None gracefully if binary absent"):
      // In this sandbox the binary is not built. The driver must NOT
      // throw; it must return Right or Left(...) so callers can fall
      // back gracefully.
      val r = Testfloat3Driver.resolveBinary()
      // Either None (binary absent) or Some(...) if installed. Both OK.
      assert(r.isEmpty || r.exists(p => Files.isExecutable(p)))

    test("Testfloat3Driver.generate falls back with explanatory Left when binary absent"):
      val resolved = Testfloat3Driver.resolveBinary()
      if resolved.isEmpty then
        val req = Testfloat3Driver.Request(
          operation = "f32_add",
          sew       = Sew.Sew32,
          rmFlag    = "-rnear_even",
          testLevel = 2)
        val r = Testfloat3Driver.generate(req)
        assert(r.isLeft)
        assert(r.left.toOption.exists(_.contains("testfloat3 binary not found")))
      else
        println("[Round11FixesTest] testfloat_gen is on PATH; skipping fallback test")

    test("Testfloat3Driver.AllFrm covers all 5 RVV rounding modes"):
      val names = Testfloat3Driver.AllFrm.map(_._1).toSet
      assert(names == Set("RNE", "RTZ", "RDN", "RUP", "RMM"))

    // ---- CI workflow file ----

    test("Drift-check CI workflow exists at .github/workflows/"):
      // Walk up from the cwd to find the project root.
      val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath
      val workflow = LazyList.iterate(cwd: Path)(_.getParent).takeWhile(_ != null)
        .map(_.resolve(".github/workflows/drift-check.yml"))
        .find(p => Files.exists(p))
      assert(workflow.isDefined)
      val text = new String(Files.readAllBytes(workflow.get))
      // Workflow must invoke runDriftCheck and pin upstream-tests path
      assert(text.contains("runDriftCheck"))
      assert(text.contains("RVPROBE_RVV_TESTS_CONFIGS"))
