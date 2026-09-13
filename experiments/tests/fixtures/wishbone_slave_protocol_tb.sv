`timescale 1ns/1ps
module wishbone_slave_protocol_tb;
  bit clk=0, rst=1, raw_mode=0, cyc=0, stb=0, we=0;
  logic [11:0] addr=0;
  logic [31:0] data=0, response;
  logic [3:0] sel=4'hf;
  logic ack;
  // Same author-neutral inactive-device reset mux as event_transport.
  wishbone_slave_bfm mem(.clk(clk),.rst(raw_mode ? 1'b1 : rst),.cyc_i(cyc),.stb_i(stb),
    .we_i(we),.sel_i(sel),.adr_i(addr),.dat_i(data),.dat_o(response),.ack_o(ack));
  always #5 clk=~clk;

  task automatic transfer(input bit write_en,input logic[11:0] address,
                           input logic[31:0] value,input logic[3:0] lanes,
                           output logic[31:0] read_value);
    int edges;
    @(negedge clk); cyc=1;stb=1;we=write_en;addr=address;data=value;sel=lanes;
    edges=0;
    do begin @(posedge clk);#1;edges++; if(edges>30)$fatal(1,"missing ACK");end while(ack!==1'b1);
    read_value=response;
    @(negedge clk);cyc=0;stb=0;
    @(posedge clk);#1;if(ack!==0)$fatal(1,"ACK not cleared");
  endtask

  initial begin
    logic [31:0] value;
    repeat(2) @(negedge clk);rst=0;
    if($test$plusargs("out_of_range")) mem.backdoor_write(20,32'h55);
    if($test$plusargs("unaligned")) mem.backdoor_write(1,32'h55);
    if($test$plusargs("unknown")) mem.backdoor_write(64'hx,32'h55);
    if($test$plusargs("address_truncation")) mem.backdoor_write(64'h1000,32'h55);
    mem.backdoor_write(0,32'h12345678);
    mem.backdoor_write(4,32'habcdef12);
    mem.backdoor_write(16,32'h87654321);
    @(negedge clk);raw_mode=1;cyc=1;stb=1;we=1;addr='x;data='1;
    repeat(mem.WAIT_STATES+3) begin
      @(posedge clk);#1;if(ack!==0)$fatal(1,"inactive BFM acknowledged raw DUT request");
    end
    mem.backdoor_read(0,value);if(value!==32'h12345678)$fatal(1,"inactive BFM changed memory");
    @(negedge clk);raw_mode=0;cyc=0;stb=0;
    if($test$plusargs("raw_to_native_unknown")) transfer(0,12'hx,0,4'hf,value);
    transfer(0,4,0,4'hf,value);if(value!==32'habcdef12)$fatal(1,"backdoor/bus units differ");
    transfer(1,4,32'h11223344,4'b0101,value);
    mem.backdoor_read(4,value);if(value!==32'hab22ef44)$fatal(1,"byte enables ignored");
    mem.backdoor_read(0,value);if(value!==32'h12345678)$fatal(1,"neighbor word corrupted");
    transfer(0,16,0,4'hf,value);if(value!==32'h87654321)$fatal(1,"non-power-of-two depth aliased");
    transfer(1,4,32'hffffffff,0,value);
    mem.backdoor_read(4,value);if(value!==32'hab22ef44)$fatal(1,"zero select wrote memory");
    if(mem.WAIT_STATES>0) begin
      @(negedge clk);cyc=1;stb=1;we=1;addr=0;data=0;sel=15;
      @(posedge clk);#1;
      @(negedge clk);cyc=0;stb=0;
      repeat(mem.WAIT_STATES+3) begin @(posedge clk);#1;if(ack!==0)$fatal(1,"aborted request ACKed");end
      mem.backdoor_read(0,value);if(value!==32'h12345678)$fatal(1,"aborted request wrote memory");
    end
    @(negedge clk);rst=1;
    @(posedge clk);#1;if(ack!==0)$fatal(1,"reset ACK high");
    mem.backdoor_read(4,value);if(value!==32'hab22ef44)$fatal(1,"reset erased external memory");
    $display("WISHBONE_SLAVE_PROTOCOL_PASS");$finish;
  end
  initial begin #10000;$fatal(1,"memory regression timed out");end
endmodule
