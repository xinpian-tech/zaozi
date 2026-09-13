`timescale 1ns/1ps
module i2c_slave_protocol_tb;
  bit clk=0, rst=1, scl=1, low=0;
  tri1 sda;
  assign sda = low ? 1'b0 : 1'bz;
  always #5 clk=~clk;
  i2c_slave_bfm dut(.clk(clk),.rst(rst),.sda(sda),.scl(scl));
  task start_bus();
    scl=0; low=0; #100; scl=1; #100; low=1; #100; scl=0; #100;
  endtask
  task stop_bus();
    scl=0; low=1; #100; scl=1; #100; low=0; #100;
  endtask
  task write_byte(input bit [7:0] value, input bit want_ack=1);
    for(int i=7;i>=0;i--) begin
      low=!value[i]; #100; scl=1; #100; scl=0; #100;
    end
    low=0; #100; scl=1; #50;
    if (sda !== !want_ack) $fatal(1,"ACK mismatch for %h: SDA=%b",value,sda);
    #50; scl=0; #100;
  endtask
  task read_byte(input bit [7:0] expected, input bit ack);
    bit [7:0] actual;
    low=0;
    for(int i=7;i>=0;i--) begin
      #100; scl=1; #50; actual[i]=sda; #50; scl=0; #100;
    end
    if (actual !== expected) $fatal(1,"read mismatch %h != %h",actual,expected);
    low=ack; #100; scl=1; #100; scl=0; #100; low=0;
  endtask
  initial begin
    #100; rst=0; #100;
    start_bus(); write_byte(8'ha0); write_byte(8'h03);
    write_byte(8'ha5); write_byte(8'h81); stop_bus();
    start_bus(); write_byte(8'ha0); write_byte(8'h03);
    start_bus(); write_byte(8'ha1); read_byte(8'ha5,1); read_byte(8'h81,0); stop_bus();
    start_bus(); write_byte(8'ha2,0); stop_bus();
    #100; if (sda !== 1) $fatal(1,"slave did not release SDA");
    $display("I2C_SLAVE_PROTOCOL_PASS"); $finish;
  end
  initial begin #100000; $fatal(1,"protocol test timeout"); end
endmodule
