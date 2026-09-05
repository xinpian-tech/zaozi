// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.{Generate, Probes, Sem, UT}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class TwoBeatParameter(width: Int) extends Parameter:
  require(width > 0, "width must be positive")

given upickle.default.ReadWriter[TwoBeatParameter] = upickle.default.macroRW

class TwoBeatLayers(parameter: TwoBeatParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class TwoBeatIO(parameter: TwoBeatParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val A     = Flipped(Bits(parameter.width))
  val SEEN  = Aligned(Bool())

/** The observation surface: `SEEN` sampled by the frontend. */
class TwoBeatUTProbe(parameter: TwoBeatParameter) extends DVBundle[TwoBeatParameter, TwoBeatLayers](parameter):
  val SEEN = ProbeRead(Bool(), layers("Verification"))

/** A minimal sequential DUT: `SEEN` pulses when the input shows the two-beat pattern `A==3` then `A==5` on consecutive
  * cycles — one register of history.
  */
@generator
object TwoBeat extends Generator[TwoBeatParameter, TwoBeatLayers, TwoBeatIO, TwoBeatUTProbe]:
  override def moduleName(p: TwoBeatParameter): String = s"TwoBeat_width${p.width}"

  def architecture(parameter: TwoBeatParameter) =
    val io = summon[Interface[TwoBeatIO]]

    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)

    val three = 3.U(parameter.width).asBits
    val five  = 5.U(parameter.width).asBits

    val prevWasThree = RegInit(0.B(1))
    prevWasThree := (io.A === three).asBits
    io.SEEN      := prevWasThree.bit(0) & (io.A === five)

    val probe = summon[ProbeInterface[TwoBeatUTProbe]]
    layer("Verification"):
      Probes.expose(probe.SEEN, Bool(), io.SEEN)

/** The temporal formal-CRV example: the generation constraint is a *sequence* — C = `(A==3) ### (A==5)` (##1 in SVA
  * terms) (the two-beat pattern), written in zaozi's typed SVA surface. The body asserts ¬C so the circt-bmc violation
  * trace is a multi-cycle transaction matching C — same dual reading as [[AbsValOddUT]], one abstraction level up.
  */
@generator
object TwoBeatUT
    extends Generator[TwoBeatParameter, TwoBeatLayers, TwoBeatIO, TwoBeatUTProbe]
    with UT[TwoBeatParameter, TwoBeatIO]:
  override def moduleName(p: TwoBeatParameter): String = s"TwoBeatUT_width${p.width}"

  def architecture(parameter: TwoBeatParameter) =
    val io       = summon[Interface[TwoBeatIO]]
    val instance = TwoBeat.instantiate(parameter)
    instance.io.clock := io.clock
    instance.io.reset := io.reset
    instance.io.A     := io.A
    io.SEEN           := instance.io.SEEN

    val three = 3.U(parameter.width).asBits
    val five  = 5.U(parameter.width).asBits

    // C as typed temporal semantics: the two-beat pattern A==3 then A==5. (In the module body — a layered
    // assert would be pruned on the formal path.)
    given ClockEvent = posedge(io.clock)
    Generate(Sem.temporal((io.A === three).S ### (io.A === five).S), "gen_two_beat")

    val probe = summon[ProbeInterface[TwoBeatUTProbe]]
    layer("Verification"):
      Probes.expose(probe.SEEN, Bool(), instance.io.SEEN)

/** Partial temporal specification: the two beats are named, but *when* the second follows is left open —
  * `##(1, Some(3))` says "somewhere in the next one to three cycles" and the solver picks. HAVEN's DSL cannot express
  * this: its steps are a total program, so every intervening cycle must be spelled out.
  */
@generator
object TwoBeatGapUT
    extends Generator[TwoBeatParameter, TwoBeatLayers, TwoBeatIO, TwoBeatUTProbe]
    with UT[TwoBeatParameter, TwoBeatIO]:
  override def moduleName(p: TwoBeatParameter): String = s"TwoBeatGapUT_width${p.width}"

  def architecture(parameter: TwoBeatParameter) =
    val io       = summon[Interface[TwoBeatIO]]
    val instance = TwoBeat.instantiate(parameter)
    instance.io.clock := io.clock
    instance.io.reset := io.reset
    instance.io.A     := io.A
    io.SEEN           := instance.io.SEEN

    val three = 3.U(parameter.width).asBits
    val five  = 5.U(parameter.width).asBits

    given ClockEvent = posedge(io.clock)
    Generate(
      Sem.temporal((io.A === three).S.##(1, Some(3))((io.A === five).S)),
      "gen_two_beat_with_gap"
    )

    val probe = summon[ProbeInterface[TwoBeatUTProbe]]
    layer("Verification"):
      Probes.expose(probe.SEEN, Bool(), instance.io.SEEN)
