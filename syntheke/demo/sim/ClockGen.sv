// SPDX-License-Identifier: Apache-2.0
// The behavioral definition of ClockGen, simulation only: the clock at FREQ_HZ on a 1ns timescale, reset held through
// the first 20 edges, and a watchdog ending a simulation that never finishes on its own. The budget is a parameter
// because it is a real cost: a run that hangs is simulated to the end of it.
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
  // Half a period, counted in the picoseconds the timescale resolves: in nanoseconds alone the division truncates,
  // and a rate whose half period is not a whole number of them becomes a different rate without a word.
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
