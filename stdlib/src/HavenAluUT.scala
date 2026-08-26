// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.{Generate, Sem, Txn, UT}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class HavenAluParameter() extends Parameter

given upickle.default.ReadWriter[HavenAluParameter] = upickle.default.macroRW

class HavenAluLayers(parameter: HavenAluParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

/** The HAVEN benchmark ALU's own port list (hdl/alu/rtl/alu_top.v): 32-bit ALU with FP add/sub, integer logic,
  * shifts, FP2INT; `done` pulses when a result lands.
  */
class HavenAluIO(parameter: HavenAluParameter) extends HWBundle(parameter):
  val clk    = Flipped(Clock())
  val rst_n  = Flipped(Bool())
  val a      = Flipped(Bits(32))
  val b      = Flipped(Bits(32))
  val op     = Flipped(Bits(4))
  val start  = Flipped(Bool())
  val result = Aligned(Bits(32))
  val flags  = Aligned(Bits(4))
  val done   = Aligned(Bool())

class HavenAluProbe(parameter: HavenAluParameter) extends DVBundle[HavenAluParameter, HavenAluLayers](parameter)

case class HavenAluVerilogParams() extends VerilogParameter

/** The first real HAVEN IP through the typed harness. The RTL is a vendored test resource
  * (`stdlib/tests/resources/haven/alu_top.v`), not inlined — the test hands it to circt-verilog and Verilator.
  */
@generator
object HavenAlu extends VerilogWrapper[HavenAluParameter, HavenAluLayers, HavenAluIO, HavenAluProbe, HavenAluVerilogParams]:
  def verilogModuleName(parameter: HavenAluParameter) = "alu_top"
  def verilogParameter(parameter: HavenAluParameter)  = HavenAluVerilogParams()

  // Parameterless wrapper: firld compares extmodule decls strictly, so the module name must be the plain
  // verilog name (see ExtAccum).
  override def moduleName(parameter: HavenAluParameter): String = verilogModuleName(parameter)

class HavenAluUTLayers(parameter: HavenAluParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

/** Model B drives one port: the two 32-bit operands travel packed as `A = b ## a` (64 bits exactly — the stimulus
  * codec's limit); the opcode is tied to XOR and `start` low, which also deactivates the FP cones for the solver.
  */
class HavenAluUTIO(parameter: HavenAluParameter) extends HWBundle(parameter):
  val clock  = Flipped(Clock())
  val reset  = Flipped(Reset())
  val A      = Flipped(Bits(64))
  val RESULT = Aligned(Bits(32))
  val DONE   = Aligned(Bool())

class HavenAluUTProbe(parameter: HavenAluParameter) extends DVBundle[HavenAluParameter, HavenAluUTLayers](parameter):
  val RESULT = ProbeRead(Bits(32), layers("Verification"))
  val DONE   = ProbeRead(Bits(1), layers("Verification"))

/** The real-IP formal-CRV example: C = "the ALU completes with result 0xBEEF" — solved through the benchmark's own
  * XOR datapath and done pipeline (the solver must schedule an input change and invert the XOR).
  */
@generator
object HavenAluUT
    extends Generator[HavenAluParameter, HavenAluUTLayers, HavenAluUTIO, HavenAluUTProbe]
    with UT[HavenAluParameter, HavenAluUTIO]:
  override def moduleName(p: HavenAluParameter): String = "HavenAluUT"

  def architecture(parameter: HavenAluParameter) =
    val io       = summon[Interface[HavenAluUTIO]]
    val instance = HavenAlu.instantiate(parameter)
    instance.io.clk   := io.clock
    instance.io.rst_n := !io.reset.asBool
    instance.io.a     := io.A.bits(31, 0)
    instance.io.b     := io.A.bits(63, 32)
    instance.io.op    := 4.U(4).asBits // OP_XOR
    instance.io.start := false.B
    io.RESULT         := instance.io.result
    io.DONE           := instance.io.done

    Txn.assumeResetLow(io.reset)

    // C as typed state semantics: the ALU's completion state — done with result 0xBEEF.
    Generate(Sem.state(instance.io.done & (instance.io.result === 48879.U(32).asBits)), "gen_alu_xor_beef")

    val probe = summon[ProbeInterface[HavenAluUTProbe]]
    layer("Verification"):
      val resultP = Wire(Bits(32))
      resultP := instance.io.result
      probe.RESULT <== resultP
      val doneP   = Wire(Bits(1))
      doneP := instance.io.done.asBits
      probe.DONE <== doneP
