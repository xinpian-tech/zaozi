// SPDX-License-Identifier: Apache-2.0
// The behavioral definition of DramDpi: an AXI slave that holds one read and one write at a time, offers each beat to
// the model until it is taken, and answers when the model reports it done. Reads and writes are independent, so a read
// never waits behind a write the fabric issued first.
`timescale 1ns / 1ps
module DramDpi #(
    parameter CONFIG    = "dram.yaml",
    parameter BASE      = 0,
    parameter PERIOD_PS = 10000,
    parameter ID_W      = 4,
    parameter ADDR_W    = 32
) (
    input                clk_clock,
    input                clk_reset,

    input                in_aw_valid,
    output               in_aw_ready,
    input   [ID_W-1:0]   in_aw_bits_id,
    input   [ADDR_W-1:0] in_aw_bits_addr,
    input   [7:0]        in_aw_bits_len,
    input   [2:0]        in_aw_bits_size,
    input   [1:0]        in_aw_bits_burst,

    input                in_w_valid,
    output               in_w_ready,
    input   [127:0]      in_w_bits_data,
    input   [15:0]       in_w_bits_strb,
    input                in_w_bits_last,

    output               in_b_valid,
    input                in_b_ready,
    output  [ID_W-1:0]   in_b_bits_id,
    output  [1:0]        in_b_bits_resp,

    input                in_ar_valid,
    output               in_ar_ready,
    input   [ID_W-1:0]   in_ar_bits_id,
    input   [ADDR_W-1:0] in_ar_bits_addr,
    input   [7:0]        in_ar_bits_len,
    input   [2:0]        in_ar_bits_size,
    input   [1:0]        in_ar_bits_burst,

    output               in_r_valid,
    input                in_r_ready,
    output  [ID_W-1:0]   in_r_bits_id,
    output  [127:0]      in_r_bits_data,
    output  [1:0]        in_r_bits_resp,
    output               in_r_bits_last
);
  import "DPI-C" function int dram_dpi_open(input string cfg, input longint base, input longint period_ps);
  import "DPI-C" function void dram_dpi_tick();
  import "DPI-C" function int dram_dpi_write(
    input longint addr, input int d0, input int d1, input int d2, input int d3, input int strb);
  import "DPI-C" function int dram_dpi_read(input longint addr);
  import "DPI-C" function int dram_dpi_write_done();
  import "DPI-C" function int dram_dpi_read_done(output int d0, output int d1, output int d2, output int d3);

  localparam W_IDLE = 0, W_DATA = 1, W_RESP = 2;
  localparam R_IDLE = 0, R_REQ = 1, R_WAIT = 2, R_DATA = 3;

  int unsigned wstate, rstate;
  reg [63:0]   waddr, raddr;
  reg [ID_W-1:0] wid, rid;
  reg [7:0]    wlen, rlen;
  reg [7:0]    rbeat;
  reg          wheld;              // a beat stands in the buffer, not yet taken by the model
  reg [127:0]  wdata;
  reg [15:0]   wstrb;
  int unsigned wsent, wdone;       // beats handed over, and beats the model has finished
  reg [127:0]  rdata;
  int          rd0, rd1, rd2, rd3;
  int          taken;

  // A parameter carries the 32 bits of the address space this port addresses; widening it to the model's 64 must not
  // read the top bit as a sign.
  localparam longint BASE_ADDR = {32'b0, BASE[31:0]};

  initial begin
    if (dram_dpi_open(CONFIG, BASE_ADDR, PERIOD_PS) < 0)
      $fatal(1, "[DramDpi] cannot bring up the DRAM model from %s", CONFIG);
  end

  assign in_aw_ready = !clk_reset && wstate == W_IDLE;
  assign in_w_ready  = !clk_reset && wstate == W_DATA && !wheld;
  assign in_b_valid  = wstate == W_RESP && wdone == wsent;
  assign in_b_bits_id = wid;
  assign in_b_bits_resp = 2'b00;

  assign in_ar_ready = !clk_reset && rstate == R_IDLE;
  assign in_r_valid  = rstate == R_DATA;
  assign in_r_bits_id = rid;
  assign in_r_bits_data = rdata;
  assign in_r_bits_resp = 2'b00;
  assign in_r_bits_last = rbeat == rlen;

  always @(posedge clk_clock) begin
    if (clk_reset) begin
      wstate <= W_IDLE;
      rstate <= R_IDLE;
      wheld  <= 1'b0;
      wsent  <= 0;
      wdone  <= 0;
      rbeat  <= 8'b0;
    end else begin
      // ---- writes: take the address, then one beat at a time, then answer once the model has them all
      case (wstate)
        W_IDLE:
          if (in_aw_valid) begin
            waddr  <= {{(64 - ADDR_W){1'b0}}, in_aw_bits_addr};
            wid    <= in_aw_bits_id;
            wlen   <= in_aw_bits_len;
            wsent  <= 0;
            wdone  <= 0;
            wstate <= W_DATA;
          end
        W_DATA: begin
          if (wheld) begin
            taken = dram_dpi_write(waddr, wdata[31:0], wdata[63:32], wdata[95:64], wdata[127:96],
                                   {16'b0, wstrb});
            if (taken != 0) begin
              wheld <= 1'b0;
              waddr <= waddr + 64'd16;
              wsent <= wsent + 1;
              if (wsent == {24'b0, wlen}) wstate <= W_RESP;
            end
          end else if (in_w_valid) begin
            wdata <= in_w_bits_data;
            wstrb <= in_w_bits_strb;
            wheld <= 1'b1;
          end
        end
        W_RESP:
          if (in_b_valid && in_b_ready) wstate <= W_IDLE;
      endcase
      // Completions arrive whenever Ramulator finishes one; between the address and the response they are counted.
      if (wstate != W_IDLE) wdone <= wdone + dram_dpi_write_done();

      // ---- reads: one beat in flight, streamed back as the model finishes each
      case (rstate)
        R_IDLE:
          if (in_ar_valid) begin
            raddr  <= {{(64 - ADDR_W){1'b0}}, in_ar_bits_addr};
            rid    <= in_ar_bits_id;
            rlen   <= in_ar_bits_len;
            rbeat  <= 8'b0;
            rstate <= R_REQ;
          end
        R_REQ:
          if (dram_dpi_read(raddr) != 0) rstate <= R_WAIT;
        R_WAIT:
          if (dram_dpi_read_done(rd0, rd1, rd2, rd3) != 0) begin
            rdata  <= {rd3, rd2, rd1, rd0};
            rstate <= R_DATA;
          end
        R_DATA:
          if (in_r_ready) begin
            if (rbeat == rlen) begin
              rstate <= R_IDLE;
            end else begin
              rbeat  <= rbeat + 8'd1;
              raddr  <= raddr + 64'd16;
              rstate <= R_REQ;
            end
          end
      endcase

      // The DRAM's own clock runs at whatever ratio its timing implies; one bus clock is that many of its cycles.
      dram_dpi_tick();
    end
  end
endmodule
