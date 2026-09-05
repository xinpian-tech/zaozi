typedef enum {NONE, BIT, STUFF, CRC, FORM, ACK} error_type_e;

class can_top_seq_item extends uvm_sequence_item;
  // Inputs (rand)
  rand logic [7:0] addr;
  rand logic [7:0] wdata; // original "data" (8-bit) renamed to avoid conflict with data array
  rand bit we;
  rand bit ext;
  rand bit rtr;
  rand logic [28:0] id;
  rand bit [3:0] dlc;
  rand bit [7:0] data[8];
  rand error_type_e error_type;
  rand bit [7:0] wb_dat_i;
  rand bit [7:0] wb_adr_i;
  rand bit wb_we_i;
  rand bit rx_i;

  // Outputs (non-rand)
  logic [7:0] rdata;
  logic error_detected;
  logic bus_off;
  logic irq;
  logic clkout;
  logic [7:0] wb_dat_o;

  // Expected values for scoreboard
  logic [7:0] exp_rdata;
  logic exp_error_detected;
  logic exp_bus_off;
  logic exp_irq;
  logic exp_clkout;

  // Constraints
  constraint c_addr { addr inside {[0:31]}; }
  constraint c_dlc { dlc <= 15; }
  constraint c_id { if (ext == 0) id[28:11] == 0; }
  constraint c_error_type { error_type inside {NONE, BIT, STUFF, CRC, FORM, ACK}; }

  `uvm_object_utils(can_top_seq_item)

  function new(string name = "can_top_seq_item");
    super.new(name);
  endfunction

  function string convert2string();
    string s;
    s = $sformatf("addr=0x%0h wdata=0x%0h we=%0b ext=%0b rtr=%0b id=0x%0h dlc=%0d error_type=%s wb_dat_i=0x%0h wb_adr_i=0x%0h wb_we_i=%0b rx_i=%0b", 
                 addr, wdata, we, ext, rtr, id, dlc, error_type.name(), wb_dat_i, wb_adr_i, wb_we_i, rx_i);
    s = {s, $sformatf("\n  data = {")};
    foreach (data[i]) s = {s, $sformatf("0x%0h ", data[i])};
    s = {s, "}"};
    s = {s, $sformatf("\n  rdata=0x%0h error_detected=%0b bus_off=%0b irq=%0b clkout=%0b", rdata, error_detected, bus_off, irq, clkout)};
    s = {s, $sformatf("\n  exp_rdata=0x%0h exp_error_detected=%0b exp_bus_off=%0b exp_irq=%0b exp_clkout=%0b", exp_rdata, exp_error_detected, exp_bus_off, exp_irq, exp_clkout)};
    return s;
  endfunction
endclass
