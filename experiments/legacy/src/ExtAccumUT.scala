// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.{Generate, Probes, Sem, Txn, UT}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ExtAccumParameter(width: Int) extends Parameter:
  require(width == 8, "the external IP is fixed at width 8")

given upickle.default.ReadWriter[ExtAccumParameter] = upickle.default.macroRW

class ExtAccumLayers(parameter: ExtAccumParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ExtAccumIO(parameter: ExtAccumParameter) extends HWBundle(parameter):
  val clk = Flipped(Clock())
  val rst = Flipped(Bool())
  val a   = Flipped(Bits(parameter.width))
  val sum = Aligned(Bits(parameter.width))

class ExtAccumProbe(parameter: ExtAccumParameter) extends DVBundle[ExtAccumParameter, ExtAccumLayers](parameter)

case class ExtAccumVerilogParams() extends VerilogParameter

/** A SystemVerilog-only IP brought into the typed harness: the RTL lives in [[ExtAccum.source]] (the stand-in for an
  * external benchmark design), enters the testbench through `verilogSources`, and enters the formal model through
  * `SvImport` (import + splice over the extern).
  */
@generator
object ExtAccum
    extends VerilogWrapper[ExtAccumParameter, ExtAccumLayers, ExtAccumIO, ExtAccumProbe, ExtAccumVerilogParams]:
  val source: String =
    """|// A SystemVerilog-only IP (stand-in for an external benchmark design): a modulo-256 accumulator.
       |module ext_accum(
       |  input  logic       clk,
       |  input  logic       rst,
       |  input  logic [7:0] a,
       |  output logic [7:0] sum
       |);
       |  always_ff @(posedge clk) begin
       |    if (rst) sum <= 8'h0;
       |    else     sum <= sum + a;
       |  end
       |endmodule
       |""".stripMargin

  def verilogModuleName(parameter: ExtAccumParameter) = "ext_accum"
  def verilogParameter(parameter:  ExtAccumParameter) = ExtAccumVerilogParams()

  // With NO Verilog parameters, firld compares the wrapper's extmodule dump and the instantiating circuit's
  // placeholder strictly — same (empty) parameters force equal defnames, so the hash-suffixed default moduleName
  // cannot link. A single fixed parameterization needs no suffix; the plain name makes both decls identical.
  override def moduleName(parameter: ExtAccumParameter): String = verilogModuleName(parameter)

  // NOT registered via verilogSources: the VerbatimBlackBoxAnno on the wrapper's own extmodule dump collides with the
  // plain placeholder extmodule the instantiating circuit declares, and firld refuses to merge the two. The RTL
  // reaches the testbench build explicitly instead (the test adds `source` to the Verilator inputs).

class ExtAccumUTLayers(parameter: ExtAccumParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class ExtAccumUTIO(parameter: ExtAccumParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val A     = Flipped(Bits(parameter.width))
  val SUM   = Aligned(Bits(parameter.width))

class ExtAccumUTProbe(parameter: ExtAccumParameter) extends DVBundle[ExtAccumParameter, ExtAccumUTLayers](parameter):
  val SUM = ProbeRead(Bits(parameter.width), layers("Verification"))

/** The external-IP formal-CRV example: the constraint C = "after two beats the IP's SUM is 9" solves *through* the
  * imported SystemVerilog — the wrapper only counts beats and states the relation. The body assumes reset low so the
  * model tracks the testbench's post-reset run (the imported registers are init-pinned to their post-reset zero).
  */
@generator
object ExtAccumUT
    extends Generator[ExtAccumParameter, ExtAccumUTLayers, ExtAccumUTIO, ExtAccumUTProbe]
    with UT[ExtAccumParameter, ExtAccumUTIO]:
  override def moduleName(p: ExtAccumParameter): String = s"ExtAccumUT_width${p.width}"

  def architecture(parameter: ExtAccumParameter) =
    val io       = summon[Interface[ExtAccumUTIO]]
    val instance = ExtAccum.instantiate(parameter)
    instance.io.clk := io.clock
    instance.io.rst := io.reset.asBool
    instance.io.a   := io.A
    io.SUM          := instance.io.sum

    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)

    Txn.assumeResetLow(io.reset)

    // C as typed relation ∧ state semantics: after two real beats, the IP's accumulator reads 9.
    val w = Txn.window(io.A, parameter.width, 2)
    Generate(
      Sem.relation(w)(_ => true.B) && Sem.state(instance.io.sum === 9.U(parameter.width).asBits),
      "gen_sum9_through_ext_ip"
    )

    val probe = summon[ProbeInterface[ExtAccumUTProbe]]
    layer("Verification"):
      Probes.expose(probe.SUM, Bits(parameter.width), instance.io.sum)
