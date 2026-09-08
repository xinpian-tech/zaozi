// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

import utest.*

object JasperGoldTest extends TestSuite:
  val tests: Tests = Tests:
    test("one lowered UT selects independent goals and rejects hidden restrictions"):
      val dir = os.temp.dir(prefix = "single-ut-policy-")
      val sv = dir / "top.sv"
      val source = """module top(input clock, input reset, input valid);
                     |low: assert property (valid);
                     |high: assert property (~valid);
                     |endmodule
                     |""".stripMargin
      os.write(sv, source)
      val model = JgModel(sv, "top", Seq.empty, Set("low", "high"))
      JasperGold.requireUnconstrainedUT(model)
      val selected = JasperGold.selectGoal(model, "high", dir / "selected")
      assert(selected.generationLabels == Set("high"))
      assert(os.read(selected.sv).contains("high: assert property (~valid);"))
      assert(!os.read(selected.sv).contains("low: assert"))
      assert(os.read(sv) == source)
      intercept[IllegalArgumentException] {
        JasperGold.generate(model, dir / "must-not-run")
      }
      for restriction <- Seq("hidden: assume property (valid);", "hidden: restrict property (valid);") do
        os.write.over(sv, source + restriction)
        intercept[IllegalArgumentException] {
          JasperGold.requireUnconstrainedUT(model)
        }
      os.write.over(sv, source + "// assume is a comment, not an operation\n")
      JasperGold.requireUnconstrainedUT(model)
      os.write.over(sv, source + "extra: assert property (valid);\n")
      intercept[IllegalArgumentException] {
        JasperGold.requireUnconstrainedUT(model)
      }

    test("replay accepts only a successful full-design trace export"):
      val complete = "JGTRACE standard\nJGCOVERED top.goal\nJGDONE\n"
      assert(JasperGold.fullTraceExported(0, complete))
      assert(!JasperGold.fullTraceExported(1, complete))
      assert(!JasperGold.fullTraceExported(0, complete.replace("JGTRACE standard", "JGTRACE ar")))
      assert(!JasperGold.fullTraceExported(0, "puts \"JGTRACE standard\"\nJGDONE\n"))
      assert(!JasperGold.fullTraceExported(0, complete.replace("JGDONE", "")))
      assert(!JasperGold.fullTraceExported(0, complete + "WARNING (WVS028): target's COI only\n"))

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
