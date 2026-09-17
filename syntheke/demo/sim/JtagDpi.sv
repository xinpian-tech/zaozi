`timescale 1ns / 1ps
module JtagDpi #(
    parameter PORT    = 5555,
    parameter TCK_DIV = 4
) (
    input      clock,
    input      reset,
    output reg tck,
    output reg tms,
    output reg tdi,
    output reg trstN,
    input      tdo
);
  import "DPI-C" function int jtag_dpi_open(input int port);
  import "DPI-C" function int jtag_dpi_step(input int tdo, output int tms, output int tdi);

  int div;
  int next_tms;
  int next_tdi;
  int taken;
  reg armed;

  initial begin
    if (jtag_dpi_open(PORT) != 0) $fatal(1, "[JtagDpi] cannot listen on port %0d", PORT);
  end

  always @(posedge clock) begin
    if (reset) begin
      div   <= 0;
      tck   <= 1'b0;
      tms   <= 1'b1;
      tdi   <= 1'b0;
      trstN <= 1'b1;
      armed <= 1'b0;
    end else if (div != TCK_DIV - 1) begin
      div <= div + 1;
    end else begin
      div <= 0;
      if (tck) begin
        tck   <= 1'b0;
        taken  = jtag_dpi_step({31'b0, tdo}, next_tms, next_tdi);
        armed <= taken != 0;
        if (taken != 0) begin
          tms <= next_tms[0];
          tdi <= next_tdi[0];
        end
      end else if (armed) begin
        tck <= 1'b1;
      end else begin
        taken  = jtag_dpi_step({31'b0, tdo}, next_tms, next_tdi);
        armed <= taken != 0;
        if (taken != 0) begin
          tms <= next_tms[0];
          tdi <= next_tdi[0];
        end
      end
    end
  end
endmodule
