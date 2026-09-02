// SPDX-License-Identifier: Apache-2.0
// The behavioral definition of SimConsole, simulation only: each byte goes to stdout, a newline ends the run.
`timescale 1ns / 1ps
module SimConsole (
    input       clock,
    input       valid,
    input [7:0] data
);
  always @(posedge clock) begin
    if (valid) begin
      $write("%c", data);
      $fflush;
      if (data == 8'h0A) $finish;
    end
  end
endmodule
