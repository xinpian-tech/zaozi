// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

module PrefixAdder_archbka_width16_jasper_top(
  input logic [15:0] A,
  input logic [15:0] B,
  input logic CI
);
  logic CO;
  logic [15:0] SUM;
  logic [16:0] expected_sum;

  PrefixAdder_archbka_width16 dut (
    .A(A),
    .B(B),
    .CI(CI),
    .CO(CO),
    .SUM(SUM)
  );

  always_comb begin
    expected_sum = {1'b0, A} + {1'b0, B} + {16'b0, CI};
    assert ({CO, SUM} == expected_sum);
  end
endmodule
