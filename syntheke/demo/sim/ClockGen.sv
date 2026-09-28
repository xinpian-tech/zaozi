`timescale 1ns / 1ps
module ClockGen #(
    parameter FREQ_HZ     = 100000000,
    parameter WATCHDOG_MS = 10
) (
    output reg clock,
    output reg reset
);
  initial begin
    clock = 1'b0;
    reset = 1'b1;
    repeat (20) @(posedge clock);
    reset = 1'b0;
  end
  localparam longint HALF_PS = 64'd500000000000 / FREQ_HZ;
  initial
    if (HALF_PS * 2 * FREQ_HZ != 64'd1000000000000)
      $fatal(1, "[ClockGen] %0d Hz has no whole-picosecond period", FREQ_HZ);
  always #(HALF_PS * 1ps) clock = ~clock;
  initial begin
    #(WATCHDOG_MS * 1ms);
    $display("[ClockGen] watchdog: simulation did not finish");
    $finish;
  end
endmodule
