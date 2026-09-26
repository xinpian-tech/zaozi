// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import utest.*

object PRCMParameterSpec extends TestSuite:
  private def domain(name: String, runCode: BigInt): PRCMManagedDomain =
    PRCMManagedDomain(name, Seq(PRCMMode("off", 0, PRCMTarget.Off), PRCMMode("run", runCode, PRCMTarget.Run)), "off", 2)

  val tests = Tests:
    test("sparse modes use byte fields and native word indices"):
      val parameter = PRCMParameter(3, 16, 2, Seq(domain("z", 769), domain("a", 769)))
      assert(parameter.orderedDomains.map(_.name) == Seq("a", "z"))
      assert(parameter.regMap.registers.map(_.byteOffset) == Seq[BigInt](0, 2, 4, 6, 8, 10))
      assert(parameter.windowBytes == 12)
      assert(parameter.banks.head.request.map(_.width) == Seq(8, 2))

    test("status exactly fills an eight-bit word"):
      val parameter = PRCMParameter(2, 8, 2, Seq(domain("core", 16)))
      assert(parameter.banks.head.domain.modeWidth == 5)
      assert(parameter.banks.head.status.width == 8)

    test("native mode codes can exceed sixty-four bits"):
      val parameter = PRCMParameter(2, 128, 2, Seq(domain("core", BigInt(1) << 80)))
      assert(parameter.banks.head.request.map(_.width) == Seq.fill(10)(8) :+ 1)
      assert(parameter.windowBytes == 48)

    test("status flags must fit alongside the request"):
      intercept[IllegalArgumentException](PRCMParameter(2, 8, 2, Seq(domain("core", 32))))

    test("mode definitions preserve identity and recovery"):
      intercept[IllegalArgumentException](domain("core", 0))
      intercept[IllegalArgumentException](domain("core", -1))
      intercept[IllegalArgumentException](PRCMManagedDomain("core", Seq(PRCMMode("run", 1, PRCMTarget.Run)), "run", 2))
      intercept[IllegalArgumentException](
        PRCMManagedDomain(
          "core",
          Seq(
            PRCMMode("same", 0, PRCMTarget.Off),
            PRCMMode("same", 1, PRCMTarget.Run)
          ),
          "same",
          2
        )
      )
      intercept[IllegalArgumentException](domain("core", 1).copy(resetMode = "missing"))

    test("domain names cannot hide another declaration"):
      intercept[IllegalArgumentException](PRCMParameter(3, 32, 2, Seq(domain("core", 1), domain("core", 2))))

    test("register capacity is checked in words"):
      val domains   = Seq(domain("a", 1), domain("b", 1), domain("c", 1))
      val parameter = PRCMParameter(3, 64, 2, domains.take(2))
      assert(parameter.wordCount == 6)
      intercept[IllegalArgumentException](PRCMParameter(3, 64, 2, domains))
      intercept[IllegalArgumentException](PRCMParameter(3, 64, 2, Seq.empty))

    test("wide software mode constants preserve every word"):
      val code          = (BigInt(1) << 80) | BigInt("0102030405060708", 16)
      val parameter     = PRCMParameter(2, 128, 2, Seq(domain("core", code)))
      val definitions   = parameter.header.linesIterator.map: line =>
        val parts = line.split(" ")
        parts(1) -> parts(2)
      val values        = definitions.toMap
      val name          = "PRCM_DOMAIN_core_MODE_run"
      assert(values(s"${name}_WORDS") == "3")
      val reconstructed = (0 until 3).map: word =>
        BigInt(values(s"${name}_WORD_$word").stripPrefix("0x").stripSuffix("U"), 16) << (32 * word)
      assert(reconstructed.reduce(_ | _) == code)

    test("software names and port bindings preserve domain identity"):
      val parameter = PRCMParameter(4, 16, 2, Seq(domain("a-b", 1), domain("a_002db", 1), domain("a\u02db", 1)))
      val names     = parameter.header.linesIterator.map(_.split(" ")(1)).toSeq
      assert(names.distinct.size == names.size)
      assert(names.forall(_.matches("[A-Za-z_][A-Za-z_0-9]*")))
      val bindings  = parameter.bindings("domains").arr.toSeq
      assert(bindings.map(_("name").str) == Seq("a-b", "a_002db", "a\u02db"))
      assert(bindings.map(_("port").num.toInt) == Seq(0, 1, 2))
