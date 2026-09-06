// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.adder

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

/** Backend-independent configuration for a fixed-width unsigned adder. */
trait AdderParameter extends Parameter:
  def width: Int

case class PrefixAdderParameter(width: Int, radix: Int = 4) extends AdderParameter:
  require(width > 0, "width must be positive")
  require(radix >= 2, "radix must be at least 2")

given upickle.default.ReadWriter[PrefixAdderParameter] = upickle.default.macroRW

class AdderLayers[P <: AdderParameter](parameter: P) extends LayerInterface(parameter):
  def layers = Seq.empty

/** Backend-independent combinational adder interface. */
class AdderIO[P <: AdderParameter](parameter: P) extends HWBundle(parameter):
  val a   = Flipped(Bits(parameter.width))
  val b   = Flipped(Bits(parameter.width))
  val ci  = Flipped(Bool())
  val co  = Aligned(Bool())
  val sum = Aligned(Bits(parameter.width))

class AdderProbe[P <: AdderParameter](parameter: P) extends DVBundle[P, AdderLayers[P]](parameter)

object Adder:
  def apply(
    parameter: PrefixAdderParameter
  )(
    using Arena,
    AdderImpl,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Wire[AdderIO[PrefixAdderParameter]] = summon[AdderImpl].apply(parameter)

/** Implementation hook for the portable [[Adder]] interface. */
trait AdderImpl:
  def apply(
    parameter: PrefixAdderParameter
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Wire[AdderIO[PrefixAdderParameter]]
