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
  always #(500000000 / FREQ_HZ) clock = ~clock;
  initial begin
    #(WATCHDOG_MS * 1ms);
    $display("[ClockGen] watchdog: simulation did not finish");
    $finish;
  end
endmodule
