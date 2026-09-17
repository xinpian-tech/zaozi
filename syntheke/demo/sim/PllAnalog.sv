`timescale 1ns / 1ps
module PllAnalog #(
    parameter REF_HZ      = 25000000,
    parameter OUT_HZ      = 100000000,
    parameter MULT        = 4,
    parameter DIV         = 1,
    parameter LOCK_CYCLES = 16
) (
    input      refClock,
    input      refReset,
    output reg clock,
    output reg reset
);
  integer lock;

  initial begin
    clock = 1'b0;
    reset = 1'b1;
    lock  = 0;
    if (OUT_HZ * DIV != REF_HZ * MULT)
      $fatal(1, "[PllAnalog] %0d Hz * %0d / %0d is not %0d Hz", REF_HZ, MULT, DIV, OUT_HZ);
    if (HALF_PS * 2 * OUT_HZ != 64'd1000000000000)
      $fatal(1, "[PllAnalog] %0d Hz has no whole-picosecond period", OUT_HZ);
  end

  localparam longint HALF_PS = 64'd500000000000 / OUT_HZ;
  always #(HALF_PS * 1ps) clock = ~clock;

  always @(posedge refClock) begin
    if (refReset) lock <= 0;
    else if (lock < LOCK_CYCLES) lock <= lock + 1;
  end
  always @(posedge clock) reset <= (lock < LOCK_CYCLES);
endmodule
