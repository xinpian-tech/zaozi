// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

module ZaoziClockInverter(input wire a, output wire outClock);
  assign outClock = ~a;
endmodule
