// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib

import me.jiuyang.stdlib.iomux.{*, given}
import utest.*

object IOMuxCellSpec extends TestSuite:
  private def cell(json: String): IOMuxCell = upickle.default.read[IOMuxCell](json)

  private def error(json: String): String =
    val thrown = intercept[Throwable](cell(json))
    Iterator.iterate(thrown)(_.getCause).takeWhile(_ != null).map(_.getMessage).mkString("\n")

  private val pullNone = """{"function":[{"PE":0,"pull":"none"},{"PE":1,"pull":"up"}]}"""

  val tests = Tests:
    test("pins pack in first-appearance order with the first pin as LSB"):
      val drive = cell(
        """{"name":"C","control":[{"name":"d","function":[{"B":1,"A":0,"d":"one"},{"A":1,"d":"two"}]}]}"""
      ).control.head
      assert(drive.pins == Seq("B", "A"))
      assert(drive.table.rows.map(_.value) == Seq(BigInt(1), BigInt(2)))

    test("x packs as 0"):
      val drive = cell("""{"name":"C","control":[{"name":"d","function":[{"A":"x","B":1,"d":"one"}]}]}""").control.head
      assert(drive.table.rows.map(_.value) == Seq(BigInt(2)))

    test("a control label written twice is rejected"):
      val message = error("""{"name":"C","control":[{"name":"d","function":[{"A":0,"d":"low"},{"A":1,"d":"low"}]}]}""")
      assert(message.contains("control d row 1 writes label low twice"))

    test("a pull strength written twice is rejected"):
      val message = error(
        """{"name":"C","pull":{"function":[{"PE":0,"pull":"none"},{"PE":1,"R":0,"pull":"up","strength":"47k"},""" +
          """{"PE":1,"R":1,"pull":"up","strength":"47k"}]}}"""
      )
      assert(message.contains("pull row 2 writes up strength 47k twice"))

    test("a pull without none is rejected"):
      assert(error("""{"name":"C","pull":{"function":[{"PE":1,"pull":"up"}]}}""").contains("pull needs a none row"))

    test("pins shared by pull and a control are rejected"):
      val message =
        error(s"""{"name":"C","pull":$pullNone,"control":[{"name":"d","function":[{"PE":0,"DS":1,"d":"low"}]}]}""")
      assert(message.contains("cell C pins PE are in pull and control d"))

    test("an unknown control default is rejected"):
      val message = error("""{"name":"C","control":[{"name":"d","function":[{"A":0,"d":"low"}],"default":"high"}]}""")
      assert(message.contains("control d default high names no row"))

    test("text in a pin column is rejected"):
      val message = error("""{"name":"C","pull":{"function":[{"PE":0,"pull":"none"},{"PE":"on","pull":"up"}]}}""")
      assert(message.contains("pull row 1 pin column PE holds text on"))
