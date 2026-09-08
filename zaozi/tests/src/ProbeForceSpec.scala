// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.testlib.*
import utest.*
import java.lang.foreign.Arena

case class ForceParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[ForceParameter] = upickle.default.macroRW

class ForceLayers(parameter: ForceParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Test"))

class ForceIO(parameter: ForceParameter) extends HWBundle(parameter):
  val clock  = Flipped(Clock())
  val reset  = Flipped(Reset())
  val data   = Flipped(UInt(parameter.width))
  val enable = Flipped(Bool())
  val out    = Aligned(UInt(parameter.width))

class ForceProbe(parameter: ForceParameter) extends DVBundle[ForceParameter, ForceLayers](parameter):
  val rw    = ProbeReadWrite(UInt(parameter.width), layers("Test"))
  val alias = ProbeReadWrite(UInt(parameter.width), layers("Test"))
  val ro    = ProbeRead(UInt(parameter.width), layers("Test"))

object ProbeForceSpec extends TestSuite:
  val tests = Tests:
    test("force and release preserve types, operands, and target aliases"):
      @generator
      object G extends Generator[ForceParameter, ForceLayers, ForceIO, ForceProbe] with HasMlirTest:
        def architecture(parameter: ForceParameter) =
          val io     = summon[Interface[ForceIO]]
          val probe  = summon[ProbeInterface[ForceProbe]]
          val target = Node(io.data, forceable = true)
          io.out := target
          layer("Test"):
            probe.rw <== target
            probe.alias <== target
            probe.ro <== target
            probe.rw.force(io.data, io.clock, io.enable)
            probe.rw.release(io.clock, io.enable)
            probe.alias.forceInitial(io.data, io.enable)
            probe.alias.releaseInitial(io.enable)
            compileError("probe.ro.force(io.data, io.clock, io.enable)")
            compileError("probe.ro.release(io.clock, io.enable)")
            compileError("probe.ro.forceInitial(io.data, io.enable)")
            compileError("probe.ro.releaseInitial(io.enable)")
            compileError("probe.rw.force(true.B, io.clock, io.enable)")
      val mlir = G.mlirString(ForceParameter(8))
      val expected = Seq(
        "firrtl.ref.force ",
        "firrtl.ref.release ",
        "firrtl.ref.force_initial ",
        "firrtl.ref.release_initial ",
        "forceable"
      )
      assert(expected.forall(mlir.contains))
      assert(!mlir.contains("firrtl.ref.rwprobe"), !mlir.contains("inner_sym"), !mlir.contains("sym @"))
      val sources  = mlir.linesIterator
        .filter(_.contains("firrtl.ref.cast"))
        .map(_.split("firrtl.ref.cast ")(1).takeWhile(_ != ' '))
        .toSeq
      assert(sources.size == 3, sources(0) == sources(1))

    test("writable binding rejects literal targets"):
      @generator
      object G extends Generator[ForceParameter, ForceLayers, ForceIO, ForceProbe] with HasMlirTest:
        def architecture(parameter: ForceParameter) =
          val io    = summon[Interface[ForceIO]]
          val probe = summon[ProbeInterface[ForceProbe]]
          io.out := io.data
          layer("Test"):
            probe.rw <== 0.U(parameter.width)
      val error = intercept[IllegalArgumentException](G.mlirString(ForceParameter(8)))
      assert(error.getMessage.contains("whole Wire, Reg, or Node"))

    test("writable binding requires an explicitly forceable declaration"):
      @generator
      object G extends Generator[ForceParameter, ForceLayers, ForceIO, ForceProbe] with HasMlirTest:
        def architecture(parameter: ForceParameter) =
          val io     = summon[Interface[ForceIO]]
          val probe  = summon[ProbeInterface[ForceProbe]]
          val target = Wire(UInt(parameter.width))
          target := io.data
          io.out := target
          layer("Test"):
            probe.rw <== target
      val error = intercept[IllegalArgumentException](G.mlirString(ForceParameter(8)))
      assert(error.getMessage.contains("forceable = true"))

    test("reset registers provide a writable SSA result"):
      @generator
      object G extends Generator[ForceParameter, ForceLayers, ForceIO, ForceProbe] with HasMlirTest:
        def architecture(parameter: ForceParameter) =
          val io           = summon[Interface[ForceIO]]
          val probe        = summon[ProbeInterface[ForceProbe]]
          given ClockScope = ClockScope.posedge(io.clock)
          given ResetScope = ResetScope.syncActiveHigh(io.reset)
          val target       = RegInit(0.U(parameter.width), forceable = true)
          target := io.data
          io.out := target
          layer("Test"):
            probe.rw <== target
            probe.alias <== target
            probe.ro <== target
            probe.rw.force(io.data, io.clock, io.enable)
            probe.rw.release(io.clock, io.enable)
      val mlir = G.mlirString(ForceParameter(8))
      assert(mlir.contains("firrtl.regreset"), mlir.contains("forceable"), !mlir.contains("firrtl.ref.rwprobe"))
