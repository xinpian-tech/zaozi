// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import utest.*

object PRCMServiceSpec extends TestSuite:
  private def domain(name: String, services: Seq[String] = Seq.empty, runCode: BigInt = 1): PRCMManagedDomain =
    PRCMManagedDomain(
      name,
      Seq(PRCMMode("off", 0, PRCMTarget.Off), PRCMMode("run", runCode, PRCMTarget.Run, services)),
      "off",
      2
    )

  val tests = Tests:
    test("service aliases share one handshake per consumer and provider"):
      val parameter = PRCMParameter(
        4,
        8,
        2,
        Seq(domain("provider"), domain("beta", Seq("x")), domain("alpha", Seq("y", "x"))),
        Seq(PRCMService("x", "provider"), PRCMService("y", "provider"))
      )
      assert(parameter.serviceEdges == Seq(PRCMServiceEdge(0, 2, Seq("x", "y")), PRCMServiceEdge(1, 2, Seq("x"))))
      assert(parameter.banks.map(_.event.width) == Seq(2, 2, 1))
      assert(parameter.banks.head.statusFlags.last == "wait_service")
      assert(parameter.banks.last.statusFlags.last == "in_use")
      val edges     = parameter.bindings("services").arr.toSeq
      assert(edges.head("consumer").str == "alpha")
      assert(edges.head("provider").str == "provider")
      assert(edges.head("names").arr.map(_.str).toSeq == Seq("x", "y"))
      assert(parameter.header.contains("#define PRCM_DOMAIN_alpha_EVENT_SERVICE_LOST_BIT 0x1ULL"))
      assert(parameter.header.contains("#define PRCM_DOMAIN_provider_STATUS_IN_USE_BIT 0x4ULL"))

    test("a consumer can require multiple providers"):
      val parameter = PRCMParameter(
        4,
        8,
        2,
        Seq(domain("consumer", Seq("x", "y")), domain("p"), domain("q")),
        Seq(PRCMService("x", "p"), PRCMService("y", "q"))
      )
      assert(parameter.serviceEdges == Seq(PRCMServiceEdge(0, 1, Seq("x")), PRCMServiceEdge(0, 2, Seq("y"))))

    test("providers cannot consume services"):
      intercept[IllegalArgumentException](
        PRCMParameter(
          4,
          8,
          2,
          Seq(domain("consumer", Seq("x")), domain("middle", Seq("y")), domain("root")),
          Seq(PRCMService("x", "middle"), PRCMService("y", "root"))
        )
      )
      intercept[IllegalArgumentException](
        PRCMParameter(2, 8, 2, Seq(domain("self", Seq("x"))), Seq(PRCMService("x", "self")))
      )

    test("service declarations preserve names and provider capability"):
      intercept[IllegalArgumentException](
        PRCMParameter(3, 8, 2, Seq(domain("c", Seq("missing")), domain("p")), Seq(PRCMService("x", "p")))
      )
      intercept[IllegalArgumentException](
        PRCMParameter(
          4,
          8,
          2,
          Seq(domain("c", Seq("x")), domain("p"), domain("q")),
          Seq(PRCMService("x", "p"), PRCMService("x", "q"))
        )
      )
      intercept[IllegalArgumentException](
        PRCMParameter(3, 8, 2, Seq(domain("c", Seq("x"))), Seq(PRCMService("x", "missing")))
      )
      val offOnly = PRCMManagedDomain("p", Seq(PRCMMode("off", 0, PRCMTarget.Off)), "off", 2)
      intercept[IllegalArgumentException](
        PRCMParameter(3, 8, 2, Seq(domain("c", Seq("x")), offOnly), Seq(PRCMService("x", "p")))
      )

    test("only Run modes use a fixed service set"):
      val consumer = domain("c", Seq("x"))
      intercept[IllegalArgumentException](consumer.copy(modes = consumer.modes :+ PRCMMode("other", 2, PRCMTarget.Run)))
      intercept[IllegalArgumentException](consumer.copy(modes = consumer.modes.map(_.copy(services = Seq("x")))))

    test("service status flags participate in word capacity"):
      intercept[IllegalArgumentException](
        PRCMParameter(3, 8, 2, Seq(domain("c", Seq("x"), 16), domain("p")), Seq(PRCMService("x", "p")))
      )
      intercept[IllegalArgumentException](
        PRCMParameter(3, 8, 2, Seq(domain("c", Seq("x")), domain("p", runCode = 16)), Seq(PRCMService("x", "p")))
      )
