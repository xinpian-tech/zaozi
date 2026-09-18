// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

import utest.*

object JasperGoldTest extends TestSuite:
  val tests: Tests = Tests:
    test("only generated liveness cover errors permit source repair"):
      val source = "/run/goal/jg/ModelUT.sv"
      val error = s"ERROR (EOBS012): $source(64): Not supported: Liveness cover.\n"
      val cascade = "ERROR (ENL008): Elaborate did not conclude successfully\nERROR: problem encountered at line 6 in file run.tcl\n"
      assert(JasperGold.executionFailure(1, error + cascade, Some(source)).get.kind == "unsupported_liveness_cover")
      assert(JasperGold.executionFailure(1, error).get.kind == "jg_execution_failure")
      assert(JasperGold.executionFailure(1, error.replace(source, "/rtl/dut.sv"), Some(source)).get.kind == "jg_execution_failure")
      assert(JasperGold.executionFailure(1, error + "ERROR: license failure\n", Some(source)).get.kind == "jg_execution_failure")
      assert(JasperGold.executionFailure(1, error + "ERROR (EOBS002): Property compilation time limit exceeded\n", Some(source)).get.kind == "property_compile_timeout")
    test("property compilation failure is not an unknown solve"):
      val failed = JasperGold.executionFailure(1, "ERROR (EOBS002): Property compilation time limit exceeded after 5000ms")
      assert(failed.get.kind == "property_compile_timeout")
      assert(JasperGold.executionFailure(1, "ERROR: syntax error").get.kind == "jg_execution_failure")
      assert(JasperGold.executionFailure(0, "JGSTATUS top.goal undetermined\nJGDONE\n").isEmpty)
    test("one lowered UT selects independent goals and rejects hidden restrictions"):
      val dir = os.temp.dir(prefix = "single-ut-policy-")
      val sv = dir / "top.sv"
      val source = """module top(input clock, input reset, input valid);
                     |low: cover property (@(posedge clock) !valid);
                     |high: cover property (@(posedge clock) valid);
                     |endmodule
                     |""".stripMargin
      os.write(sv, source)
      val model = JgModel(sv, "top", Seq.empty, Set("low", "high"))
      JasperGold.requireUnconstrainedUT(model)
      val selected = JasperGold.selectGoal(model, "high", dir / "selected")
      assert(selected.generationLabels == Set("high"))
      assert(os.read(selected.sv).contains("high: cover property (@(posedge clock) valid);"))
      assert(!os.read(selected.sv).contains("low: cover"))
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

    test("native covers are validated without changing temporal expressions"):
      val sv = """flow: cover property (@(posedge clock) (a ##1 b));
                 |value: cover property (@(posedge clock) done);
                 |constant: cover property (@(posedge clock) 1'b0);
                 |ordinary: assert property (done);
                 |""".stripMargin
      JasperGold.validateCovers(sv, Set("flow", "value", "constant"))
      intercept[IllegalArgumentException] {
        JasperGold.validateCovers(sv, Set("missing"))
      }
      intercept[IllegalArgumentException] {
        JasperGold.validateCovers(sv, Set.empty)
      }
      intercept[IllegalArgumentException] {
        JasperGold.validateCovers(sv + sv, Set("flow"))
      }
      intercept[IllegalArgumentException] {
        JasperGold.validateCovers("old: assert property (not (done));", Set("old"))
      }

    test("generation model requires labels"):
      intercept[IllegalArgumentException] {
        JgModel(os.pwd / "unused.sv", "top", Seq.empty, Set.empty)
      }
