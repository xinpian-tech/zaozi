// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.stdlib.adder.*
import me.jiuyang.stdlib.adder.default.{BrentKungAdder, PrefixAdderParameter, RippleAdderParameter, given}
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
    test("Ripple parameters validate width and serialize"):
      intercept[IllegalArgumentException](RippleAdderParameter(0))
      intercept[IllegalArgumentException](RippleAdderParameter(-1))
      val parameter = RippleAdderParameter(8)
      assert(upickle.default.read[RippleAdderParameter](upickle.default.write(parameter)) == parameter)

    test("Default Adder accepts a custom parameter and instantiates RippleAdder"):
      @generator
      object DefaultAdder
          extends Generator[
            WidthOnlyAdderParameter,
            AdderLayers[WidthOnlyAdderParameter],
            AdderIO[WidthOnlyAdderParameter],
            AdderProbe[WidthOnlyAdderParameter]
          ]
          with HasFirrtlTest:
        def architecture(parameter: WidthOnlyAdderParameter) =
          val io = summon[Interface[AdderIO[WidthOnlyAdderParameter]]]
          val adder: Wire[AdderIO[WidthOnlyAdderParameter]] = Adder(parameter)
          adder.a  := io.a
          adder.b  := io.b
          adder.ci := io.ci
          io.sum   := adder.sum
          io.co    := adder.co

      for width <- Seq(1, 5, 32) do
        DefaultAdder.firrtlTest(WidthOnlyAdderParameter(width))(out =>
          out.contains(s"of RippleAdder_width$width") && !out.contains("BrentKungAdder")
        )

    test("BKA remains explicitly instantiable"):
      @generator
      object ExplicitBKA
          extends Generator[
            PrefixAdderParameter,
            AdderLayers[PrefixAdderParameter],
            AdderIO[PrefixAdderParameter],
            AdderProbe[PrefixAdderParameter]
          ]
          with HasFirrtlTest:
        def architecture(parameter: PrefixAdderParameter) =
          val io    = summon[Interface[AdderIO[PrefixAdderParameter]]]
          val adder = BrentKungAdder.instantiate(parameter).io
          adder.a  := io.a
          adder.b  := io.b
          adder.ci := io.ci
          io.sum   := adder.sum
          io.co    := adder.co

      ExplicitBKA.firrtlTest(PrefixAdderParameter(8, 2))(out =>
        out.contains("of BrentKungAdder_width8_radix2") && !out.contains("RippleAdder")
      )

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
