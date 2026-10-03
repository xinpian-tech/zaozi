// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

module ZaoziClockMux(input wire a, b, input wire select, output wire outClock);
  assign outClock = select ? b : a;
endmodule
