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
  def apply[P <: AdderParameter](
    parameter: P
  )(
    using Arena,
    AdderImpl,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Wire[AdderIO[P]] = summon[AdderImpl].apply(parameter)

/** Implementation hook for the portable [[Adder]] interface. */
trait AdderImpl:
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
  ): Wire[AdderIO[P]]
