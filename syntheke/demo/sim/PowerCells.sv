module DemoLevelShifter #(
    parameter WIDTH = 1,
    parameter SOURCE_MILLIVOLTS = 900,
    parameter TARGET_MILLIVOLTS = 900
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

module DemoRetention #(
    parameter WIDTH = 1,
    parameter SOURCE_MILLIVOLTS = 900,
    parameter RETENTION_MILLIVOLTS = 900
) (
    input clock,
    input reset,
    input [WIDTH-1:0] in,
    input save,
    input restore,
    output [WIDTH-1:0] out,
    output saved,
    output restored
);
  wire retentionClock;
  wire [WIDTH-1:0] retentionInput;
  wire saveRequest;
  wire restoreRequest;
  reg [WIDTH-1:0] shadow;
  reg saveAck;
  reg restoreAck;
  reg valid;

  DemoLevelShifter #(
      .WIDTH(1), .SOURCE_MILLIVOLTS(SOURCE_MILLIVOLTS), .TARGET_MILLIVOLTS(RETENTION_MILLIVOLTS)
  ) clockToRetention (.in(clock), .out(retentionClock));
  DemoLevelShifter #(
      .WIDTH(WIDTH), .SOURCE_MILLIVOLTS(SOURCE_MILLIVOLTS), .TARGET_MILLIVOLTS(RETENTION_MILLIVOLTS)
  ) dataToRetention (.in(in), .out(retentionInput));
  DemoLevelShifter #(
      .WIDTH(1), .SOURCE_MILLIVOLTS(SOURCE_MILLIVOLTS), .TARGET_MILLIVOLTS(RETENTION_MILLIVOLTS)
  ) saveToRetention (.in(save), .out(saveRequest));
  DemoLevelShifter #(
      .WIDTH(1), .SOURCE_MILLIVOLTS(SOURCE_MILLIVOLTS), .TARGET_MILLIVOLTS(RETENTION_MILLIVOLTS)
  ) restoreToRetention (.in(restore), .out(restoreRequest));
  DemoLevelShifter #(
      .WIDTH(WIDTH), .SOURCE_MILLIVOLTS(RETENTION_MILLIVOLTS), .TARGET_MILLIVOLTS(SOURCE_MILLIVOLTS)
  ) dataToCpu (.in(shadow), .out(out));
  DemoLevelShifter #(
      .WIDTH(1), .SOURCE_MILLIVOLTS(RETENTION_MILLIVOLTS), .TARGET_MILLIVOLTS(SOURCE_MILLIVOLTS)
  ) savedToCpu (.in(saveAck), .out(saved));
  DemoLevelShifter #(
      .WIDTH(1), .SOURCE_MILLIVOLTS(RETENTION_MILLIVOLTS), .TARGET_MILLIVOLTS(SOURCE_MILLIVOLTS)
  ) restoredToCpu (.in(restoreAck), .out(restored));

  always @(posedge retentionClock or posedge reset) begin
    if (reset) begin
      shadow <= '0;
      valid <= 1'b0;
      saveAck <= 1'b0;
      restoreAck <= 1'b0;
    end else begin
      saveAck <= saveRequest && !restoreRequest;
      restoreAck <= restoreRequest && valid && !saveRequest;
      if (saveRequest && !restoreRequest) begin
        shadow <= retentionInput;
        valid <= 1'b1;
      end
    end
  end
endmodule
