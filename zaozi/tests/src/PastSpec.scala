// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.zaozitest

import java.lang.foreign.Arena
import me.jiuyang.testlib.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.ClockEvent
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import utest.*

case class PastParameter(width: Int, falling: Boolean) extends Parameter
given upickle.default.ReadWriter[PastParameter] = upickle.default.macroRW

class PastLayers(parameter: PastParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class PastIO(parameter: PastParameter) extends HWBundle(parameter):
  val clock   = Flipped(Clock())
  val bool    = Flipped(Bool())
  val uint    = Flipped(UInt(parameter.width))
  val sint    = Flipped(SInt(parameter.width))
  val bits    = Flipped(Bits(parameter.width))
  val boolOut = Aligned(Bool())
  val uintOut = Aligned(UInt(parameter.width))
  val sintOut = Aligned(SInt(parameter.width))
  val bitsOut = Aligned(Bits(parameter.width))

class PastProbe(parameter: PastParameter) extends DVBundle[PastParameter, PastLayers](parameter)

object PastSpec extends TestSuite:
  val tests = Tests:
    test("past preserves scalar types and widths on both clock edges"):
      @generator
      object G extends Generator[PastParameter, PastLayers, PastIO, PastProbe] with HasVerilogTest:
        def architecture(parameter: PastParameter) =
          val io           = summon[Interface[PastIO]]
          given ClockEvent = if parameter.falling then negedge(io.clock) else posedge(io.clock)
          val uintInput:    Referable[UInt] = io.uint
          val previousBool: Node[Bool]      = past(io.bool)
          val previousUInt: Node[UInt]      = past(uintInput, 2)
          val previousSInt: Node[SInt]      = past(io.sint, 3)
          val previousBits: Node[Bits]      = past(io.bits, 4)
          assert(previousBool.width == 1)
          assert(previousUInt.width == parameter.width)
          assert(previousSInt.width == parameter.width)
          assert(previousBits.width == parameter.width)
          io.boolOut := previousBool
          io.uintOut := previousUInt
          io.sintOut := previousSInt
          io.bitsOut := previousBits
          compileError("past(io.clock)")
          compileError("val wrong: Node[Bool] = past(io.uint)")
          compileError("val wrong: Node[UInt] = past(io.sint)")

      for
        width   <- Seq(1, 13, 32)
        falling <- Seq(false, true)
      do
        val edge    = if falling then "negedge" else "posedge"
        val verilog = G.verilogString(PastParameter(width, falling))
        assert(verilog.contains(s"$$past(bool, 1, , @($edge clock))"))
        assert(verilog.contains(s"$$past(uint, 2, , @($edge clock))"))
        assert(verilog.contains(s"$$past(sint, 3, , @($edge clock))"))
        assert(verilog.contains(s"$$past(bits, 4, , @($edge clock))"))

    test("past rejects non-positive delays for word values"):
      @generator
      object G extends Generator[PastParameter, PastLayers, PastIO, PastProbe] with HasCompileErrorTest:
        def architecture(parameter: PastParameter) =
          val io           = summon[Interface[PastIO]]
          given ClockEvent = posedge(io.clock)
          val zero         = intercept[IllegalArgumentException](past(io.uint, 0))
          val negative     = intercept[IllegalArgumentException](past(io.sint, -1))
          assert(zero.getMessage.contains("past delay (0)"))
          assert(negative.getMessage.contains("past delay (-1)"))
      G.compileErrorTest(PastParameter(13, false))
