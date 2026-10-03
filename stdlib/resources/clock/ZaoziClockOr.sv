// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

module ZaoziClockOr(input wire a, b, output wire outClock);
  assign outClock = a | b;
endmodule
