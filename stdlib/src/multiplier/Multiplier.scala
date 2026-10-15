// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.multiplier

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

/** The `Multiplier` contract: the parameter and IO every multiplier implementation must expose. Concrete
  * implementations and their internal datapath types live in `me.jiuyang.stdlib.multiplier.default`.
  */
case class MultiplierParameter(aWidth: Int, bWidth: Int) extends Parameter:
  require(aWidth > 0, "aWidth must be positive")
  require(bWidth > 0, "bWidth must be positive")

  def productWidth: Int = aWidth + bWidth

given upickle.default.ReadWriter[MultiplierParameter] = upickle.default.macroRW

class MultiplierLayers(parameter: MultiplierParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class MultiplierIO(parameter: MultiplierParameter) extends HWBundle(parameter):
  val a       = Flipped(Bits(parameter.aWidth))
  val b       = Flipped(Bits(parameter.bWidth))
  val signed  = Flipped(Bool())
  val product = Aligned(Bits(parameter.productWidth))

class MultiplierProbe(parameter: MultiplierParameter) extends DVBundle[MultiplierParameter, MultiplierLayers](parameter)
