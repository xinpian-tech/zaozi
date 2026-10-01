// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

module ZaoziClockGatePositive(input wire a, input wire enable, output wire outClock);
  reg held;
  always @* if (!a) held = enable;
  assign outClock = a & held;
endmodule
