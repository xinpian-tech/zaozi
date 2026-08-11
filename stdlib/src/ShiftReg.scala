// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

/** Configuration for a universal shift register. */
case class ShiftRegParameter(width: Int) extends Parameter:
  require(width > 1, s"shift-register width must be greater than one, got $width")

given upickle.default.ReadWriter[ShiftRegParameter] = upickle.default.macroRW

class ShiftRegLayers(parameter: ShiftRegParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ShiftRegIO(parameter: ShiftRegParameter) extends HWBundle(parameter):
  val clock          = Flipped(Clock())
  val serialInput    = Flipped(Bool())
  val parallelInput  = Flipped(Bits(parameter.width))
  val shift          = Flipped(Bool())
  val load           = Flipped(Bool())
  val parallelOutput = Aligned(Bits(parameter.width))

class ShiftRegProbe(parameter: ShiftRegParameter) extends DVBundle[ShiftRegParameter, ShiftRegLayers](parameter)

/** Universal shift register with parallel-load priority.
  *
  * On each rising edge, `load` replaces the complete state with `parallelInput`. Otherwise, `shift` moves the current
  * value toward the most-significant bit and inserts `serialInput` at bit zero. With neither control asserted, the
  * register holds its value. The initial value is intentionally unspecified because the module has no reset. Its
  * contract checks the complete transition relation from one rising edge to the next.
  */
@generator
object ShiftReg extends Generator[ShiftRegParameter, ShiftRegLayers, ShiftRegIO, ShiftRegProbe]:
  override def moduleName(parameter: ShiftRegParameter): String = s"ShiftReg_width${parameter.width}"

  def architecture(parameter: ShiftRegParameter) =
    val io           = summon[Interface[ShiftRegIO]]
    given ClockScope = ClockScope.posedge(io.clock)

    val state        = Reg(Bits(parameter.width))
    val shiftedState = state.bits(parameter.width - 2, 0) ## io.serialInput.asBits

    val nextState = Wire(Bits(parameter.width))
    nextState := state
    when(io.load) {
      nextState := io.parallelInput
    }.otherwise {
      when(io.shift) {
        nextState := shiftedState
      }
    }

    state             := nextState
    io.parallelOutput := state

    Contract {
      given ClockEvent = posedge(io.clock)

      val transitionMatches = (0 until parameter.width)
        .map: bit =>
          past(nextState.bit(bit)).S iff state.bit(bit).S
        .reduce(_ & _)
      Ensure(true.B.S |=> transitionMatches, "shift_reg_transition")
    }
