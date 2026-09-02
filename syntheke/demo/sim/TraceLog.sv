// SPDX-License-Identifier: Apache-2.0
// The behavioral definition of TraceLog, simulation only: one line per retired instruction into trace-<HART>.log —
// a file, not stdout, which the console is using.
`timescale 1ns / 1ps
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
