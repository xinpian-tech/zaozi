// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

import utest.*

object JasperGoldTest extends TestSuite:
  val tests: Tests = Tests:
    test("generation assertions become covers for temporal and combinational negation"):
      val temporal      =
        """flow: // firtool may put source metadata on the label line
          |  assert property (not ((a) ##1 (b)));
          |""".stripMargin
      val combinational =
        """value: assert property (~(op == 4'h0 & start));
          |short:
          |  assert property (~(a == 1'b1));
          |""".stripMargin
      val ordinary      =
        """check:
          |  assert property (done);
          |""".stripMargin

      val converted = JasperGold.asCover(temporal + combinational + ordinary, Set("flow", "value", "short"))
      assert(converted.contains("cover property (not (not ((a) ##1 (b))));"))
      assert(converted.contains("value: cover property (not (~(op == 4'h0 & start)));"))
      assert(converted.contains("cover property (not (~(a == 1'b1)));"))
      assert(converted.contains(ordinary))

    test("explicit generation labels survive firtool boolean simplification"):
      val sv = """completion: assert property (~_dut_done);
                 |negated: assert property (done);
                 |constant: assert property (1'h0);
                 |ordinary: assert property (done);
                 |rst_low: assume property (~reset);
                 |""".stripMargin
      val converted = JasperGold.asCover(sv, Set("completion", "negated", "constant"))
      assert(converted.contains("completion: cover property (not (~_dut_done));"))
      assert(converted.contains("negated: cover property (not (done));"))
      assert(converted.contains("constant: cover property (not (1'h0));"))
      assert(converted.contains("ordinary: assert property (done);"))
      assert(converted.contains("rst_low: assume property (~reset);"))

    test("a missing generation label fails instead of returning a misleading cover result"):
      intercept[IllegalArgumentException] {
        JasperGold.asCover("ordinary: assert property (done);", Set("missing"))
      }

    test("unmarked or ambiguous generation assertions are rejected"):
      intercept[IllegalArgumentException] {
        JasperGold.asCover("old: assert property (not (done));", Set.empty)
      }
      intercept[IllegalArgumentException] {
        JasperGold.asCover("goal: assert property (~done);\ngoal: assert property (~done);", Set("goal"))
      }

    test("generation model requires labels"):
      intercept[IllegalArgumentException] {
        JgModel(os.pwd / "unused.sv", "top", Seq.empty, Set.empty)
      }
