// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.unittest.AcGateRunner

import java.nio.file.{Files, Paths}

import utest.*

/** Regressions for Codex round-18 review fixed in round 19. */
object Round19FixesTest extends TestSuite:

  val tests = Tests:

    // ---- Codex r18 #4 (HIGH): testfloat_gen in RequiredTools ----

    test("Codex r18 #4: AcGateRunner.RequiredTools includes testfloat_gen"):
      assert(AcGateRunner.RequiredTools.contains("testfloat_gen"))

    test("Codex r18 #4: RequiredTools has the 4 expected tools"):
      val expected = Set("spike", "pspike", "merger", "testfloat_gen")
      assert(AcGateRunner.RequiredTools.toSet == expected)

    test("Codex r18 #4: sandbox run reports testfloat_gen in missing_tools"):
      val tmp    = Files.createTempDirectory("rvprobe-r19-")
      val cfg    = AcGateRunner.Config(vlen = 256, xlen = 64, march = "rv64gcv")
      val result = AcGateRunner.run(tmp, cfg)
      // In this sandbox, testfloat_gen is not on PATH.
      assert(result.missingTools.contains("testfloat_gen"))
      // All 4 tools missing → status MUST be `unavailable`.
      assert(result.status == "unavailable")

    // ---- Codex r18 blocking #1 (HIGH): no env escape, fake tool defence ----

    test("Codex r18 blocking #1: Round18FixesTest source no longer references env escape"):
      // This is a meta-regression: the source of Round18FixesTest must
      // no longer reference the RVPROBE_ACGATE_TOOLS_PRESENT escape
      // hatch (which would mask false-pass bugs in CI if mistakenly
      // set).
      val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath
      val testFile = LazyList.iterate(cwd: java.nio.file.Path)(_.getParent)
        .takeWhile(_ != null)
        .map(_.resolve("rvprobe/tests/src/rvv/Round18FixesTest.scala"))
        .find(p => Files.exists(p))
      assert(testFile.isDefined)
      val text = new String(Files.readAllBytes(testFile.get))
      assert(!text.contains("RVPROBE_ACGATE_TOOLS_PRESENT"))

    test("Codex r18 blocking #1: gate refuses `pass` whenever missingTools is non-empty"):
      // Even in a hypothetical scenario where the run produced no
      // failures, missing required tools alone must keep status off
      // `pass`. Verify against the result class directly.
      val tmp    = Files.createTempDirectory("rvprobe-r19-")
      val cfg    = AcGateRunner.Config(vlen = 256, xlen = 64, march = "rv64gcv")
      val result = AcGateRunner.run(tmp, cfg)
      if result.missingTools.nonEmpty then
        assert(result.status != "pass")

    // ---- Codex r18 blocking #2 (HIGH): run-release-matrix honest skip ----

    test("Codex r18 blocking #2: run-release-matrix no longer prints `PASSED on all selected cells (or skipped)`"):
      val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath
      val script = LazyList.iterate(cwd: java.nio.file.Path)(_.getParent)
        .takeWhile(_ != null)
        .map(_.resolve("rvprobe/scripts/run-release-matrix"))
        .find(p => Files.exists(p))
      assert(script.isDefined)
      val text = new String(Files.readAllBytes(script.get))
      // The old false-pass line MUST be gone.
      assert(!text.contains("PASSED on all selected cells (or skipped)"))
      // The new unavailable behavior must be present.
      assert(text.contains("AC-6 UNAVAILABLE"))
      assert(text.contains("UNAVAILABLE: $TAG"))
      // Non-zero exit on skip path (exit 3 marker).
      assert(text.contains("exit 3"))
