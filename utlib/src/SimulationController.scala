// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.magic.macros.generator
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

final case class SimulationControllerParameter(
  timeoutCycles: Int,
  trace:         Boolean,
  traceFile:     String)
    extends Parameter:
  require(timeoutCycles > 0, "simulation timeout must be positive")
  require(traceFile.nonEmpty, "simulation trace filename must not be empty")

object SimulationControllerParameter:
  given upickle.default.ReadWriter[SimulationControllerParameter] = upickle.default.macroRW

class SimulationControllerLayers(parameter: SimulationControllerParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class SimulationControllerIO(parameter: SimulationControllerParameter) extends HWBundle(parameter):
  val clock = Aligned(Clock())
  val reset = Aligned(Reset())
  val done  = Flipped(Bool())

class SimulationControllerProbe(parameter: SimulationControllerParameter)
    extends DVBundle[SimulationControllerParameter, SimulationControllerLayers](parameter)

final case class SimulationControllerVerilogParameter(
  timeoutCycles: Int,
  trace:         Boolean,
  traceFile:     String)
    extends VerilogParameter

/** Verilog testbench services exposed as a Zaozi blackbox.
  *
  * The implementation owns clock/reset generation, timeout enforcement, and optional VCD dumping. A DUT-specific Zaozi
  * top only wires this controller to its harness; it never constructs Verilog source text itself.
  */
@generator
object SimulationController
    extends VerilogWrapper[
      SimulationControllerParameter,
      SimulationControllerLayers,
      SimulationControllerIO,
      SimulationControllerProbe,
      SimulationControllerVerilogParameter
    ]:
  val verilogSourceName: String = "ZaoziSimulationController.sv"

  def verilogModuleName(parameter: SimulationControllerParameter): String = "ZaoziSimulationController"

  def verilogParameter(parameter: SimulationControllerParameter): SimulationControllerVerilogParameter =
    SimulationControllerVerilogParameter(parameter.timeoutCycles, parameter.trace, parameter.traceFile)

  override def verilogSources(parameter: SimulationControllerParameter): Seq[VerilogSource] =
    Seq(VerilogSource(verilogSourceName, verilogSource))

  private val verilogSource =
    """|// SPDX-License-Identifier: Apache-2.0
       |// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
       |
       |module ZaoziSimulationController #(
       |  parameter integer timeoutCycles = 100,
       |  parameter         trace         = 1'b0,
       |  parameter string  traceFile     = "trace.vcd"
       |) (
       |  output logic clock,
       |  output logic reset,
       |  input  wire  done
       |);
       |  initial clock = 1'b0;
       |  always #5 clock = ~clock;
       |
       |  initial begin
       |    reset = 1'b1;
       |    repeat (4) @(posedge clock);
       |    reset = 1'b0;
       |  end
       |
       |  generate
       |    if (trace) begin : generate_trace
       |      initial begin
       |        $dumpfile(traceFile);
       |        $dumpvars(0);
       |      end
       |    end
       |  endgenerate
       |
       |  always @(posedge clock) begin
       |    if (!reset && done) begin
       |      $display("HARNESS-DONE");
       |      $finish;
       |    end
       |  end
       |
       |  initial begin
       |    repeat (timeoutCycles) @(posedge clock);
       |    $display("HARNESS-TIMEOUT after %0d cycles", timeoutCycles);
       |    $fatal;
       |  end
       |endmodule
       |""".stripMargin
