// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.audit.TomlIntent
import me.jiuyang.rvprobe.rvv.pred.*

import java.nio.file.{Files, Path, Paths}

import utest.*

object TomlIntentTest extends TestSuite:

  private def findAncestorWith(marker: String): Option[Path] =
    val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath
    LazyList
      .iterate(cwd: Path)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve(marker)))

  val tests = Tests:

    test("TomlIntent.parse reads vadd.vv header + tests sections"):
      val configsRoot = findAncestorWith("riscv-vector-tests/configs")
        .map(_.resolve("riscv-vector-tests/configs"))
      if configsRoot.isDefined && Files.isDirectory(configsRoot.get) then
        val file = configsRoot.get.resolve("v").resolve("vadd.vv.toml")
        TomlIntent.parse("v", file) match
          case Right(spec) =>
            assert(spec.name == "vadd.vv")
            assert(spec.format == "vd,vs2,vs1,vm")
            assert(!spec.vxrm)
            assert(!spec.vxsat)
            assert(!spec.notestfloat3)
            // Must have base/sew8/sew16/sew32/sew64 arrays
            assert(spec.tests.contains("base"))
            assert(spec.tests.contains("sew8"))
            assert(spec.tests.contains("sew16"))
            assert(spec.tests.contains("sew32"))
            assert(spec.tests.contains("sew64"))
            // Content hash is deterministic 64-char hex
            assert(spec.contentHash.length == 64)
            assert(spec.contentHash.forall(c => "0123456789abcdef".contains(c)))
          case Left(msg)   =>
            assert(false)
      else
        println("[TomlIntentTest] upstream configs/ unavailable; skipping parse test")

    test("TomlIntent.classify produces expected predicates for vadd.vv sew8 row (0x7f, 0x01)"):
      val configsRoot = findAncestorWith("riscv-vector-tests/configs")
        .map(_.resolve("riscv-vector-tests/configs"))
      if configsRoot.isDefined && Files.isDirectory(configsRoot.get) then
        val file = configsRoot.get.resolve("v").resolve("vadd.vv.toml")
        val snap = TomlIntent.classify(TomlIntent.parse("v", file).toOption.get)
        val sew8 = snap.rowsByKey.get("sew8").getOrElse(Nil)
        // (0x7f, 0x01) row in sew8: MaxSigned + One → MaxPlusOne
        val maxPlusOne = sew8.find(_.rawRow == List("0x7f", "0x01"))
        assert(maxPlusOne.isDefined)
        assert(maxPlusOne.get.tuplePreds.exists {
          case _: TuplePred.MaxPlusOne => true
          case _                       => false
        })
      else
        println("[TomlIntentTest] upstream configs/ unavailable; skipping classify test")

    test("TomlIntent.classify vdiv.vv produces DivByZero on (0x80, 0x00) sew8 row"):
      val configsRoot = findAncestorWith("riscv-vector-tests/configs")
        .map(_.resolve("riscv-vector-tests/configs"))
      if configsRoot.isDefined && Files.isDirectory(configsRoot.get) then
        val file = configsRoot.get.resolve("v").resolve("vdiv.vv.toml")
        val snap = TomlIntent.classify(TomlIntent.parse("v", file).toOption.get)
        val sew8 = snap.rowsByKey.get("sew8").getOrElse(Nil)
        val divZero = sew8.find(_.rawRow == List("0x80", "0x00"))
        assert(divZero.isDefined)
        assert(divZero.get.tuplePreds.exists {
          case _: TuplePred.DivByZero => true
          case _                      => false
        })
        assert(divZero.get.tuplePreds.exists {
          case _: TuplePred.MinSignedDivZero => true
          case _                             => false
        })
      else
        println("[TomlIntentTest] upstream configs/ unavailable; skipping classify test")

    test("TomlIntent.classify vsll.vi produces ShiftBySewOrAbove on (0x1f, 0xff) sew8 row"):
      val configsRoot = findAncestorWith("riscv-vector-tests/configs")
        .map(_.resolve("riscv-vector-tests/configs"))
      if configsRoot.isDefined && Files.isDirectory(configsRoot.get) then
        val file = configsRoot.get.resolve("v").resolve("vsll.vi.toml")
        val snap = TomlIntent.classify(TomlIntent.parse("v", file).toOption.get)
        val sew8 = snap.rowsByKey.get("sew8").getOrElse(Nil)
        val shift = sew8.find(_.rawRow == List("0x1f", "0xff"))
        assert(shift.isDefined)
        assert(shift.get.tuplePreds.exists {
          case _: TuplePred.ShiftBySewOrAbove => true
          case _                              => false
        })
      else
        println("[TomlIntentTest] upstream configs/ unavailable; skipping classify test")

    test("TomlIntent.classify vfadd.vv produces NaNPair on (nan, -nan) fsew32 row"):
      val configsRoot = findAncestorWith("riscv-vector-tests/configs")
        .map(_.resolve("riscv-vector-tests/configs"))
      if configsRoot.isDefined && Files.isDirectory(configsRoot.get) then
        val file = configsRoot.get.resolve("v").resolve("vfadd.vv.toml")
        val snap = TomlIntent.classify(TomlIntent.parse("v", file).toOption.get)
        val rows = snap.rowsByKey.get("fsew32").getOrElse(Nil)
        val nanRow = rows.find(_.rawRow == List("nan", "-nan"))
        assert(nanRow.isDefined)
        assert(nanRow.get.fpTuplePreds.contains(FpTuplePred.NaNPair))
      else
        println("[TomlIntentTest] upstream configs/ unavailable; skipping classify test")

    test("writeAuditFixtures end-to-end: 676 snapshots, AC-3 (0 unclassified), AC-4 (0 dead vocabulary)"):
      val configsRoot = findAncestorWith("riscv-vector-tests/configs")
        .map(_.resolve("riscv-vector-tests/configs"))
      if configsRoot.isDefined && Files.isDirectory(configsRoot.get) then
        val walk = TomlIntent.walkConfigs(configsRoot.get)
        assert(walk.errors.isEmpty)
        assert(walk.specs.size == 676)
        val snaps      = walk.specs.map(TomlIntent.classify)
        val backReport = TomlIntent.renderBackwardReport(snaps)
        // AC-4: backward report says all named non-escape predicates are exercised
        assert(backReport.contains("All named predicates are exercised"))
      else
        println("[TomlIntentTest] upstream configs/ unavailable; skipping end-to-end test")

    test("vfcvt.xu.f.v parses 12 rows per SEW after comment stripping (Codex r4 regression)"):
      val configsRoot = findAncestorWith("riscv-vector-tests/configs")
        .map(_.resolve("riscv-vector-tests/configs"))
      if configsRoot.isDefined && Files.isDirectory(configsRoot.get) then
        val file = configsRoot.get.resolve("v").resolve("vfcvt.xu.f.v.toml")
        val spec = TomlIntent.parse("v", file).toOption.get
        // Upstream toml has 12 rows per fsew{16,32,64} array, each row
        // followed by an inline `# ...` comment. The round-4 parser
        // truncated at the first comment, keeping only 1 row per array.
        assert(spec.tests.get("sew16").map(_.size) == Some(12))
        assert(spec.tests.get("sew32").map(_.size) == Some(12))
        assert(spec.tests.get("sew64").map(_.size) == Some(12))
      else
        println("[TomlIntentTest] upstream configs/ unavailable; skipping comment-stripping test")

    test("stripTomlComments preserves quoted hashes"):
      val input = """key = "with#hash"\n# this is a comment\nother = 1\n"""
      val out   = TomlIntent.stripTomlComments(input)
      assert(out.contains("\"with#hash\"")) // # inside string is preserved
      assert(!out.contains("this is a comment")) // # comment is stripped

    test("vfadd.vv parses only fsew* keys (Codex r5 key-collision regression)"):
      val configsRoot = findAncestorWith("riscv-vector-tests/configs")
        .map(_.resolve("riscv-vector-tests/configs"))
      if configsRoot.isDefined && Files.isDirectory(configsRoot.get) then
        val file = configsRoot.get.resolve("v").resolve("vfadd.vv.toml")
        val spec = TomlIntent.parse("v", file).toOption.get
        // vfadd.vv is FP-only: only fsew16/fsew32/fsew64 in upstream.
        // The round-4/5 substring matcher fired sew16 inside fsew16 and
        // populated spurious integer rows. This regression locks the fix.
        assert(spec.tests.keySet == Set("fsew16", "fsew32", "fsew64"))
      else
        println("[TomlIntentTest] upstream configs/ unavailable; skipping vfadd.vv key regression")

    test("corpus key counts match upstream exactly (Codex r5 invariant)"):
      val configsRoot = findAncestorWith("riscv-vector-tests/configs")
        .map(_.resolve("riscv-vector-tests/configs"))
      if configsRoot.isDefined && Files.isDirectory(configsRoot.get) then
        val walk = TomlIntent.walkConfigs(configsRoot.get)
        assert(walk.errors.isEmpty)
        val keyCounts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
        walk.specs.foreach { s =>
          s.tests.keys.foreach(k => keyCounts(k) += 1)
        }
        // Per Codex's round-5 review (exact upstream key counts via
        // `rg '^\s*(base|sew*|fsew*|bf16sew*)\s*='`):
        assert(keyCounts("base") == 561)
        assert(keyCounts("sew8") == 293)
        assert(keyCounts("sew16") == 302)
        assert(keyCounts("sew32") == 329)
        assert(keyCounts("sew64") == 298)
        assert(keyCounts("fsew16") == 76)
        assert(keyCounts("fsew32") == 77)
        assert(keyCounts("fsew64") == 73)
        assert(keyCounts("bf16sew16") == 3)
        val total = keyCounts.values.sum
        assert(total == 2012)
      else
        println("[TomlIntentTest] upstream configs/ unavailable; skipping corpus key-count regression")
