`timescale 1ns/1ps
`ifndef ADDRESS_LSB
`define ADDRESS_LSB 0
`endif
interface probe_if(input logic clk,rst);
  logic [7:0] addr,data,read_data;
  logic cyc,stb,we,ack,err,rty,sel;
endinterface
package probe_pkg;
  import uvm_pkg::*;
  `include "uvm_macros.svh"
  class probe_seq_item extends uvm_sequence_item;
    bit [7:0] addr,data,read_data;
    bit we,sel;
  endclass
  `include "driver.sv"
endpackage
module wishbone_driver_protocol_tb;
  import uvm_pkg::*;
  import probe_pkg::*;
  bit clk=0,rst=0;
  int mode=1;
  probe_if vif(clk,rst);
  probe_driver driver;
  always #5 clk=~clk;
  assign vif.ack=vif.cyc && vif.stb && mode==1;
  assign vif.err=vif.cyc && vif.stb && mode==2;
  assign vif.rty=vif.cyc && vif.stb && mode==3;
  assign vif.read_data=8'h5a;
  initial begin
    logic [7:0] data;
    probe_seq_item item;
    uvm_report_server server;
    server=uvm_report_server::get_server();
    driver=new("driver",null); driver.vif=vif;
    for(int i=1;i<=3;i++) begin
      mode=i; driver.wb_write(8'hf4,8'h34); driver.wb_read(8'hf4,data);
      if(vif.addr !== (8'hf4 >> `ADDRESS_LSB)) $fatal(1,"logical address was truncated before physical conversion");
      if(data!==8'h5a) $fatal(1,"read data not propagated");
    end
    if(server.get_severity_count(UVM_ERROR)!=0) $fatal(1,"valid termination reported as error");
    mode=1;
    item=new(); item.addr=8'hf4; item.data=8'h34; item.sel=0; item.we=1;
    driver.drive_item(item);
    if(vif.sel!==1'b0) $fatal(1,"write byte select ignored");
    item.we=0; driver.drive_item(item);
    if(vif.sel!==1'b0) $fatal(1,"read byte select ignored");
    item.sel=1; driver.drive_item(item);
    if(vif.sel!==1'b1) $fatal(1,"read byte select lost");
    mode=0; driver.wb_write(8'h12,8'h34);
    if(server.get_severity_count(UVM_ERROR)!=1) $fatal(1,"missing timeout error");
    $display("WISHBONE_DRIVER_PROTOCOL_PASS"); $finish;
  end
  initial begin #10000; $fatal(1,"driver did not terminate"); end
endmodule
