`timescale 1ns / 1ps
module TraceLog #(
    parameter HART = "hart",
    parameter XLEN = 32,
    parameter REG_INDEX_BITS = 4
) (
    input        clock,
    input        reset,
    input        valid,
    input [XLEN-1:0] pc,
    input [31:0] instr,
    input        rdWe,
    input [REG_INDEX_BITS-1:0] rd,
    input [XLEN-1:0] rdWdata
);
  integer fd;

  initial begin
    fd = $fopen($sformatf("trace-%0s.log", HART), "w");
    if (fd == 0) $fatal(1, "[TraceLog] cannot open the trace file for %0s", HART);
  end

  always @(posedge clock) begin
    if (!reset && valid) begin
      if (rdWe) $fwrite(fd, "%08x: %08x  x%0d <- %08x\n", pc, instr, rd, rdWdata);
      else $fwrite(fd, "%08x: %08x\n", pc, instr);
    end
  end

  final $fclose(fd);
endmodule
