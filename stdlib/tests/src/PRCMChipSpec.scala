// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import utest.*

object PRCMChipSpec extends TestSuite:
  private def domain(name: String, needs: Seq[String] = Seq.empty, runCode: BigInt = 2): PRCMManagedDomain =
    PRCMManagedDomain(
      name,
      Seq(
        PRCMMode("off", 0, PRCMTarget.Off),
        PRCMMode("reset", 1, PRCMTarget.Reset),
        PRCMMode("run", runCode, PRCMTarget.Run, needs)
      ),
      "off",
      2
    )

  private val domains = Seq(domain("a", Seq("x")), domain("p"))
  private val services = Seq(PRCMService("x", "p"))
  private val sleep = PRCMChipMode("sleep", 0, Map("a" -> PRCMPolicy.Fixed("off"), "p" -> PRCMPolicy.Fixed("off")))
  private val active = PRCMChipMode("active", 32, Map("a" -> PRCMPolicy.Fixed("run"), "p" -> PRCMPolicy.Local))
  private val chip = PRCMChip(Seq(sleep, active), "sleep")
  private def parameter(config: PRCMChip): PRCMParameter = PRCMParameter(3, 8, 2, domains, services, Some(config))

  val tests = Tests:
    test("chip registers precede domain banks and preserve exact status fit"):
      val p = parameter(chip)
      assert(p.wordCount == 8)
      assert(p.banks.map(_.byteOffset) == Seq(BigInt(2), BigInt(5)))
      assert(p.banks.head.statusFlags == Seq("done", "invalid", "fault", "blocked_by_chip", "wait_service"))
      assert(p.banks.last.statusFlags == Seq("done", "invalid", "fault", "blocked_by_chip", "in_use"))
      assert(p.chipBank.get.request.map(_.width) == Seq(6))
      assert(p.header.contains("#define PRCM_CHIP_STATUS_DONE_BIT 0x6ULL"))
      assert(p.header.contains("#define PRCM_CHIP_STATUS_INVALID_BIT 0x7ULL"))
      assert(p.header.contains("#define PRCM_DOMAIN_a_STATUS_WAIT_SERVICE_BIT 0x6ULL"))
      assert(p.header.contains("#define PRCM_CHIP_MODE_active 0x20ULL"))
      assert(upickle.default.read[PRCMParameter](upickle.default.write(p)) == p)

    test("legal witnesses promote Local providers required by fixed consumers"):
      val p     = parameter(chip)
      assert(
        p.chipWitnesses == Seq(
          "sleep"  -> Seq(PRCMTarget.Off, PRCMTarget.Off),
          "active" -> Seq(PRCMTarget.Run, PRCMTarget.Run)
        )
      )
      assert(p.bindings("chip")(1)("witness")("p").str == "Run")
      val local = active.copy(domains = Map("a" -> PRCMPolicy.Local, "p" -> PRCMPolicy.Fixed("run")))
      assert(
        parameter(chip.copy(modes = Seq(sleep, local))).chipWitnesses.last._2 == Seq(PRCMTarget.Off, PRCMTarget.Run)
      )

    test("chip policies preserve provider availability for fixed and Local consumers"):
      val stopped = active.copy(domains = Map("a" -> PRCMPolicy.Fixed("run"), "p" -> PRCMPolicy.Fixed("off")))
      intercept[IllegalArgumentException](parameter(chip.copy(modes = Seq(sleep, stopped))))
      val local   = stopped.copy(domains = stopped.domains.updated("a", PRCMPolicy.Local))
      intercept[IllegalArgumentException](parameter(chip.copy(modes = Seq(sleep, local))))
      val held    = stopped.copy(domains = stopped.domains.updated("a", PRCMPolicy.Fixed("reset")))
      assert(
        parameter(chip.copy(modes = Seq(sleep, held))).chipWitnesses.last._2 == Seq(PRCMTarget.Reset, PRCMTarget.Off)
      )

    test("every policy resolves every domain and named local mode"):
      intercept[IllegalArgumentException](
        parameter(chip.copy(modes = Seq(sleep.copy(domains = Map("a" -> PRCMPolicy.Local)))))
      )
      intercept[IllegalArgumentException](
        parameter(chip.copy(modes = Seq(sleep.copy(domains = sleep.domains.updated("extra", PRCMPolicy.Local)))))
      )
      intercept[IllegalArgumentException](
        parameter(chip.copy(modes = Seq(sleep.copy(domains = sleep.domains.updated("a", PRCMPolicy.Fixed("missing"))))))
      )
      intercept[IllegalArgumentException](chip.copy(resetMode = "missing"))
      intercept[IllegalArgumentException](chip.copy(modes = Seq.empty))
      intercept[IllegalArgumentException](chip.copy(modes = Seq(sleep, active.copy(code = 0))))
      intercept[IllegalArgumentException](chip.copy(modes = Seq(sleep, active.copy(name = "sleep"))))
      intercept[IllegalArgumentException](chip.copy(modes = Seq(sleep, active.copy(code = -1))))

    test("chip and domain flags both participate in native word capacity"):
      intercept[IllegalArgumentException](parameter(chip.copy(modes = Seq(sleep, active.copy(code = 64)))))
      assert(
        PRCMParameter(
          3,
          8,
          2,
          Seq(domain("a", Seq("x"), 4), domain("p", runCode = 4)),
          services,
          Some(chip)
        ).banks.head.statusFlags.size == 5
      )
      intercept[IllegalArgumentException](
        PRCMParameter(3, 8, 2, Seq(domain("a", Seq("x"), 8), domain("p")), services, Some(chip))
      )
      intercept[IllegalArgumentException](
        PRCMParameter(3, 8, 2, Seq(domain("a", Seq("x")), domain("p", runCode = 8)), services, Some(chip))
      )
      intercept[IllegalArgumentException](PRCMParameter(2, 8, 2, domains, services, Some(chip)))

    test("wide chip requests use byte fields and exact header words"):
      val code = (BigInt(1) << 80) | 0x1234
      val p    = PRCMParameter(3, 128, 2, domains, services, Some(chip.copy(modes = Seq(sleep, active.copy(code = code)))))
      assert(p.chipBank.get.request.map(_.width) == Seq.fill(10)(8) :+ 1)
      assert(p.header.contains("#define PRCM_CHIP_MODE_active_WORDS 3"))
      assert(p.header.contains("#define PRCM_CHIP_MODE_active_WORD_0 0x1234U"))
      assert(p.header.contains("#define PRCM_CHIP_MODE_active_WORD_2 0x10000U"))
