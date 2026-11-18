// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.adder.default

import java.lang.foreign.Arena

import me.jiuyang.stdlib.adder.{AdderIO, AdderImpl, AdderLayers, AdderParameter, AdderProbe}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class RippleAdderParameter(width: Int) extends AdderParameter:
  require(width > 0, "width must be positive")

given upickle.default.ReadWriter[RippleAdderParameter] = upickle.default.macroRW

/** Each full-adder consumes the carry from the preceding bit. */
@generator
object RippleAdder
    extends Generator[
      RippleAdderParameter,
      AdderLayers[RippleAdderParameter],
      AdderIO[RippleAdderParameter],
      AdderProbe[RippleAdderParameter]
    ]:
  override def moduleName(p: RippleAdderParameter): String = s"RippleAdder_width${p.width}"

  def architecture(parameter: RippleAdderParameter) =
    val io = summon[Interface[AdderIO[RippleAdderParameter]]]
    case class Stage(sum: Referable[Bool], carry: Referable[Bool])

    val stages = (0 until parameter.width).scanLeft(Stage(false.B, io.ci)): (previous, index) =>
      val a = io.a.bit(index)
      val b = io.b.bit(index)
      val c = previous.carry
      Stage(a ^ b ^ c, (a & b) | (a & c) | (b & c))

    val sums                    = stages.tail.map(_.sum)
    val sumWord                 = sums.tail.foldLeft[Referable[Bits]](sums.head.asBits)((word, bit) => bit.asBits ## word)
    val (checkedCO, checkedSUM) = Contract((stages.last.carry, sumWord)) { case (co, sum) =>
      val observed = (co.asBits ## sum).asUInt
      val expected = (io.a.asUInt + io.b.asUInt + io.ci.asBits.asUInt).asBits.bits(parameter.width, 0).asUInt
      Ensure((observed === expected).I, "ripple_adder_matches_add")
    }

    io.sum := checkedSUM
    io.co  := checkedCO

given AdderImpl with
  def apply[P <: AdderParameter](
    parameter: P
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Wire[AdderIO[P]] =
    val io      = Wire(new AdderIO(parameter))
    val adderIO = RippleAdder.instantiate(RippleAdderParameter(parameter.width)).io

    adderIO.a  := io.a
    adderIO.b  := io.b
    adderIO.ci := io.ci
    io.co      := adderIO.co
    io.sum     := adderIO.sum
    io
