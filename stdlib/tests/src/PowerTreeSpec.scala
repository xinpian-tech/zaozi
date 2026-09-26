// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.power

import utest.*

object PowerTreeSpec extends TestSuite:
  val tests = Tests:
    val switched = PowerControlParameter(true, 3, 2, 4)
    val root     = PowerTreeDomain("root", switched)
    test("forward dependencies and reset clocks retain declared indices"):
      val consumer = PowerTreeDomain(
        "consumer",
        switched,
        Seq(PowerDependency("root", PowerDependencyKind.Hard), PowerDependency("aon", PowerDependencyKind.Soft)),
        Seq(PowerFollow("consumerReset", "slow", 3), PowerFollow("oneReset", "fast", 1))
      )
      val aon      =
        PowerTreeDomain("aon", PowerControlParameter(false, 0, 1, 0), follows = Seq(PowerFollow("aonReset", "slow", 2)))
      val tree     = PowerTreeParameter(Seq(consumer, root, aon))
      assert(tree.domainIndices == Map("consumer" -> 0, "root" -> 1, "aon" -> 2))
      assert(tree.clocks == Seq("slow", "fast"))
      assert(tree.bindings("domains")(0)("kind").str == "switched")
      assert(tree.bindings("domains")(1)("kind").str == "root")
      assert(tree.bindings("domains")(2)("kind").str == "alwaysOn")
      assert(tree.bindings("domains")(0)("resets")(1)("name").str == "oneReset")
      assert(tree.bindings("domains")(0)("resets")(1)("port").num == 1)
    test("names and references do not hide resources"):
      intercept[IllegalArgumentException](PowerTreeParameter(Seq(root, root)))
      intercept[IllegalArgumentException](PowerTreeParameter(Seq(root.copy(name = ""))))
      intercept[IllegalArgumentException](
        PowerTreeParameter(Seq(root.copy(dependencies = Seq(PowerDependency("missing", PowerDependencyKind.Hard)))))
      )
      val follow = PowerFollow("reset", "clock", 2)
      intercept[IllegalArgumentException](PowerTreeParameter(Seq(root.copy(follows = Seq(follow, follow)))))
      intercept[IllegalArgumentException](PowerTreeParameter(Seq(root.copy(follows = Seq(follow.copy(name = ""))))))
      intercept[IllegalArgumentException](PowerTreeParameter(Seq(root.copy(follows = Seq(follow.copy(clock = ""))))))
    test("follow resets reject non-positive stages"):
      Seq(-1, 0).foreach: stages =>
        intercept[IllegalArgumentException](PowerFollow("reset", "clock", stages))
    test("dependency cycles remain timed sequential connections"):
      val first  = root.copy(dependencies = Seq(PowerDependency("other", PowerDependencyKind.Hard)))
      val second = PowerTreeDomain("other", switched, Seq(PowerDependency("root", PowerDependencyKind.Soft)))
      val tree   = PowerTreeParameter(Seq(first, second))
      assert(tree.bindings("domains")(0)("dependencies")(0)("source").str == "other")
      assert(tree.bindings("domains")(1)("dependencies")(0)("source").str == "root")
