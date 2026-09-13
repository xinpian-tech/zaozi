`timescale 1ns/1ps
module sdram_model_protocol_tb;
  bit clk=0,rst=1;
  logic cs_n=1,ras_n=1,cas_n=1,we_n=1;
  logic [1:0] ba=0,dqm=3;
  logic [12:0] addr=0;
  tri [15:0] dq;
  logic drive=0;
  logic [15:0] data=0;
  assign dq=drive?data:16'bz;
  sdram_model_bfm bfm(.*);
  always #5 clk=~clk;
  task automatic command(input bit [3:0] cmd,input bit [12:0] address);
    @(negedge clk); {cs_n,ras_n,cas_n,we_n}=cmd;addr=address;
    @(posedge clk);#1;
  endtask
  initial begin
    repeat(2) @(negedge clk);rst=0;
    command(4'b0000,13'h33); // BL8, sequential, CAS3
    command(4'b0011,0); // activate row zero
    @(negedge clk); {cs_n,ras_n,cas_n,we_n}=4'b0100; addr=0;drive=1;data=16'h1234;dqm=0;
    @(posedge clk);#1;
    @(negedge clk); {cs_n,ras_n,cas_n,we_n}=4'b0111;data=16'h5678;
    @(posedge clk);#1;
    @(negedge clk);dqm=3;
    repeat(7) @(negedge clk);
    drive=0;dqm=0;
    command(4'b0101,0); // CL3 output after T2, ready to sample at T3
    command(4'b0111,0);
    command(4'b0111,0);
    if(dq !== 16'h1234) $fatal(1,"CAS latency/first SDRAM beat incorrect: %h",dq);
    command(4'b0111,0);
    if(dq !== 16'h5678) $fatal(1,"second burst beat missing: %h",dq);
    repeat(8) command(4'b0111,0);
    command(4'b0000,13'h23); // CL2, same data must be ready one clock earlier
    command(4'b0101,0);
    command(4'b0111,0);
    if(dq !== 16'h1234) $fatal(1,"CL2 first beat incorrect: %h",dq);
    command(4'b0111,0);
    if(dq !== 16'h5678) $fatal(1,"CL2 second beat incorrect: %h",dq);
    repeat(8) command(4'b0111,0);
    command(4'b0010,13'h400);
    command(4'b0011,13'd16); // would alias row zero in the old modulo-depth store
    drive=1;data=16'hABCD;dqm=0;
    command(4'b0100,0);
    dqm=3;
    repeat(8) command(4'b0111,0);
    drive=0;dqm=0;
    command(4'b0101,0);
    command(4'b0111,0);
    if(dq !== 16'hABCD) $fatal(1,"second row write/read failed: %h",dq);
    repeat(9) command(4'b0111,0);
    command(4'b0010,13'h400);
    command(4'b0011,0);
    command(4'b0101,0);
    command(4'b0111,0);
    if(dq !== 16'h1234) $fatal(1,"distinct SDRAM rows alias: %h",dq);
    $display("SDRAM_MODEL_PROTOCOL_PASS CAS2/3, BL8, masks, two-word readback, distinct rows");$finish;
  end
  initial begin #5000;$fatal(1,"SDRAM protocol regression timeout");end
endmodule
