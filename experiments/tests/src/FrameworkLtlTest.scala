// SPDX-License-Identifier: Apache-2.0
import me.jiuyang.testlib.*
import me.jiuyang.utlib.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import utest.*

// Synthetic API tests, never retrieved as DUT stimulus or framework examples.
case class LtlExampleParameter(width: Int, gap: Int) extends Parameter
given upickle.default.ReadWriter[LtlExampleParameter] = upickle.default.macroRW
class LtlExampleLayers(p: LtlExampleParameter) extends LayerInterface(p):
  def layers = Seq.empty
class LtlExampleIO(parameter: LtlExampleParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val p = Flipped(Bool())
  val q = Flipped(Bool())
  val bits = Flipped(Bits(parameter.width))
class LtlExampleProbe(p: LtlExampleParameter) extends DVBundle[LtlExampleParameter, LtlExampleLayers](p)

object FrameworkLtlTest extends TestSuite:
  @generator
  object Example extends Generator[LtlExampleParameter, LtlExampleLayers, LtlExampleIO, LtlExampleProbe]
      with HasVerilogTest:
    def architecture(parameter: LtlExampleParameter) =
      val io = summon[Interface[LtlExampleIO]]
      given ClockEvent = posedge(io.clock)
      Gen(FrameworkGoalExample.conjunction(io.p, io.q), "both")
      Gen(io.p | io.q, "either")
      Gen(FrameworkGoalExample.deasserted(io.p), "negated")
      Gen(FrameworkGoalExample.bitsEquality(io.bits, BigInt(1), parameter.width), "equal")
      Gen(io.p.S ### io.q.S, "next")
      Gen(FrameworkGoalExample.ordered(io.p, io.q, parameter.gap), "fixed")
      Gen(FrameworkGoalExample.bounded(io.p, io.q, parameter.gap, parameter.gap + 2), "bounded")
      Gen(FrameworkGoalExample.changed(io.bits, parameter.gap), "changed")
      Gen(FrameworkGoalExample.changedAfter(io.p, io.bits, parameter.gap), "observed_change")

  val tests: Tests = Tests:
    test("skill expression forms lower to native covers with the requested delays"):
      for gap <- Seq(1, 3) do
        val sv = Example.verilogString(LtlExampleParameter(13, gap))
        assert(sv.contains("cover property"))
        assert(sv.contains("##1"))
        assert(sv.contains(s"##$gap"))
        assert(sv.contains(s"##[$gap:${gap + 2}]"))
        assert(sv.contains(s"$$past(bits, $gap, , @(posedge clock))"))
        assert(!sv.contains("assume property"))
        assert(!sv.contains("assert property"))
