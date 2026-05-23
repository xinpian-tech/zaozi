// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.unittest.AcGateRunner

import java.nio.file.{Files, Paths}

import utest.*

/** Regressions for Codex round-17 review fixed in round 18 (AC-16 gate scaffold). */
object Round18FixesTest extends TestSuite:

  val tests = Tests:

    test("Codex r17 #1: AcGateRunner emits 9 POC + vsetvli = 10 files"):
      val tmp    = Files.createTempDirectory("rvprobe-r18-gate-")
      val config = AcGateRunner.Config(vlen = 256, xlen = 64, march = "rv64gcv")
      val result = AcGateRunner.run(tmp, config)
      // 10 POC names; in a clean run, all should emit successfully.
      // If TestFloat-3 is unavailable, vfadd.vv still emits but is
      // flagged as a structural failure (xorshift marker) — so the
      // emitted-files count should remain 10 even when status=fail.
      assert(result.files.size == 10)

    test("Codex r17 #1: AcGateRunner reports `unavailable` when spike not on PATH"):
      val tmp    = Files.createTempDirectory("rvprobe-r18-gate-")
      val config = AcGateRunner.Config(vlen = 256, xlen = 64, march = "rv64gcv")
      val result = AcGateRunner.run(tmp, config)
      // Codex r18 blocking #1: removed env-variable escape hatch
      // that could mask false-pass bugs. In this sandbox
      // spike/pspike/merger/testfloat_gen are not on PATH; status
      // MUST be `unavailable`, missing_tools MUST list at least
      // spike.
      assert(result.status == "unavailable")
      assert(result.missingTools.contains("spike"))

    test("Codex r18 blocking #1: gate cannot return `pass` without all required tools"):
      // Invariant: if any RequiredTool is missing, status MUST be
      // `unavailable`, NEVER `pass` (no matter how cleanly files
      // emit). The current sandbox is missing all 4 tools (spike,
      // pspike, merger, testfloat_gen).
      val tmp    = Files.createTempDirectory("rvprobe-r18-gate-")
      val config = AcGateRunner.Config(vlen = 256, xlen = 64, march = "rv64gcv")
      val result = AcGateRunner.run(tmp, config)
      assert(result.missingTools.nonEmpty)
      assert(result.status != "pass")

    test("Codex r17 #1: vfadd.vv xorshift-fallback emission is flagged in failures"):
      val tmp    = Files.createTempDirectory("rvprobe-r18-gate-")
      val config = AcGateRunner.Config(vlen = 256, xlen = 64, march = "rv64gcv")
      val result = AcGateRunner.run(tmp, config)
      // The xorshift fallback marker MUST be present in vfadd.vv's
      // emitted file (in the sandbox without testfloat_gen), and the
      // gate MUST log this as a failure entry (alongside reporting
      // `unavailable` due to missing spike).
      val xorshiftFailure = result.failures.exists(f =>
        f.file.contains("vfadd_vv") && f.reason.contains("xorshift"))
      assert(xorshiftFailure)

    test("Codex r17 #1: evidence.json artifact written with expected schema"):
      val tmp    = Files.createTempDirectory("rvprobe-r18-gate-")
      val config = AcGateRunner.Config(vlen = 256, xlen = 64, march = "rv64gcv")
      AcGateRunner.run(tmp, config)
      val evidencePath = tmp.resolve("evidence.json")
      assert(Files.exists(evidencePath))
      val text = new String(Files.readAllBytes(evidencePath))
      // Spot-check schema keys.
      assert(text.contains("\"version\": 1"))
      assert(text.contains("\"config\""))
      assert(text.contains("\"vlen\": 256"))
      assert(text.contains("\"xlen\": 64"))
      assert(text.contains("\"march\": \"rv64gcv\""))
      assert(text.contains("\"files\""))
      assert(text.contains("\"tool_versions\""))
      assert(text.contains("\"status\""))
      assert(text.contains("\"missing_tools\""))
      assert(text.contains("\"failures\""))

    test("Codex r17 #1: PocNames matches AC-16 declared count (10 with vsetvli)"):
      assert(AcGateRunner.PocNames.size == 10)
      // The 9 declared AC-16 POC instructions plus vsetvli for the
      // CSR-only path. Per round-1 architecture, vsetvli is in a
      // separate codepath but still part of the POC gate.
      assert(AcGateRunner.PocNames.contains("vsetvli"))
      assert(AcGateRunner.PocNames.contains("vfadd.vv"))

    test("Codex r17 #1: RequiredTools includes spike, pspike, merger"):
      // Round 19 added testfloat_gen per Codex r18 #4; keep the
      // historical r17 #1 assertion as subset-membership.
      assert(AcGateRunner.RequiredTools.contains("spike"))
      assert(AcGateRunner.RequiredTools.contains("pspike"))
      assert(AcGateRunner.RequiredTools.contains("merger"))

    test("Codex r17 #1: AcGateRunner status `pass` requires no failures + all tools"):
      // Status semantics test: simulate via the Result case class.
      val cfg = AcGateRunner.Config(vlen = 256, xlen = 64, march = "rv64gcv")
      val tmp = Files.createTempDirectory("rvprobe-r18-gate-")
      val r   = AcGateRunner.run(tmp, cfg)
      // `pass` impossible in this sandbox; confirm the gate doesn't
      // wishfully report it.
      assert(r.status != "pass")
