// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.stdlib.adder.{AdderIO, AdderLayers, AdderParameter, AdderProbe}
import me.jiuyang.testlib.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import utest.*

case class WidthOnlyAdderParameter(width: Int) extends AdderParameter
given upickle.default.ReadWriter[WidthOnlyAdderParameter] = upickle.default.macroRW

object AdderSpec extends TestSuite:
  val tests = Tests:
    test("Adder interfaces accept a width-only parameter"):
      @generator
      object WidthOnlyAdder
          extends Generator[
            WidthOnlyAdderParameter,
            AdderLayers[WidthOnlyAdderParameter],
            AdderIO[WidthOnlyAdderParameter],
            AdderProbe[WidthOnlyAdderParameter]
          ]
          with HasFirrtlTest:
        def architecture(parameter: WidthOnlyAdderParameter) =
          val io = summon[Interface[AdderIO[WidthOnlyAdderParameter]]]
          io.sum := io.a
          io.co  := io.ci

      for width <- Seq(1, 8) do
        WidthOnlyAdder.firrtlTest(WidthOnlyAdderParameter(width))(
          s"input a : UInt<$width>",
          s"input b : UInt<$width>",
          "input ci : UInt<1>",
          "output co : UInt<1>",
          s"output sum : UInt<$width>",
          "connect sum, a",
          "connect co, ci"
        )
