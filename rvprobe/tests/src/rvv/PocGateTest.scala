// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.unittest.{Driver, RvvInsn, RvvInsnRegistry, TestSEmit}

import java.nio.file.Files

import utest.*

/** AC-16 (9-case POC gate) verification at the emission level.
 *
 *  The plan's AC-16 calls for these 9 cases passing end-to-end through
 *  stage1 -> pspike -> merger -> stage2 -> spike on the release-minimum
 *  config (VLEN=256, XLEN=64, full march). Because spike + pspike are
 *  not necessarily available in every dev environment, this test
 *  validates the rvprobe-side of the contract: each POC instruction
 *  is present in the registry, its emission produces a well-formed
 *  `.S` (RVTEST_* macros, magic word, data section), and the Driver's
 *  filter-then-emit path runs.
 *
 *  The final spike-side soak is the task18 release-matrix soak; this
 *  test confirms the rvprobe driver does not regress the AC-16 set.
 */
object PocGateTest extends TestSuite:

  /** The 9 AC-16 POC instructions. */
  private val PocNames: List[String] = List(
    "vadd.vv",          // basic integer
    "vwadd.vv",         // widening (vd doubled at LMUL=4)
    "vmseq.vv",         // mask-producing
    "vle32.v",          // unit-stride load
    "vse32.v",          // unit-stride store
    "vluxei32.v",       // indexed load (EEW separation)
    "vsseg2e32.v",      // segmented store (NFIELDS x EMUL <= 8)
    "vnclip.wv",        // saturating with vxrm
    "vfadd.vv"          // FP with FRM (testfloat3 path per DEC-4)
  )

  val tests = Tests:

    test("AC-16: every POC instruction exists in the registry"):
      val present = PocNames.filter(n => RvvInsnRegistry.all.exists(_.name == n))
      val missing = PocNames.diff(present)
      if missing.nonEmpty then
        println(s"[PocGateTest] missing from registry: ${missing.mkString(", ")}")
      assert(missing.isEmpty)

    test("AC-16: vsetvli is part of the registry (separate codepath verified)"):
      // Per round-1 architecture: vsetvl* instructions are in Schema as
      // sealed-family entries; the dedicated codepath lives at
      // src/rvv/vsetvl/Tests.scala (task12). The registry includes the
      // instruction declaration; the test below confirms presence.
      val vsetvli = RvvInsnRegistry.all.find(_.name == "vsetvli")
      assert(vsetvli.isDefined)

    test("AC-16: emit each POC insn through Driver, verify file structure + mnemonic + FP FRM sweep"):
      val tmpDir = Files.createTempDirectory("rvprobe-poc-").toAbsolutePath
      val cli    = Driver.Cli(
        vlen            = 256,
        xlen            = 64,
        splitLines      = 10000,
        integerOnly     = false,
        pattern         = ".*",
        stage1OutputDir = tmpDir.toString,
        testfloat3Level = 2,
        repeat          = 1,
        march           = "rv64gcv_zvbb_zvbc_zfh_zvfh_zvkg_zvkned_zvknha_zvksed_zvksh_zfbfmin_zvfbfmin_zvfbfwma")
      for name <- PocNames do
        RvvInsnRegistry.all.find(_.name == name) match
          case None => assert(false)
          case Some(insn) =>
            val baseName = Driver.emitOne(insn, cli, tmpDir)
            val fileName = s"$baseName.S"
            val path     = tmpDir.resolve(fileName)
            assert(Files.exists(path))
            val content = new String(Files.readAllBytes(path))
            // Every emitted .S must carry the upstream RVTEST_* contract
            assert(content.contains("RVTEST_CODE_BEGIN"))
            assert(content.contains("RVTEST_CODE_END"))
            assert(content.contains("RVTEST_DATA_BEGIN"))
            assert(content.contains("RVTEST_DATA_END"))
            assert(content.contains(".word 0x")) // magic word for pspike
            assert(content.contains("TEST_PASSFAIL"))
            // Codex r12 #4 (HIGH): each POC file MUST contain the
            // instruction-under-test mnemonic as a real `.S` line —
            // not as a TODO comment, not skipped through a placeholder
            // path. Look for the mnemonic followed by a space (which
            // disambiguates `vadd.vv ` from `# TODO vadd.vv`).
            val mnemonicPresent = content.split("\n").exists { line =>
              val trimmed = line.trim
              !trimmed.startsWith("#") && trimmed.startsWith(s"$name ")
            }
            if !mnemonicPresent then
              println(s"[PocGateTest] FAIL: $name mnemonic missing from emitted .S")
              println(s"  file=$path")
            assert(mnemonicPresent)
            // FP instructions must carry the FRM sweep (≥5 csrwi frm
            // lines, one per RNE/RTZ/RDN/RUP/RMM).
            if name.startsWith("vf") then
              val frmCount = content.split("\n").count(_.trim.startsWith("csrwi frm,"))
              if frmCount < 5 then
                println(s"[PocGateTest] FAIL: $name has $frmCount csrwi frm, need ≥5")
              assert(frmCount >= 5)

    test("AC-16: PocGate rejects `# TODO` comment-only instruction bodies"):
      // Synthetic .S whose only mention of `vfadd.vv` is in a TODO
      // comment must NOT be classified as a real POC emission.
      val synthetic =
        """RVTEST_CODE_BEGIN
          |  # TODO vfadd.vv unavailable
          |  csrwi frm, 0
          |  csrwi frm, 1
          |  csrwi frm, 2
          |  csrwi frm, 3
          |  csrwi frm, 4
          |  .word 0xdeadbeef
          |  TEST_PASSFAIL
          |RVTEST_CODE_END
          |""".stripMargin
      val mnemonicPresent = synthetic.split("\n").exists { line =>
        val trimmed = line.trim
        !trimmed.startsWith("#") && trimmed.startsWith("vfadd.vv ")
      }
      assert(!mnemonicPresent) // synthetic must fail the gate criterion

    test("AC-16: cardinality budget = 9 (POC gate scope)"):
      assert(PocNames.size == 9)
