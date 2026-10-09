module DemoLevelShifter #(
    parameter WIDTH = 1
) (
    input [WIDTH-1:0] in,
    output [WIDTH-1:0] out
);
  assign out = in;
endmodule

module DemoIsolation #(
    parameter WIDTH = 1
) (
    input [WIDTH-1:0] in,
    input isolate,
    output [WIDTH-1:0] out
);
  assign out = isolate ? '0 : in;
endmodule

module DemoPowerSwitch #(
    parameter RISE_CYCLES = 1,
    parameter FALL_CYCLES = 1
) (
    input clock,
    input reset,
    input enable,
    output reg good
);
  integer count;

  always @(posedge clock or posedge reset) begin
    if (reset) begin
      good <= 1'b0;
      count <= 0;
    end else if (enable == good) begin
      count <= 0;
    end else if (enable) begin
      if (count == RISE_CYCLES - 1) begin
        good <= 1'b1;
        count <= 0;
      end else count <= count + 1;
    end else begin
      if (count == FALL_CYCLES - 1) begin
        good <= 1'b0;
        count <= 0;
      end else count <= count + 1;
    end
  end
endmodule
