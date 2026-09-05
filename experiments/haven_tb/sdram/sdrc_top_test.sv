// Template-generated
class sdrc_top_test extends uvm_test;
  `uvm_component_utils(sdrc_top_test)

  sdrc_top_env m_env;

  function new(string name = "sdrc_top_test", uvm_component parent = null);
    super.new(name, parent);
  endfunction

  function void build_phase(uvm_phase phase);
    super.build_phase(phase);
    m_env = sdrc_top_env::type_id::create("m_env", this);
  endfunction

  task run_phase(uvm_phase phase);
    phase.raise_objection(this);
`ifdef RVPROBE_FLOW
    begin
      rvprobe_sdram_flow_seq flow;
      flow = rvprobe_sdram_flow_seq::type_id::create("flow");
      flow.start(m_env.m_wb_agent.m_sequencer);
`ifdef RVPROBE_BULK
      begin
        rvprobe_sdram_bulk_seq bulk;
        bulk = rvprobe_sdram_bulk_seq::type_id::create("bulk");
        bulk.start(m_env.m_wb_agent.m_sequencer);
      end
`endif
    end
`else
    begin : seq_1_guard
      fork : seq_1_fork
        begin
          sdrc_single_rw_basic seq_1;
          seq_1 = sdrc_single_rw_basic::type_id::create("seq_1");
          seq_1.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence sdrc_single_rw_basic timed out — moving on")
        end
      join_any
      disable seq_1_fork;
    end : seq_1_guard
    begin : seq_2_guard
      fork : seq_2_fork
        begin
          sdrc_bus_width_16_rw seq_2;
          seq_2 = sdrc_bus_width_16_rw::type_id::create("seq_2");
          seq_2.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence sdrc_bus_width_16_rw timed out — moving on")
        end
      join_any
      disable seq_2_fork;
    end : seq_2_guard
    begin : seq_3_guard
      fork : seq_3_fork
        begin
          sdrc_bus_width_8_rw seq_3;
          seq_3 = sdrc_bus_width_8_rw::type_id::create("seq_3");
          seq_3.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence sdrc_bus_width_8_rw timed out — moving on")
        end
      join_any
      disable seq_3_fork;
    end : seq_3_guard
    begin : seq_4_guard
      fork : seq_4_fork
        begin
          sdrc_partial_sel_rw seq_4;
          seq_4 = sdrc_partial_sel_rw::type_id::create("seq_4");
          seq_4.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence sdrc_partial_sel_rw timed out — moving on")
        end
      join_any
      disable seq_4_fork;
    end : seq_4_guard
    begin : seq_5_guard
      fork : seq_5_fork
        begin
          sdrc_colbits_0_rw seq_5;
          seq_5 = sdrc_colbits_0_rw::type_id::create("seq_5");
          seq_5.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence sdrc_colbits_0_rw timed out — moving on")
        end
      join_any
      disable seq_5_fork;
    end : seq_5_guard
    begin : seq_6_guard
      fork : seq_6_fork
        begin
          sdrc_colbits_1_rw seq_6;
          seq_6 = sdrc_colbits_1_rw::type_id::create("seq_6");
          seq_6.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence sdrc_colbits_1_rw timed out — moving on")
        end
      join_any
      disable seq_6_fork;
    end : seq_6_guard
    begin : seq_7_guard
      fork : seq_7_fork
        begin
          sdrc_colbits_3_rw seq_7;
          seq_7 = sdrc_colbits_3_rw::type_id::create("seq_7");
          seq_7.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence sdrc_colbits_3_rw timed out — moving on")
        end
      join_any
      disable seq_7_fork;
    end : seq_7_guard
    begin : seq_8_guard
      fork : seq_8_fork
        begin
          sdrc_page_hit seq_8;
          seq_8 = sdrc_page_hit::type_id::create("seq_8");
          seq_8.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence sdrc_page_hit timed out — moving on")
        end
      join_any
      disable seq_8_fork;
    end : seq_8_guard
    begin : seq_9_guard
      fork : seq_9_fork
        begin
          sdrc_page_miss seq_9;
          seq_9 = sdrc_page_miss::type_id::create("seq_9");
          seq_9.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence sdrc_page_miss timed out — moving on")
        end
      join_any
      disable seq_9_fork;
    end : seq_9_guard
    begin : seq_10_guard
      fork : seq_10_fork
        begin
          sdrc_bank_coverage seq_10;
          seq_10 = sdrc_bank_coverage::type_id::create("seq_10");
          seq_10.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence sdrc_bank_coverage timed out — moving on")
        end
      join_any
      disable seq_10_fork;
    end : seq_10_guard
    begin : seq_11_guard
      fork : seq_11_fork
        begin
          sdrc_bank_interleave seq_11;
          seq_11 = sdrc_bank_interleave::type_id::create("seq_11");
          seq_11.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence sdrc_bank_interleave timed out — moving on")
        end
      join_any
      disable seq_11_fork;
    end : seq_11_guard
    begin : seq_12_guard
      fork : seq_12_fork
        begin
          sdrc_refresh seq_12;
          seq_12 = sdrc_refresh::type_id::create("seq_12");
          seq_12.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence sdrc_refresh timed out — moving on")
        end
      join_any
      disable seq_12_fork;
    end : seq_12_guard
    begin : seq_13_guard
      fork : seq_13_fork
        begin
          sdrc_back_to_back seq_13;
          seq_13 = sdrc_back_to_back::type_id::create("seq_13");
          seq_13.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence sdrc_back_to_back timed out — moving on")
        end
      join_any
      disable seq_13_fork;
    end : seq_13_guard
    begin : seq_14_guard
      fork : seq_14_fork
        begin
          sdrc_random_stress seq_14;
          seq_14 = sdrc_random_stress::type_id::create("seq_14");
          seq_14.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence sdrc_random_stress timed out — moving on")
        end
      join_any
      disable seq_14_fork;
    end : seq_14_guard
    begin : seq_15_guard
      fork : seq_15_fork
        begin
          seq_toggle_sweep seq_15;
          seq_15 = seq_toggle_sweep::type_id::create("seq_15");
          seq_15.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence seq_toggle_sweep timed out — moving on")
        end
      join_any
      disable seq_15_fork;
    end : seq_15_guard
    begin : seq_16_guard
      fork : seq_16_fork
        begin
          sdrc_top_sync_fifo_reset_seq seq_16;
          seq_16 = sdrc_top_sync_fifo_reset_seq::type_id::create("seq_16");
          seq_16.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence sdrc_top_sync_fifo_reset_seq timed out — moving on")
        end
      join_any
      disable seq_16_fork;
    end : seq_16_guard
    begin : seq_17_guard
      fork : seq_17_fork
        begin
          sdrc_top_sync_fifo_basic_ops_seq seq_17;
          seq_17 = sdrc_top_sync_fifo_basic_ops_seq::type_id::create("seq_17");
          seq_17.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence sdrc_top_sync_fifo_basic_ops_seq timed out — moving on")
        end
      join_any
      disable seq_17_fork;
    end : seq_17_guard
    begin : seq_18_guard
      fork : seq_18_fork
        begin
          sdrc_top_sync_fifo_full_seq seq_18;
          seq_18 = sdrc_top_sync_fifo_full_seq::type_id::create("seq_18");
          seq_18.start(m_env.m_wb_agent.m_sequencer);
        end
        begin
          #(100_000_000);  // 100ms timeout per sequence
          `uvm_warning("SEQ_TIMEOUT", "Sequence sdrc_top_sync_fifo_full_seq timed out — moving on")
        end
      join_any
      disable seq_18_fork;
    end : seq_18_guard
`endif
    #1000;
    phase.drop_objection(this);
  endtask
endclass
