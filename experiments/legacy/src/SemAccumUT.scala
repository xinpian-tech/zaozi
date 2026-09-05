// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.{Generate, Probes, Sem, Txn, UT}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

/** All four typed semantic kinds composed into one intent, on the [[Accum]] DUT:
  *
  *   - value: the fire-cycle beat is 1 (`A === 1`),
  *   - relation: the two beats before it are distinct and nonzero,
  *   - state: the accumulator has reached exactly 9 by the fire cycle (so those two beats sum to 9),
  *   - temporal: the fire-cycle beat is followed by a 2 on the next cycle.
  *
  * Jointly they pin the witness to `[b₁, b₂, 1, 2]` with `b₁ ≠ b₂`, both nonzero, `b₁ + b₂ = 9` — and the replayed
  * accumulator ends at 12.
  */
@generator
object SemAccumUT
    extends Generator[AccumParameter, AccumLayers, AccumIO, AccumUTProbe]
    with UT[AccumParameter, AccumIO]:
  override def moduleName(p: AccumParameter): String = s"SemAccumUT_width${p.width}"

  def architecture(parameter: AccumParameter) =
    val io       = summon[Interface[AccumIO]]
    val instance = Accum.instantiate(parameter)
    instance.io.clock := io.clock
    instance.io.reset := io.reset
    instance.io.A     := io.A
    io.SUM            := instance.io.SUM

    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)
    given ClockEvent = posedge(io.clock)

    val zero = 0.U(parameter.width).asBits
    val one  = 1.U(parameter.width).asBits
    val two  = 2.U(parameter.width).asBits

    val w = Txn.window(io.A, parameter.width, 2)
    Generate(
      Sem.value(io.A === one)
        && Sem.relation(w) { w =>
          !(w.past(2) === w.past(1)) & !(w.past(2) === zero) & !(w.past(1) === zero)
        }
        && Sem.state(instance.io.SUM === 9.U(parameter.width).asBits)
        && Sem.temporal((io.A === one).S ### (io.A === two).S),
      "gen_all_four_kinds"
    )

    val probe = summon[ProbeInterface[AccumUTProbe]]
    layer("Verification"):
      Probes.expose(probe.SUM, Bits(parameter.width), instance.io.SUM)
