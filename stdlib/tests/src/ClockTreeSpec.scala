// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import utest.*

object ClockTreeSpec extends TestSuite:
  val tests = Tests:
    test("guides require their clock processing point"):
      val guide = Some(ClockGuideParameter("Guide", "I", "Z"))
      intercept[IllegalArgumentException](ClockPath(gateGuide = guide))
      intercept[IllegalArgumentException](ClockPath(dividerGuide = guide))
      intercept[IllegalArgumentException](ClockPath(inverterGuide = guide))
      intercept[IllegalArgumentException](ClockTarget("out", Seq(ClockLink("root")), muxGuide = guide))
      intercept[IllegalArgumentException](ClockGuideParameter("Guide", "I", "I"))

    test("guide instance names cannot alias another clock cell"):
      val guide     = ClockGuideParameter("Guide", "I", "Z", Some("target_0_gate"))
      val path      = ClockPath(gate = Some(ClockGateParameter(true, false)), gateGuide = Some(guide))
      intercept[IllegalArgumentException](
        ClockTreeParameter(Seq("root"), Seq(ClockTarget("out", Seq(ClockLink("root")), path = path)))
      )
      val duplicate = ClockGuideParameter("Guide", "I", "Z", Some("shared"))
      val target    =
        ClockTarget("out", Seq(ClockLink("root")), path = ClockPath(invert = true, inverterGuide = Some(duplicate)))
      intercept[IllegalArgumentException](ClockTreeParameter(Seq("root"), Seq(target, target.copy(name = "other"))))

    test("derived sources are ordered without changing output ports"):
      val divided   = ClockTarget("divided", Seq(ClockLink("root")))
      val selected  = ClockTarget("selected", Seq(ClockLink("divided"), ClockLink("root")), Some(ClockSelection.Raw))
      val forwarded = ClockTarget("forwarded", Seq(ClockLink("selected")))
      val tree      = ClockTreeParameter(Seq("root"), Seq(forwarded, selected, divided))
      assert(tree.orderedTargets.map(_.name) == Seq("divided", "selected", "forwarded"))
      assert(tree.bindings("targets")(0)("name").str == "forwarded")
      assert(tree.targetIndices("divided") == 2)

    test("resource names cannot hide another declaration"):
      intercept[IllegalArgumentException](ClockTreeParameter(Seq("root", "root"), Seq.empty))
      intercept[IllegalArgumentException](
        ClockTreeParameter(Seq("root"), Seq(ClockTarget("root", Seq(ClockLink("root")))))
      )
      val target = ClockTarget("out", Seq(ClockLink("root")))
      intercept[IllegalArgumentException](ClockTreeParameter(Seq("root"), Seq(target, target)))
      intercept[IllegalArgumentException](ClockTreeParameter(Seq(""), Seq.empty))

    test("missing sources and dependency cycles are rejected"):
      intercept[IllegalArgumentException](
        ClockTreeParameter(Seq("root"), Seq(ClockTarget("out", Seq(ClockLink("missing")))))
      )
      intercept[IllegalArgumentException](
        ClockTreeParameter(Seq.empty, Seq(ClockTarget("loop", Seq(ClockLink("loop")))))
      )
      intercept[IllegalArgumentException](
        ClockTreeParameter(
          Seq.empty,
          Seq(
            ClockTarget("a", Seq(ClockLink("b"))),
            ClockTarget("b", Seq(ClockLink("a")))
          )
        )
      )

    test("selection follows the number of links"):
      intercept[IllegalArgumentException](ClockTarget("out", Seq.empty))
      intercept[IllegalArgumentException](ClockTarget("out", Seq(ClockLink("root")), Some(ClockSelection.Raw)))
      intercept[IllegalArgumentException](ClockTarget("out", Seq(ClockLink("a"), ClockLink("b"))))
      val repeated = ClockTarget(
        "out",
        Seq(ClockLink("root"), ClockLink("root", ClockPath(invert = true))),
        Some(ClockSelection.Raw)
      )
      assert(ClockTreeParameter(Seq("root"), Seq(repeated)).orderedTargets == Seq(repeated))
