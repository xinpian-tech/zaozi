// SPDX-License-Identifier: Apache-2.0
// Synthetic partial-assignment regression: unrelated bits have independent next-state logic.
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
      result <= 0;
      if (valid) begin
        result[0] <= payload[0];
        result[1] <= (payload[7:1] == 0);
      end
    end
  end
endmodule
