// SPDX-License-Identifier: Apache-2.0
// Synthetic external RTL for code-generation regressions, not a benchmark model.
module tiny_external (
  input clk,
  input rst,
  input [7:0] payload,
  input valid,
  output reg [7:0] result,
  output reg done
);
  always @(posedge clk) begin
    if (rst) begin
      result <= 0;
      done <= 0;
    end else begin
      done <= valid;
      if (valid) result <= payload;
    end
  end
endmodule
