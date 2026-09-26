// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

module ZaoziClockBuffer(input wire a, output wire outClock);
  assign outClock = a;
endmodule

module ZaoziClockInverter(input wire a, output wire outClock);
  assign outClock = ~a;
endmodule

module ZaoziClockOr(input wire a, b, output wire outClock);
  assign outClock = a | b;
endmodule

module ZaoziClockMux(input wire a, b, input wire select, output wire outClock);
  assign outClock = select ? b : a;
endmodule

module ZaoziClockXor(input wire a, b, output wire outClock);
  assign outClock = a ^ b;
endmodule

module ZaoziClockGatePositive(input wire a, input wire enable, output wire outClock);
  reg held;
  always @* if (!a) held = enable;
  assign outClock = a & held;
endmodule

module ZaoziClockGateNegative(input wire a, input wire enable, output wire outClock);
  reg held;
  always @* if (a) held = !enable;
  assign outClock = a | held;
endmodule
