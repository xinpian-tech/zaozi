// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Zaozi contributors
package me.jiuyang.zaozitest

import me.jiuyang.testlib.{HasFirrtlTest, HasVerilogTest}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.Interface
import me.jiuyang.zaozi.valuetpe.*
import utest.*

case class ProbeWriteSpecParameter() extends Parameter
given upickle.default.ReadWriter[ProbeWriteSpecParameter] = upickle.default.macroRW

class ProbeWriteSpecLayers(parameter: ProbeWriteSpecParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("DV"))

class ProbeWriteSpecIO(parameter: ProbeWriteSpecParameter) extends HWBundle(parameter):
  val clock:          BundleField[Clock] = Flipped(Clock())
  val reset:          BundleField[Reset] = Flipped(Reset())
  val force_enable:   BundleField[Bool]  = Flipped(Bool())
  val release_enable: BundleField[Bool]  = Flipped(Bool())
  val force_value:    BundleField[UInt]  = Flipped(UInt(3))
  val state:          BundleField[UInt]  = Aligned(UInt(3))

class ProbeWriteSpecProbe(parameter: ProbeWriteSpecParameter)
    extends DVBundle[ProbeWriteSpecParameter, ProbeWriteSpecLayers](parameter)

object ProbeWriteSpec extends TestSuite:
  @generator
  object ForceableRegister
      extends Generator[
        ProbeWriteSpecParameter,
        ProbeWriteSpecLayers,
        ProbeWriteSpecIO,
        ProbeWriteSpecProbe
      ]
      with HasFirrtlTest
      with HasVerilogTest:
    def architecture(parameter: ProbeWriteSpecParameter) =
      val io           = summon[Interface[ProbeWriteSpecIO]]
      given ClockScope = ClockScope.posedge(io.clock)
      given ResetScope = ResetScope.asyncActiveHigh(io.reset)

      val state = RegInit(0.U(3), forceable = true)
      state    := (state + 1.U).asBits.tail(3).asUInt
      io.state := state

      layer("DV"):
        val writeProbe = ProbeWrite(state)
        writeProbe.force(io.force_enable, io.force_value)
        writeProbe.release(io.release_enable)

  @generator
  object AllForceableDeclarations
      extends Generator[
        ProbeWriteSpecParameter,
        ProbeWriteSpecLayers,
        ProbeWriteSpecIO,
        ProbeWriteSpecProbe
      ]
      with HasFirrtlTest
      with HasVerilogTest:
    def architecture(parameter: ProbeWriteSpecParameter) =
      val io           = summon[Interface[ProbeWriteSpecIO]]
      given ClockScope = ClockScope.posedge(io.clock)
      given ResetScope = ResetScope.asyncActiveHigh(io.reset)

      val forceableWire     = Wire(UInt(3), forceable = true)
      val forceableReg      = Reg(UInt(3), forceable = true)
      val forceableRegReset = RegInit(0.U(3), forceable = true)
      val forceableNode     = Node(forceableRegReset, forceable = true)
      forceableWire     := io.force_value
      forceableReg      := forceableWire
      forceableRegReset := forceableReg
      io.state          := forceableNode

      layer("DV"):
        ProbeWrite(forceableWire).force(io.force_enable, io.force_value)
        ProbeWrite(forceableReg).force(io.force_enable, io.force_value)
        ProbeWrite(forceableRegReset).release(io.release_enable)
        ProbeWrite(forceableNode).release(io.release_enable)

  @generator
  object NegedgeForceableRegister
      extends Generator[
        ProbeWriteSpecParameter,
        ProbeWriteSpecLayers,
        ProbeWriteSpecIO,
        ProbeWriteSpecProbe
      ]
      with HasFirrtlTest:
    def architecture(parameter: ProbeWriteSpecParameter) =
      val io           = summon[Interface[ProbeWriteSpecIO]]
      given ClockScope = ClockScope.negedge(io.clock)
      given ResetScope = ResetScope.asyncActiveHigh(io.reset)

      val state = RegInit(0.U(3), forceable = true)
      state    := io.force_value
      io.state := state

      layer("DV"):
        ProbeWrite(state).force(io.force_enable, io.force_value)

  val tests = Tests:
    test("test_forceable_regreset_emits_clocked_probewrite_operations"):
      ForceableRegister.firrtlTest(ProbeWriteSpecParameter())(
        "forceable",
        "force(",
        "release("
      )

    test("test_forceable_regreset_lowers_to_dv_bind_force_and_release"):
      ForceableRegister.verilogTest(ProbeWriteSpecParameter())(
        "module ForceableRegister_DV",
        "always @(posedge ForceableRegister.clock)",
        "force ForceableRegister.state",
        "release ForceableRegister.state",
        "bind ForceableRegister ForceableRegister_DV"
      )

    test("test_forceable_wire_reg_regreset_node_emit_rwprobe_results"):
      AllForceableDeclarations.firrtlTest(ProbeWriteSpecParameter()): output =>
        output.linesIterator.count(_.contains(" forceable :")) == 4 &&
          output.contains("force(") &&
          output.contains("release(")

    test("test_forceable_wire_reg_regreset_node_lower_through_dv_bind"):
      AllForceableDeclarations.verilogTest(ProbeWriteSpecParameter())(
        "module AllForceableDeclarations_DV",
        "bind AllForceableDeclarations AllForceableDeclarations_DV"
      )

    test("test_clocked_probewrite_rejects_negedge_scope"):
      val error = intercept[IllegalArgumentException]:
        NegedgeForceableRegister.firrtlString(ProbeWriteSpecParameter())
      assert(error.getMessage.contains("posedge clocks only"))
