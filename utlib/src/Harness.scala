// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.stdlib.*
import me.jiuyang.stdlib.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.ClockEvent
import me.jiuyang.zaozi.magic.macros.generator
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

/** Everything the harness needs to elaborate: the DUT's width, the solved stimulus baked in as constants, and the
  * coverpoints to declare.
  *
  * The stimulus travels *inside* the parameter so that a single generator object covers every solved sequence —
  * `Generator.moduleName` already hashes the parameter, so distinct sequences get distinct module names and never
  * collide.
  */
final case class HarnessParameter(
  width:       Int,
  stimulus:    SolvedStimulus,
  coverpoints: Seq[Coverpoint])
    extends Parameter

object HarnessParameter:
  given upickle.default.ReadWriter[HarnessParameter] = upickle.default.macroRW

class HarnessLayers(parameter: HarnessParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

/** The harness's own IO: a clock domain and a `done` flag that rises once the solved sequence has been played out. The
  * generated SystemVerilog top watches `done` to end the simulation.
  */
class HarnessIO(parameter: HarnessParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val done  = Aligned(Bool())

class HarnessProbe(parameter: HarnessParameter) extends DVBundle[HarnessParameter, HarnessLayers](parameter)

/** The known coverpoint vocabulary for the [[Fifo]] DUT.
  *
  * A test names coverpoints by string; this map is what gives those names meaning. An unknown name fails elaboration
  * rather than silently never being hit, which would otherwise look like a coverage hole in a passing run.
  */
object FifoCoverpoints:
  val known: Seq[String] = Seq(
    "cover_enq_fire",
    "cover_deq_fire",
    "cover_full",
    "cover_empty",
    "cover_full_enq",
    "cover_empty_deq",
    "cover_simultaneous"
  )

/** A testbench around [[Fifo]] driven entirely by a solved stimulus.
  *
  * Each cycle of the sequence becomes a `when (cycle === N)` block that drives the DUT's Decoupled ports with the
  * values the solver chose. Coverpoints become clocked SVA `Cover` properties, which reach SystemVerilog as `cover
  * property` statements and are counted by Verilator.
  */
@generator
object FifoHarness extends Generator[HarnessParameter, HarnessLayers, HarnessIO, HarnessProbe] with HasSvEmit:
  def architecture(parameter: HarnessParameter) =
    val io = summon[Interface[HarnessIO]]

    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)
    given ClockEvent = posedge(io.clock)

    val dut = Fifo.instantiate(FifoParameter(parameter.width))
    dut.io.clock := io.clock
    dut.io.reset := io.reset

    // Cycle tracker. A one-hot shift register rather than a counter: FIRRTL's
    // `add` grows the result width, so `count := count + 1.U` would be a
    // width-mismatched connect. One register per cycle keeps every connect
    // width-exact, and the sequences a unit test plays are short.
    val cycles = parameter.stimulus.cycles
    val stages = (0 to cycles).map(i => RegInit(if i == 0 then true.B else false.B))
    (cycles to 1 by -1).foreach(i => stages(i) := stages(i - 1))
    stages(0) := false.B

    // `done` rises the cycle after the last stimulus slot has been driven.
    io.done := stages(cycles)

    // Defaults: no traffic unless a solved slot says otherwise.
    dut.io.enq.valid := false.B
    dut.io.enq.bits  := 0.U(parameter.width)
    dut.io.deq.ready := false.B

    parameter.stimulus.txns.foreach { txn =>
      txn.kind match
        case TxnKind.Enqueue =>
          when(stages(txn.cycle)) {
            dut.io.enq.valid := true.B
            dut.io.enq.bits  := txn.payload.toInt.U(parameter.width)
          }
        case TxnKind.Dequeue =>
          when(stages(txn.cycle)) {
            dut.io.deq.ready := true.B
          }
        case TxnKind.Idle    => ()
    }

    val enqFire = dut.io.enq.valid & dut.io.enq.ready
    val deqFire = dut.io.deq.valid & dut.io.deq.ready

    parameter.coverpoints.foreach { point =>
      val condition = point.name match
        case "cover_enq_fire"     => enqFire
        case "cover_deq_fire"     => deqFire
        case "cover_full"         => dut.io.full
        case "cover_empty"        => dut.io.empty
        case "cover_full_enq"     => dut.io.full & dut.io.enq.valid
        case "cover_empty_deq"    => dut.io.empty & dut.io.deq.ready
        case "cover_simultaneous" => enqFire & deqFire
        case other                =>
          throw new IllegalArgumentException(
            s"unknown coverpoint `$other`; FifoHarness understands ${FifoCoverpoints.known.mkString(", ")}"
          )
      Cover(condition.S, point.name)
    }
