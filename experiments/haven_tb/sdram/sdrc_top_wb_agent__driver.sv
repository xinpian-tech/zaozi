// Template-generated Wishbone master driver — deterministic, no LLM needed
class sdrc_top_wb_agent__driver extends uvm_driver #(sdrc_top_seq_item);
  `uvm_component_utils(sdrc_top_wb_agent__driver)

  virtual sdrc_top_if vif;

  function new(string name = "sdrc_top_wb_agent__driver", uvm_component parent = null);
    super.new(name, parent);
  endfunction

  function void build_phase(uvm_phase phase);
    super.build_phase(phase);
    if (!uvm_config_db#(virtual sdrc_top_if)::get(this, "", "vif", vif))
      `uvm_fatal(get_type_name(), "Failed to get vif from config_db")
  endfunction

  task run_phase(uvm_phase phase);
    sdrc_top_seq_item item;

    // Drive idle state immediately to avoid X on bus signals
    vif.wb_cyc_i <= 1'b0;
    vif.wb_stb_i <= 1'b0;
    vif.wb_we_i  <= 1'b0;
    vif.wb_addr_i <= '0;
    vif.wb_dat_i <= '0;

    // Wait for reset deassert
    wait(vif.wb_rst_i == 1'b0);
    // Allow DUT internal state (e.g., ACK synchronizers) to settle after reset
    repeat (10) @(posedge vif.wb_clk_i);

    forever begin
      seq_item_port.get_next_item(item);
      drive_item(item);
      seq_item_port.item_done();
    end
  endtask

  task drive_item(sdrc_top_seq_item item);
    // rvprobe: the generated driver never drove the enable from the item; a flow that withdraws it needs this line.
    vif.cfg_sdr_en <= item.cfg_sdr_en;
    if (item.wb_we_i)
      wb_write(item.wb_addr_i, item.wb_dat_i);
    else begin
      logic [31:0] rdata;
      wb_read(item.wb_addr_i, rdata);
      item.wb_dat_o = rdata;
    end
  endtask

  task wb_write(input logic [25:0] addr,
                input logic [31:0] data);
    int ack_wait;
    @(posedge vif.wb_clk_i);
    vif.wb_cyc_i <= 1'b1;
    vif.wb_stb_i <= 1'b1;
    vif.wb_we_i  <= 1'b1;
    vif.wb_addr_i <= addr;
    vif.wb_dat_i <= data;
    vif.wb_sel_i <= {4{1'b1}};
    ack_wait = 0;
    do begin
      @(posedge vif.wb_clk_i);
      ack_wait++;
      if (ack_wait >= 100) begin
        `uvm_error(get_type_name(), $sformatf("WB write ACK timeout at addr 0x%0h", addr))
        break;
      end
    end while (vif.wb_ack_o !== 1'b1);
    vif.wb_cyc_i <= 1'b0;
    vif.wb_stb_i <= 1'b0;
    vif.wb_we_i  <= 1'b0;
  endtask

  task wb_read(input logic [25:0] addr,
               output logic [31:0] data);
    int ack_wait;
    @(posedge vif.wb_clk_i);
    vif.wb_cyc_i <= 1'b1;
    vif.wb_stb_i <= 1'b1;
    vif.wb_we_i  <= 1'b0;
    vif.wb_addr_i <= addr;
    vif.wb_sel_i <= {4{1'b1}};
    ack_wait = 0;
    do begin
      @(posedge vif.wb_clk_i);
      ack_wait++;
      if (ack_wait >= 100) begin
        `uvm_error(get_type_name(), $sformatf("WB read ACK timeout at addr 0x%0h", addr))
        break;
      end
    end while (vif.wb_ack_o !== 1'b1);
    data = vif.wb_dat_o;
    vif.wb_cyc_i <= 1'b0;
    vif.wb_stb_i <= 1'b0;
  endtask

endclass
