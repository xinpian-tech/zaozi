// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.{Generate, Probes, Sem, Txn, UT}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class AccumParameter(width: Int) extends Parameter:
  require(width > 0, "width must be positive")

given upickle.default.ReadWriter[AccumParameter] = upickle.default.macroRW

class AccumLayers(parameter: AccumParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class AccumIO(parameter: AccumParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val A     = Flipped(Bits(parameter.width))
  val SUM   = Aligned(Bits(parameter.width))

class AccumUTProbe(parameter: AccumParameter) extends DVBundle[AccumParameter, AccumLayers](parameter):
  val SUM = ProbeRead(Bits(parameter.width), layers("Verification"))

/** A running modulo-2^width accumulator: `SUM += A` each cycle. */
@generator
object Accum extends Generator[AccumParameter, AccumLayers, AccumIO, AccumUTProbe]:
  override def moduleName(p: AccumParameter): String = s"Accum_width${p.width}"

  def architecture(parameter: AccumParameter) =
    val io = summon[Interface[AccumIO]]

    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)

    val sum = RegInit(0.B(parameter.width))
    sum    := (sum.asUInt + io.A.asUInt).asBits.bits(parameter.width - 1, 0)
    io.SUM := sum

    val probe = summon[ProbeInterface[AccumUTProbe]]
    layer("Verification"):
      Probes.expose(probe.SUM, Bits(parameter.width), sum)

/** The cross-transaction formal-CRV example: the constraint relates *three different beats* of one stimulus — C = "the
  * first three beats are pairwise distinct and sum to 12". [[Txn.history]] materializes the two prior beats as typed
  * state, turning the cross-object relation into a plain boolean over `(hist(1), hist(0), A)` guarded by the beat
  * counter; the body asserts ¬C and the BMC violation is the three-beat transaction.
  */
@generator
object AccumUT extends Generator[AccumParameter, AccumLayers, AccumIO, AccumUTProbe] with UT[AccumParameter, AccumIO]:
  override def moduleName(p: AccumParameter): String = s"AccumUT_width${p.width}"

  def architecture(parameter: AccumParameter) =
    val io       = summon[Interface[AccumIO]]
    val instance = Accum.instantiate(parameter)
    instance.io.clock := io.clock
    instance.io.reset := io.reset
    instance.io.A     := io.A
    io.SUM            := instance.io.SUM

    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)

    // C as typed relation semantics: the three beats (two past, one current) are pairwise distinct and sum to
    // 12 — the window's reality guard is conjoined automatically.
    val w = Txn.window(io.A, parameter.width, 2)
    Generate(
      Sem.relation(w) { w =>
        val distinct = !(w.past(2) === w.past(1)) & !(w.past(2) === io.A) & !(w.past(1) === io.A)
        val sum3     = (w.past(2).asUInt + w.past(1).asUInt + io.A.asUInt).asBits.bits(parameter.width - 1, 0)
        distinct & (sum3 === 12.U(parameter.width).asBits)
      },
      "gen_three_distinct_sum12"
    )

    val probe = summon[ProbeInterface[AccumUTProbe]]
    layer("Verification"):
      Probes.expose(probe.SUM, Bits(parameter.width), instance.io.SUM)
