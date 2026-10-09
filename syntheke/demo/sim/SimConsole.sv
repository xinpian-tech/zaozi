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
