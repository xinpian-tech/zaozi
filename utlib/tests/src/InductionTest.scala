// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.ClockEvent
import me.jiuyang.zaozi.magic.macros.generator
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import utest.*

/** The generator-hint demo: a construct that knows, by construction, the invariant tying its
  * occupancy counter to its valid bits — and emits it as an assumption in its own induction
  * step-check elaboration.
  *
  * The property `count != 3` is not 1-inductive bare: from the unreachable state
  * (count = 2, both slots empty) the FIFO is not full, an enqueue fires, and the counter
  * reaches 3 — a classic counterexample-to-induction. The construct's contract
  * (count encodes exactly the occupancy of the valid bits) excludes that state, and the same
  * step check closes.
  */
final case class ObservedFifoParameter(
  checkStep:   Boolean,
  useContract: Boolean)
    extends Parameter

object ObservedFifoParameter:
  given upickle.default.ReadWriter[ObservedFifoParameter] = upickle.default.macroRW

class ObservedFifoLayers(parameter: ObservedFifoParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ObservedFifoIO(parameter: ObservedFifoParameter) extends HWBundle(parameter):
  val clock    = Flipped(Clock())
  val enqValid = Flipped(Bool())
  val deqReady = Flipped(Bool())
  val enqReady = Aligned(Bool())
  val deqValid = Aligned(Bool())

class ObservedFifoProbe(parameter: ObservedFifoParameter)
    extends DVBundle[ObservedFifoParameter, ObservedFifoLayers](parameter)

@generator
object ObservedFifo
    extends Generator[ObservedFifoParameter, ObservedFifoLayers, ObservedFifoIO, ObservedFifoProbe]:
  def architecture(parameter: ObservedFifoParameter) =
    val io = summon[Interface[ObservedFifoIO]]

    given ClockScope = ClockScope.posedge(io.clock)

    // State: two occupancy bits (head, tail) and a two-bit counter c1:c0. Deliberately no
    // reset and no initial value: in the step check the starting state must be arbitrary.
    val valid0 = Reg(Bool())
    val valid1 = Reg(Bool())
    val c0     = Reg(Bool())
    val c1     = Reg(Bool())

    val full    = valid0 & valid1
    val enqFire = io.enqValid & !full
    val deqFire = io.deqReady & valid0
    io.enqReady := !full
    io.deqValid := valid0

    // Explicit next-state wires: the step check is a single-frame combinational question
    // relating the (symbolic) current state to these expressions.
    val valid0Next = Wire(Bool())
    val valid1Next = Wire(Bool())
    valid0Next := (deqFire & (valid1 | enqFire)) | (!deqFire & (valid0 | enqFire))
    valid1Next := (deqFire & (valid1 & enqFire)) | (!deqFire & (valid1 | (enqFire & valid0)))

    val up     = enqFire & !deqFire
    val down   = deqFire & !enqFire
    val c0Next = Wire(Bool())
    val c1Next = Wire(Bool())
    c0Next := c0 ^ (up | down)
    c1Next := c1 ^ ((up & c0) | (down & !c0))

    valid0 := valid0Next
    valid1 := valid1Next
    c0     := c0Next
    c1     := c1Next

    if parameter.checkStep then
      // P: the counter never reads 3.
      val pCur  = !(c1 & c0)
      val pNext = !(c1Next & c0Next)

      if parameter.useContract then
        // The contract this construct knows by construction: the counter encodes exactly
        // the occupancy of the valid bits (0, 1, or 2 — head-first).
        val contract =
          (!c1 & !c0 & !valid0 & !valid1) |
            (!c1 & c0 & valid0 & !valid1) |
            (c1 & !c0 & valid0 & valid1)
        Assume(contract.I)

      Assume(pCur.I)
      Assert(pNext.I)

object InductionTest extends TestSuite:
  private val outputRoot = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)

  val tests: Tests = Tests:
    test("the bare step case finds a counterexample to induction"):
      val outcome = Induction.checkStep(
        ObservedFifo,
        ObservedFifoParameter(checkStep = true, useContract = false),
        outputRoot / "induction-bare"
      )
      outcome match
        case StepOutcome.Cti(_) => ()
        case other              => throw new java.lang.AssertionError(s"expected Cti, got $other")

    test("the construct's contract makes the step case close"):
      val outcome = Induction.checkStep(
        ObservedFifo,
        ObservedFifoParameter(checkStep = true, useContract = true),
        outputRoot / "induction-contract"
      )
      assert(outcome == StepOutcome.Proven)
