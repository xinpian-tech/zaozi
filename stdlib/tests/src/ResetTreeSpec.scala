// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.reset

import utest.*

object ResetTreeSpec extends TestSuite:
  val tests = Tests:
    test("processing shares named clocks and preserves reason bit order"):
      val sources = Seq(ResetSource("event", false), ResetSource("root", true), ResetSource("external", true))
      val tree    = ResetTreeParameter(
        sources,
        Seq(
          ResetTarget(
            "out",
            true,
            Seq(
              ResetLink("event", Some(ResetProcessing.Async("fast", 3))),
              ResetLink("external", Some(ResetProcessing.Pipeline("slow", 2)))
            ),
            Some(ResetProcessing.Counter("fast", 4))
          )
        ),
        Some(ResetTreeReason("slow", "root"))
      )
      assert(tree.clocks == Seq("fast", "slow"))
      assert(tree.reasonSources.map(_.name) == Seq("event", "external"))
      assert(tree.bindings("reasons")(1)("source").str == "external")
      assert(tree.bindings("reasons")(1)("bit").num == 1)

    test("resource names and references are unambiguous"):
      val root = ResetSource("root", true)
      intercept[IllegalArgumentException](ResetTreeParameter(Seq(root, root), Seq.empty))
      intercept[IllegalArgumentException](ResetTreeParameter(Seq(root), Seq(ResetTarget("root", true, Seq.empty))))
      intercept[IllegalArgumentException](ResetTreeParameter(Seq(ResetSource("", true)), Seq.empty))
      intercept[IllegalArgumentException](
        ResetTreeParameter(Seq(root), Seq(ResetTarget("out", true, Seq(ResetLink("missing")))))
      )
      intercept[IllegalArgumentException](
        ResetTreeParameter(Seq(root), Seq.empty, Some(ResetTreeReason("clock", "missing")))
      )

    test("stateful paths require named clocks"):
      intercept[IllegalArgumentException](
        ResetTreeParameter(
          Seq(ResetSource("root", true)),
          Seq(
            ResetTarget("out", false, Seq(ResetLink("root")), Some(ResetProcessing.Counter("", 2)))
          )
        )
      )
      intercept[IllegalArgumentException](
        ResetTreeParameter(Seq(ResetSource("root", true)), Seq.empty, Some(ResetTreeReason("", "root")))
      )

    test("reset pipeline rejects non-positive stages"):
      Seq(-1, 0).foreach: length =>
        intercept[IllegalArgumentException](ResetPipelineParameter(length))

    test("reset counter rejects non-positive cycles"):
      Seq(-1, 0).foreach: length =>
        intercept[IllegalArgumentException](ResetCounterParameter(length))

    test("asynchronous reset paths reject non-positive stages"):
      Seq(-1, 0).foreach: stages =>
        intercept[IllegalArgumentException](
          ResetTreeParameter(
            Seq(ResetSource("root", true)),
            Seq(ResetTarget("out", true, Seq(ResetLink("root", Some(ResetProcessing.Async("clock", stages))))))
          )
        )
