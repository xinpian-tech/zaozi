// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.testlib.*

import org.llvm.mlir.scalalib.capi.ir.{given_ContextApi, Block, Context, ContextApi}
import org.llvm.mlir.scalalib.capi.pass.{given_PassManagerApi, PassManager, PassManagerApi}
import utest.{eventually as _, *}

import java.lang.foreign.Arena
import scala.annotation.meta.param

case class SVASpecParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[SVASpecParameter] = upickle.default.macroRW

class SVASpecLayers(parameter: SVASpecParameter) extends LayerInterface(parameter):
  def layers = Seq(
    Layer(
      "Assertion"
    )
  )

class SVASpecIO(parameter: SVASpecParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val ib0   = Flipped(Bool())
  val ib1   = Flipped(Bool())

class SVASpecProbe(parameter: SVASpecParameter) extends DVBundle[SVASpecParameter, SVASpecLayers](parameter)

case class MultiClockParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[MultiClockParameter] = upickle.default.macroRW

class MultiClockLayers(parameter: MultiClockParameter) extends LayerInterface(parameter):
  def layers = Seq(
    Layer(
      "Assertion"
    )
  )

class MultiClockIO(parameter: MultiClockParameter) extends HWBundle(parameter):
  val clock0 = Flipped(Clock())
  val clock1 = Flipped(Clock())
  val ib0    = Flipped(Bool())
  val ib1    = Flipped(Bool())

class MultiClockProbe(parameter: MultiClockParameter) extends DVBundle[MultiClockParameter, MultiClockLayers](parameter)

object SVASpec extends TestSuite:
  val tests = Tests:
    test("Simple SVA"):
      @generator
      object SimpleSVA extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe] with HasVerilogTest:
        def architecture(parameter: SVASpecParameter) =
          val io    = summon[Interface[SVASpecIO]]
          val probe = summon[Interface[SVASpecProbe]]
          val a:         Referable[Bool] & HasOperation = io.ib0
          val immediate: Immediate                      = a.I

          Assert(immediate, "Simple SVA")

      val moduleName = SimpleSVA.moduleName(SVASpecParameter(32))
      SimpleSVA.verilogTest(SVASpecParameter(32))(
        s"Simple_SVA: assert property (ib0);"
      )

    test("api"):
      test("assert"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io = summon[Interface[SVASpecIO]]
            val a:         Referable[Bool] & HasOperation = io.ib0
            val immediate: Immediate                      = a.I
            val sequence:  Sequence                       = posedge(io.clock)(a.S)
            val property:  Property                       = a.I implies a.I

            Assert(immediate, "assert_0")
            Assert(sequence, "assert_1")
            Assert(property, "assert_2")

        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"assert_0: assert property (ib0);",
          s"assert_1: assert property (@(posedge clock) ib0);",
          s"assert_2: assert property (not ib0 or ib0);"
        )

      test("assume"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:         Referable[Bool] & HasOperation = io.ib0
            val immediate: Immediate                      = a.I
            val sequence:  Sequence                       = posedge(io.clock)(a.S)
            val property:  Property                       = a.I implies a.I

            Assume(immediate, "assume_0")
            Assume(sequence, "assume_1")
            Assume(property, "assume_2")
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"assume_0: assume property (ib0);",
          s"assume_1: assume property (@(posedge clock) ib0);",
          s"assume_2: assume property (not ib0 or ib0);"
        )

      test("cover"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:         Referable[Bool] & HasOperation = io.ib0
            val immediate: Immediate                      = a.I
            val sequence:  Sequence                       = posedge(io.clock)(a.S)
            val property:  Property                       = a.I implies a.I

            Cover(immediate, "cover_0")
            Cover(sequence, "cover_1")
            Cover(property, "cover_2")
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"cover_0: cover property (ib0);",
          s"cover_1: cover property (@(posedge clock) ib0);",
          s"cover_2: cover property (not ib0 or ib0);"
        )

    test("Sequence"):
      test("##"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasMlirTest
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:      Referable[Bool] & HasOperation = io.ib0
            val b:      Referable[Bool] & HasOperation = io.ib1
            val concat: Sequence                       = a.S ## b.S

            Assert(concat)
        SimpleSVA.mlirTest(SVASpecParameter(32))(out =>
          out.contains("ltl.concat") && !out.contains("ltl.clocked_delay")
        )
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          "(@(posedge clock) ib0) ##0 (@(posedge clock) ib1)"
        )

      test("###"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:       Referable[Bool] & HasOperation = io.ib0
            val b:       Referable[Bool] & HasOperation = io.ib1
            val delayed: Sequence                       = a.S ### b.S

            Assert(delayed)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          "(@(posedge clock) ib0) ##0",
          "(@(posedge clock) ##1 (@(posedge clock) ib1))"
        )

      test("##n"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasMlirTest
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io = summon[Interface[SVASpecIO]]
            val a:       Referable[Bool] & HasOperation = io.ib0
            val b:       Referable[Bool] & HasOperation = io.ib1
            val delayed: Sequence                       = posedge(io.clock)(a.S.##(5)(b.S))

            Assert(delayed)
        SimpleSVA.mlirTest(SVASpecParameter(32))(
          "builtin.unrealized_conversion_cast",
          "ltl.clock",
          "ltl.clocked_delay",
          "ltl.concat",
          "verif.assert"
        )
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          "(@(posedge clock) ib0) ##0",
          "(@(posedge clock) ##5 (@(posedge clock) ib1))"
        )

      test("##[n:m]"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:       Referable[Bool] & HasOperation = io.ib0
            val b:       Referable[Bool] & HasOperation = io.ib1
            val as:      Sequence                       = a.S
            val bs:      Sequence                       = b.S
            val delayed: Sequence                       = as.##(1, Some(2))(bs)

            Assert(delayed)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          "(@(posedge clock) ib0) ##0",
          "(@(posedge clock) ##[1:2] (@(posedge clock) ib1))"
        )

      test("reject invalid ## ranges"):
        @generator
        object InvalidDelay
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasCompileErrorTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val as: Sequence = io.ib0.S
            val bs: Sequence = io.ib1.S

            val negativeDelay = intercept[IllegalArgumentException]:
              as.##(-1)(bs)
            assert(negativeDelay.getMessage.contains("delay (-1)"))

            val negativeMin = intercept[IllegalArgumentException]:
              as.##(-1, Some(2))(bs)
            assert(negativeMin.getMessage.contains("min (-1)"))

            val invertedRange = intercept[IllegalArgumentException]:
              as.##(2, Some(1))(bs)
            assert(invertedRange.getMessage.contains("max (1)"))
        InvalidDelay.compileErrorTest(SVASpecParameter(32))

      test("##[+]"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:        Referable[Bool] & HasOperation = io.ib0
            val b:        Referable[Bool] & HasOperation = io.ib1
            val as:       Sequence                       = a.S
            val bs:       Sequence                       = b.S
            val delayed0: Sequence                       = as.##(1, None)(bs)
            val delayed1: Sequence                       = as ##+ bs

            Assert(delayed0, "assert0")
            Assert(delayed1, "assert1")
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          "assert0:",
          "assert1:",
          "(@(posedge clock) ib0) ##0",
          "(@(posedge clock) ##[+] (@(posedge clock) ib1))"
        )

      test("##[*]"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:        Referable[Bool] & HasOperation = io.ib0
            val b:        Referable[Bool] & HasOperation = io.ib1
            val as:       Sequence                       = a.S
            val bs:       Sequence                       = b.S
            val delayed0: Sequence                       = as.##(0, None)(bs)
            val delayed1: Sequence                       = as ##* bs

            Assert(delayed0, "assert0")
            Assert(delayed1, "assert1")
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          "assert0:",
          "assert1:",
          "(@(posedge clock) ib0) ##0",
          "(@(posedge clock) ##[*] (@(posedge clock) ib1))"
        )

      test("[*n]"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:       Referable[Bool] & HasOperation = io.ib0
            val seq:     Sequence                       = a.S
            val delayed: Sequence                       = seq * 1

            Assert(delayed)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"(@(posedge clock) ib0)"
        )

      test("[*n:m]"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:   Referable[Bool] & HasOperation = io.ib0
            val seq: Sequence                       = a.S
            val rep: Sequence                       = seq.*(1, Some(2))

            Assert(rep)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"(@(posedge clock) ib0)[*1:2]"
        )

      test("[*n:$]"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:    Referable[Bool] & HasOperation = io.ib0
            val seq:  Sequence                       = a.S
            val rep0: Sequence                       = seq.*(0, None) // [*0:$] -> [*]
            val rep1: Sequence                       = seq.*(1, None) // [*1:$] -> [+]
            val rep2: Sequence                       = seq.*(2, None) // [*2:$] -> [*2:$]

            Assert(rep0, "assert0")
            Assert(rep1, "assert1")
            Assert(rep2, "assert2")
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"assert0: assert property ((@(posedge clock) ib0)[*]);",
          s"assert1: assert property ((@(posedge clock) ib0)[+]);",
          s"assert2: assert property ((@(posedge clock) ib0)[*2:$$]);"
        )

      test("[->n:m]"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:        Referable[Bool] & HasOperation = io.ib0
            val seq:      Sequence                       = a.S
            val sequence: Sequence                       = seq.*->(1, 2)

            Assert(sequence)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"(@(posedge clock) ib0)[->1:2]"
        )

      test("[=n:m]"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:        Referable[Bool] & HasOperation = io.ib0
            val seq:      Sequence                       = a.S
            val sequence: Sequence                       = seq.*=(1, 2)

            Assert(sequence)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"(@(posedge clock) ib0)[=1:2]"
        )

      test("and"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:        Referable[Bool] & HasOperation = io.ib0
            val b:        Referable[Bool] & HasOperation = io.ib1
            val as:       Sequence                       = a.S
            val bs:       Sequence                       = b.S
            val sequence: Sequence                       = as & bs

            Assert(sequence)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"(@(posedge clock) ib0) and (@(posedge clock) ib1)"
        )

      test("intersect"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:        Referable[Bool] & HasOperation = io.ib0
            val b:        Referable[Bool] & HasOperation = io.ib1
            val as:       Sequence                       = a.S * 3
            val bs:       Sequence                       = b.S * 2
            val sequence: Sequence                       = as intersect bs

            Assert(sequence)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"(@(posedge clock) ib0)[*3] intersect (@(posedge clock) ib1)[*2]"
        )

      test("or"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:        Referable[Bool] & HasOperation = io.ib0
            val b:        Referable[Bool] & HasOperation = io.ib1
            val as:       Sequence                       = a.S
            val bs:       Sequence                       = b.S
            val sequence: Sequence                       = as | bs

            Assert(sequence)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"(@(posedge clock) ib0) or (@(posedge clock) ib1)"
        )

      test("not"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:        Referable[Bool] & HasOperation = io.ib0
            val property: Property                       = !a.S

            Assert(property)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"not (@(posedge clock) ib0)"
        )

      test("not immediate"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io = summon[Interface[SVASpecIO]]
            val a:    Referable[Bool] & HasOperation = io.ib0
            val prop: Property                       = !a.I

            Assert(prop)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"not ib0"
        )

      test("throughout"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:        Referable[Bool] & HasOperation = io.ib0
            val b:        Referable[Bool] & HasOperation = io.ib1
            val bs:       Sequence                       = b.S
            val sequence: Sequence                       = a throughout bs

            Assert(sequence)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"(ib0[*] intersect (@(posedge clock) ib1))"
        )

      test("within"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:        Referable[Bool] & HasOperation = io.ib0
            val b:        Referable[Bool] & HasOperation = io.ib1
            val as:       Sequence                       = a.S
            val bs:       Sequence                       = b.S
            val sequence: Sequence                       = as within bs

            Assert(sequence)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          "(@(posedge clock) 1'h1) ##0",
          "(@(posedge clock) ##[*] (@(posedge clock) ib0))",
          "(@(posedge clock) ##[*] (@(posedge clock) 1'h1))",
          "intersect (@(posedge clock) ib1)"
        )

    test("Property"):
      test("immediate implies"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io = summon[Interface[SVASpecIO]]
            val a:    Referable[Bool] & HasOperation = io.ib0
            val b:    Referable[Bool] & HasOperation = io.ib1
            val prop: Property                       = a.I implies b.I

            Assert(prop)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"(not ib0 or ib1)"
        )

      test("|->"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:    Referable[Bool] & HasOperation = io.ib0
            val b:    Referable[Bool] & HasOperation = io.ib1
            val as:   Sequence                       = a.S
            val bs:   Sequence                       = b.S
            val prop: Property                       = as |-> bs

            Assert(prop)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"(@(posedge clock) ib0) |-> (@(posedge clock) ib1)"
        )
      test("|=>"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:    Referable[Bool] & HasOperation = io.ib0
            val b:    Referable[Bool] & HasOperation = io.ib1
            val as:   Sequence                       = a.S
            val bs:   Sequence                       = b.S
            val prop: Property                       = as |=> bs

            Assert(prop)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          "(@(posedge clock) ib0) ##0",
          "(@(posedge clock) ##1 (@(posedge clock) 1'h1))",
          "|-> (@(posedge clock) ib1)"
        )
      test("implies"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:    Referable[Bool] & HasOperation = io.ib0
            val b:    Referable[Bool] & HasOperation = io.ib1
            val as:   Sequence                       = a.S
            val bs:   Sequence                       = b.S
            val prop: Property                       = as implies bs

            Assert(prop)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"(not (@(posedge clock) ib0) or (@(posedge clock) ib1))"
        )
      test("property and"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:    Referable[Bool] & HasOperation = io.ib0
            val b:    Referable[Bool] & HasOperation = io.ib1
            val as:   Property                       = !a.S
            val bs:   Sequence                       = b.S
            val prop: Property                       = as & bs

            Assert(prop)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"not (@(posedge clock) ib0) and (@(posedge clock) ib1)"
        )
      test("property or"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:    Referable[Bool] & HasOperation = io.ib0
            val b:    Referable[Bool] & HasOperation = io.ib1
            val as:   Property                       = !a.S
            val bs:   Sequence                       = b.S
            val prop: Property                       = as | bs

            Assert(prop)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"not (@(posedge clock) ib0) or (@(posedge clock) ib1)"
        )
      test("property intersect"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:    Referable[Bool] & HasOperation = io.ib0
            val b:    Referable[Bool] & HasOperation = io.ib1
            val as:   Property                       = !a.S
            val bs:   Sequence                       = b.S
            val prop: Property                       = as intersect bs

            Assert(prop)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"(not (@(posedge clock) ib0)) intersect (@(posedge clock) ib1)"
        )
      test("property overload result"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:              Referable[Bool] & HasOperation = io.ib0
            val b:              Referable[Bool] & HasOperation = io.ib1
            val lhs:            Sequence                       = a.S
            val rhs:            Property                       = !b.S
            val erasedLhs:      Property                       = lhs & rhs
            val propertyToProp: Property                       = (!a.S) & rhs

            Assert(erasedLhs, "assert0")
            Assert(propertyToProp, "assert1")
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"assert0: assert property ((@(posedge clock) ib0) and not (@(posedge clock) ib1));",
          s"assert1: assert property (not (@(posedge clock) ib0) and not (@(posedge clock) ib1));"
        )
      test("immediate sequence-like implication"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io = summon[Interface[SVASpecIO]]
            val a:    Referable[Bool] & HasOperation = io.ib0
            val b:    Referable[Bool] & HasOperation = io.ib1
            val prop: Property                       = a.I |-> b.I

            Assert(prop)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"ib0 |-> ib1"
        )
      test("sequence assert keeps clocked operand as property"):
        @generator
        object SimpleSVA extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe] with HasMlirTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val prop: Sequence = io.ib0.S

            Assert(prop)
        SimpleSVA.mlirTest(SVASpecParameter(32))(out => out.contains("ltl.clock") && !out.contains("ltl.repeat"))

      test("iff"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:    Referable[Bool] & HasOperation = io.ib0
            val b:    Referable[Bool] & HasOperation = io.ib1
            val as:   Sequence                       = a.S
            val bs:   Sequence                       = b.S
            val prop: Property                       = as iff bs

            Assert(prop)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          "(not ((@(posedge clock) ib0) or (@(posedge clock) ib1))",
          "or (@(posedge clock) ib0) and (@(posedge clock) ib1))"
        )
      test("#-#"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:    Referable[Bool] & HasOperation = io.ib0
            val b:    Referable[Bool] & HasOperation = io.ib1
            val as:   Sequence                       = a.S
            val bs:   Sequence                       = b.S
            val prop: Property                       = as #-# bs

            Assert(prop)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"(not ((@(posedge clock) ib0) |-> not (@(posedge clock) ib1)))"
        )
      test("#=#"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:    Referable[Bool] & HasOperation = io.ib0
            val b:    Referable[Bool] & HasOperation = io.ib1
            val as:   Sequence                       = a.S
            val bs:   Sequence                       = b.S
            val prop: Property                       = as #=# bs

            Assert(prop)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          "not",
          "((@(posedge clock) ib0) ##0",
          "(@(posedge clock) ##1 (@(posedge clock) 1'h1)) |-> not",
          "(@(posedge clock) ib1))"
        )
      test("#=# property rhs"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:    Referable[Bool] & HasOperation = io.ib0
            val b:    Referable[Bool] & HasOperation = io.ib1
            val as:   Sequence                       = a.S
            val bp:   Property                       = !b.S
            val prop: Property                       = as #=# bp

            Assert(prop)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          "not",
          "((@(posedge clock) ib0) ##0",
          "(@(posedge clock) ##1 (@(posedge clock) 1'h1)) |-> not not",
          "(@(posedge clock) ib1))"
        )
      test("always"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:    Referable[Bool] & HasOperation = io.ib0
            val b:    Referable[Bool] & HasOperation = io.ib1
            val as:   Sequence                       = a.S
            val bs:   Sequence                       = b.S
            val prop: Property                       = always(as)

            Assert(prop)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"(@(posedge clock) ib0) until 1'h0"
        )
      test("eventually"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:    Referable[Bool] & HasOperation = io.ib0
            val b:    Referable[Bool] & HasOperation = io.ib1
            val as:   Sequence                       = a.S
            val bs:   Sequence                       = b.S
            val prop: Property                       = eventually(as)

            Assert(prop)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"(s_eventually (@(posedge clock) ib0))"
        )
      test("until"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:    Referable[Bool] & HasOperation = io.ib0
            val b:    Referable[Bool] & HasOperation = io.ib1
            val as:   Sequence                       = a.S
            val bs:   Sequence                       = b.S
            val prop: Property                       = as until bs

            Assert(prop)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          s"(@(posedge clock) ib0) until (@(posedge clock) ib1)"
        )
      test("untilWith"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a:    Referable[Bool] & HasOperation = io.ib0
            val b:    Referable[Bool] & HasOperation = io.ib1
            val as:   Sequence                       = a.S
            val bs:   Sequence                       = b.S
            val prop: Property                       = as untilWith bs

            Assert(prop)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          "(@(posedge clock) ib0) until (@(posedge clock) ib0)",
          "and (@(posedge clock) ib1)"
        )

    test("Clock"):
      test("Simple"):
        @generator
        object SimpleSVA
            extends Generator[SVASpecParameter, SVASpecLayers, SVASpecIO, SVASpecProbe]
            with HasVerilogTest:
          def architecture(parameter: SVASpecParameter) =
            val io           = summon[Interface[SVASpecIO]]
            given ClockEvent = posedge(io.clock)
            val a: Referable[Bool] & HasOperation = io.ib0
            val b: Referable[Bool] & HasOperation = io.ib1

            Assert(a.S ### b.S ### a.S ### b.S ### a.S)
        SimpleSVA.verilogTest(SVASpecParameter(32))(
          "((@(posedge clock) ib0) ##0",
          "(@(posedge clock) ##1 (@(posedge clock) ib1)) ##0",
          "(@(posedge clock) ##1 (@(posedge clock) ib0)) ##0",
          "(@(posedge clock) ##1 (@(posedge clock) ib1)) ##0",
          "(@(posedge clock) ##1 (@(posedge clock) ib0)))"
        )
      test("Asynchronous"):
        @generator
        object MultiClock
            extends Generator[MultiClockParameter, MultiClockLayers, MultiClockIO, MultiClockProbe]
            with HasVerilogTest:
          def architecture(parameter: MultiClockParameter) =
            val io = summon[Interface[MultiClockIO]]
            val a: Referable[Bool] & HasOperation = io.ib0
            val b: Referable[Bool] & HasOperation = io.ib1

            Assert(posedge(io.clock0)(a.S) ## negedge(io.clock1)(b.S))

        MultiClock.verilogTest(MultiClockParameter(32))(
          "(@(posedge clock0) ib0) ##0 (@(negedge clock1) ib1)"
        )
      test("Nested"):
        @generator
        object MultiClock
            extends Generator[MultiClockParameter, MultiClockLayers, MultiClockIO, MultiClockProbe]
            with HasVerilogTest:
          def architecture(parameter: MultiClockParameter) =
            val io = summon[Interface[MultiClockIO]]
            val a: Referable[Bool] & HasOperation = io.ib0
            val b: Referable[Bool] & HasOperation = io.ib1

            given ClockEvent = posedge(io.clock0)

            Assert(a.S ### negedge(io.clock1)(b.S) ### a.S)

        MultiClock.verilogTest(MultiClockParameter(32))(
          "((@(posedge clock0) ib0) ##0",
          "(@(negedge clock1) ##1 (@(negedge clock1) ib1)) ##0",
          "(@(posedge clock0) ##1 (@(posedge clock0) ib0)))"
        )
