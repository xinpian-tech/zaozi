class sdrc_top_sdram_agent__driver extends uvm_driver #(sdrc_top_seq_item);

   `uvm_component_utils(sdrc_top_sdram_agent__driver)

   virtual sdrc_top_if vif;

   function new(string name, uvm_component parent);
      super.new(name, parent);
   endfunction

   virtual function void build_phase(uvm_phase phase);
      super.build_phase(phase);
      if(!uvm_config_db#(virtual sdrc_top_if)::get(this, "", "vif", vif))
        `uvm_fatal("NOVIF", "Virtual interface not found")
   endfunction

   virtual task run_phase(uvm_phase phase);
      sdrc_top_seq_item req;
      // Wait for reset deassert (active-high, so wait for 0)
      wait(vif.wb_rst_i == 1'b0);
      reset_signals();
      // Start reset monitor
      fork
         reset_monitor();
      join_none

      forever begin
         seq_item_port.get_next_item(req);
         drive_item(req);
         seq_item_port.item_done();
      end
   endtask

   virtual task reset_signals();
      vif.wb_stb_i <= 1'b0;
      vif.wb_cyc_i <= 1'b0;
      vif.wb_we_i  <= 1'b0;
      vif.wb_addr_i <= '0;
      vif.wb_dat_i <= '0;
      vif.wb_sel_i <= '0;
      vif.wb_cti_i <= '0;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_en   <= 1'b0;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_width <= 2'b00;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_colbits   <= 2'b00;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_mode_reg <= 13'h0000;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_tras_d   <= 4'h0;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_trp_d    <= 4'h0;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_trcd_d   <= 4'h0;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_cas      <= 3'h0;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_trcar_d  <= 4'h0;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_twr_d    <= 4'h0;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_rfsh     <= 32'h0;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_rfmax    <= 32'h0;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_req_depth    <= 2'h0;
      vif.sdram_clk        <= 1'b0;
      vif.sdram_resetn     <= 1'b0;
   endtask

   virtual task reset_monitor();
      forever begin
         @(posedge vif.wb_clk_i);
         if (vif.wb_rst_i == 1'b1) begin
            vif.wb_cyc_i <= 1'b0;
            vif.wb_stb_i <= 1'b0;
            vif.wb_we_i  <= 1'b0;
         end
      end
   endtask

   virtual task drive_item(sdrc_top_seq_item item);
      // Drive all input signals from item
      vif.wb_addr_i <= item.wb_addr_i;
      vif.wb_dat_i  <= item.wb_dat_i;
      vif.wb_sel_i  <= {28'b0, item.wb_sel_i};
      vif.wb_we_i   <= item.wb_we_i;
      vif.wb_cti_i  <= item.wb_cti_i;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_en       <= item.cfg_sdr_en;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_width    <= item.cfg_sdr_width;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_colbits      <= item.cfg_colbits;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_mode_reg <= item.cfg_sdr_mode_reg;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_tras_d   <= item.cfg_sdr_tras_d;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_trp_d    <= item.cfg_sdr_trp_d;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_trcd_d   <= item.cfg_sdr_trcd_d;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_cas      <= item.cfg_sdr_cas;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_trcar_d  <= item.cfg_sdr_trcar_d;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_twr_d    <= item.cfg_sdr_twr_d;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_rfsh     <= item.cfg_sdr_rfsh;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_sdr_rfmax    <= item.cfg_sdr_rfmax;
      // rvprobe: static configuration is owned by the top (HAVEN\'s own haven.json values); the generated agent zeroed it here.
      // vif.cfg_req_depth    <= item.cfg_req_depth;
      vif.sdram_clk        <= item.sdram_clk;
      vif.sdram_resetn     <= item.sdram_resetn;

      // Perform Wishbone transfer
      if (item.wb_we_i) begin
         wb_write(item.wb_addr_i, item.wb_dat_i);
      end else begin
         wb_read(item.wb_addr_i, item.exp_wb_dat_o);
      end
   endtask

   task wb_write(input logic [25:0] addr, input logic [31:0] data);
      @(posedge vif.wb_clk_i);
      vif.wb_cyc_i <= 1'b1;
      vif.wb_stb_i <= 1'b1;
      vif.wb_we_i  <= 1'b1;
      vif.wb_addr_i <= addr;
      vif.wb_dat_i <= data;
      do @(posedge vif.wb_clk_i); while (!vif.wb_ack_o);
      vif.wb_cyc_i <= 1'b0;
      vif.wb_stb_i <= 1'b0;
      vif.wb_we_i  <= 1'b0;
   endtask

   task wb_read(input logic [25:0] addr, output logic [31:0] data);
      @(posedge vif.wb_clk_i);
      vif.wb_cyc_i <= 1'b1;
      vif.wb_stb_i <= 1'b1;
      vif.wb_we_i  <= 1'b0;
      vif.wb_addr_i <= addr;
      do @(posedge vif.wb_clk_i); while (!vif.wb_ack_o);
      data = vif.wb_dat_o;
      vif.wb_cyc_i <= 1'b0;
      vif.wb_stb_i <= 1'b0;
   endtask

   virtual function void end_of_elaboration_phase(uvm_phase phase);
      if (vif == null)
        `uvm_fatal("NOVIF", "Virtual interface is null")
   endfunction

endclass