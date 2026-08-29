// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.{DVBundle, HWBundle, LayerInterface, Parameter, VerilogParameter, VerilogWrapper}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.{Bool, Clock, UInt}
import upickle.default.ReadWriter

/** Where a hart's trace ends up: one line per retired instruction on the simulator's stdout. Writing a file is not
  * something RTL does, so this is an external Verilog module — [[traceLogModel]] is its behavioral definition — and it
  * is clocked by the hart's own clock, which the harness takes as an ordinary negotiated edge.
  *
  * It logs the commit and the register write; the rest of the trace reaches the harness too, for whatever else wants to
  * read it.
  */

case class TraceLogP(hart: String, xlen: Int, regIndexBits: Int) extends Parameter derives ReadWriter:
  require(hart.nonEmpty, "a trace log is named after the hart it follows")
  require(xlen > 0, s"xlen $xlen must be positive")
  require(regIndexBits > 0, s"register index width $regIndexBits must be positive")

class TraceLogPLayers(p: TraceLogP) extends LayerInterface(p):
  def layers = Seq.empty
class TraceLogPProbe(p: TraceLogP)  extends DVBundle[TraceLogP, TraceLogPLayers](p)
class TraceLogIO(p: TraceLogP)      extends HWBundle(p):
  val clock   = Flipped(Clock())
  val valid   = Flipped(Bool())
  val pc      = Flipped(UInt(p.xlen))
  val instr   = Flipped(UInt(p.xlen))
  val rdWe    = Flipped(Bool())
  val rd      = Flipped(UInt(p.regIndexBits))
  val rdWdata = Flipped(UInt(p.xlen))

case class TraceLogVerilogP(HART: String) extends VerilogParameter

@generator
object TraceLog extends VerilogWrapper[TraceLogP, TraceLogPLayers, TraceLogIO, TraceLogPProbe, TraceLogVerilogP]:
  def verilogModuleName(p: TraceLogP) = "TraceLog"
  def verilogParameter(p:  TraceLogP) = TraceLogVerilogP(p.hart)

/** The behavioral definition of [[TraceLog]], simulation only: one file per hart, so the log does not shred whatever
  * the design itself is printing.
  */
val traceLogModel: String = """`timescale 1ns / 1ps
module TraceLog #(
    parameter HART = "hart"
) (
    input        clock,
    input        valid,
    input [31:0] pc,
    input [31:0] instr,
    input        rdWe,
    input [3:0]  rd,
    input [31:0] rdWdata
);
  integer fd;

  initial begin
    fd = $fopen($sformatf("trace-%0s.log", HART), "w");
    if (fd == 0) $fatal(1, "[TraceLog] cannot open the trace file for %0s", HART);
  end

  always @(posedge clock) begin
    if (valid) begin
      if (rdWe) $fwrite(fd, "%08x: %08x  x%0d <- %08x\n", pc, instr, rd, rdWdata);
      else $fwrite(fd, "%08x: %08x\n", pc, instr);
    end
  end

  final $fclose(fd);
endmodule
"""
