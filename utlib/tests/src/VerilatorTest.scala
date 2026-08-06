// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*
import utest.*

object VerilatorTest extends TestSuite:
  /** A record in the exact shape Verilator 5.048 writes: fields joined by \u0001, key and value split by \u0002. */
  private def record(file: String, line: Int, label: String, hier: String, count: Int): String =
    val fields = Seq(
      s"f\u0002$file",
      s"l\u0002$line",
      "n\u000219",
      "t\u0002user",
      "page\u0002v_user/Harness",
      s"o\u0002$label",
      s"h\u0002$hier"
    ).mkString("\u0001")
    s"C '$fields' $count"

  val tests: Tests = Tests:
    test("parseCoverage reads labels from the o field, not the column"):
      val dat    = Seq(
        "# SystemC::Coverage-3",
        record("harness.sv", 134, "cover_zero", "top.harness.cover_zero", 4),
        record("harness.sv", 137, "cover_positive", "top.harness.cover_positive", 6),
        record("harness.sv", 136, "cover_negative", "top.harness.cover_negative", 0)
      ).mkString("\n")
      val report = Verilator.parseCoverage(dat)
      assert(report.hits("cover_zero") == 4)
      assert(report.hits("cover_positive") == 6)
      assert(report.hits("cover_negative") == 0)
      assert(report.hit("cover_zero"))
      assert(!report.hit("cover_negative"))

    test("parseCoverage tolerates comments and blank lines"):
      assert(Verilator.parseCoverage("# header\n\n# another\n").hits.isEmpty)

    test("parseCoverage sums records that share a label"):
      val dat = Seq(
        record("harness.sv", 10, "cover_x", "top.a.cover_x", 2),
        record("harness.sv", 10, "cover_x", "top.b.cover_x", 3)
      ).mkString("\n")
      assert(Verilator.parseCoverage(dat).hits("cover_x") == 5)
