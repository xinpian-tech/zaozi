`timescale 1ns/1ps
module mii_phy_protocol_tb;
  bit clk=0, rst=1, rx_event=0, tx_event=0;
  bit [3:0] txd=0, external_rxd=0;
  bit tx_en=0, tx_er=0, raw_mode=0, external_rx_dv=0, external_rx_er=0;
  wire [3:0] rxd;
  wire rx_dv,rx_er,rx_clk,tx_clk,col,crs;
  always #5 clk=~clk;
  always #20 rx_event=~rx_event;
  always #20 tx_event=~tx_event;
  mii_phy_bfm dut(.clk(clk),.rst(rst),.txd(txd),.tx_en(tx_en),.tx_er(tx_er),
    .rxd(rxd),.rx_dv(rx_dv),.rx_er(rx_er),.rx_clk(rx_clk),.tx_clk(tx_clk),.col(col),.crs(crs),
    .external_clock_mode(1'b1),.external_rx_clk(rx_event),.external_tx_clk(tx_event),
    .external_stimulus_mode(raw_mode),.external_rxd(external_rxd),
    .external_rx_dv(external_rx_dv),.external_rx_er(external_rx_er));
  int frame_nibbles=0;
  always @(posedge rx_clk) begin
    if(dut.manual_active && rx_dv) begin
      if(frame_nibbles<15 && rxd!==4'h5) $fatal(1,"preamble overwritten by loopback");
      if(frame_nibbles==15 && rxd!==4'hd) $fatal(1,"SFD mismatch");
      frame_nibbles++;
    end
  end
  initial begin
    #100; rst=0;
    @(negedge tx_clk); txd=4'ha; tx_en=1;
    repeat(3) @(posedge rx_clk); #1;
    if(rxd!==4'ha || rx_dv!==1) $fatal(1,"loopback lost");
    raw_mode=1; external_rxd=4'h3; external_rx_dv=1; external_rx_er=1; #1;
    if(rxd!==4'h3 || rx_dv!==1 || rx_er!==1) $fatal(1,"external stimulus mux failed");
    external_rx_dv=0; #1;
    if(rxd!==4'ha) $fatal(1,"external idle erased loopback");
    raw_mode=0; tx_en=0; txd=0;
    repeat(3) @(posedge rx_clk);
    dut.send_frame(48'h010203040506,16'h1234,46);
    if(frame_nibbles!=144) $fatal(1,"frame length %0d != 144",frame_nibbles);
    if(rx_clk!==rx_event || tx_clk!==tx_event) $fatal(1,"device clocks differ");
    $display("MII_PHY_PROTOCOL_PASS"); $finish;
  end
  initial begin #100000; $fatal(1,"MII protocol test timeout"); end
endmodule
