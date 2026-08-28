// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.{DVRecord, Generator, HWRecord, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import upickle.default.ReadWriter

given mainargs.TokensReader.Simple[Arbitration] = jsonTokens("arbitration")

// ============ Xbar: the n×m crossbar ============

enum Arbitration derives CanEqual, ReadWriter:
  case RoundRobin, FixedPriority

case class XbarP(
  name:        String,
  arbitration: Arbitration,
  inputs:      Vector[(String, AxiShape)],
  outputs:     Vector[(String, AxiShape)])
    extends Parameter derives ReadWriter
class XbarPLayers(p: XbarP) extends LayerInterface(p):
  def layers = Seq.empty
class XbarPProbe(p: XbarP) extends DVRecord[XbarP, XbarPLayers](p)
class XbarPIO(p: XbarP) extends HWRecord(p):
  val ins  = p.inputs.map((n, s) => Flipped(n, new AxiPortRecord(s)))
  val outs = p.outputs.map((n, s) => Aligned(n, new AxiPortRecord(s)))
@zaoziGenerator
object XbarGen          extends Generator[XbarP, XbarPLayers, XbarPIO, XbarPProbe]:
  def architecture(p: XbarP) = summon[Interface[XbarPIO]].dontCare()
