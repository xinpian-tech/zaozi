package sdrc_top_pkg;
  import uvm_pkg::*;
  `include "uvm_macros.svh"

  // Components (dependency order)
  `include "sdrc_top_seq_item.sv"
  `include "sdrc_top_wb_agent__sequencer.sv"
  `include "sdrc_top_wb_agent__driver.sv"
  `include "sdrc_top_wb_agent__monitor.sv"
  `include "sdrc_top_wb_agent__subscriber.sv"
  `include "sdrc_top_wb_agent__agent.sv"
  `include "sdrc_top_sdram_agent__sequencer.sv"
  `include "sdrc_top_sdram_agent__driver.sv"
  `include "sdrc_top_sdram_agent__monitor.sv"
  `include "sdrc_top_sdram_agent__subscriber.sv"
  `include "sdrc_top_sdram_agent__agent.sv"
  `include "sdrc_top_cfg_agent__monitor.sv"
  `include "sdrc_top_cfg_agent__subscriber.sv"
  `include "sdrc_top_cfg_agent__agent.sv"
  `include "sdrc_top_subscriber.sv"
  `include "sdrc_top_scoreboard.sv"
  `include "sdrc_top_env.sv"

  // Sequences
  `include "sequence_1.sv"
  `include "sequence_2.sv"
  `include "sequence_3.sv"
  `include "sequence_4.sv"
  `include "sequence_5.sv"
  `include "sequence_6.sv"
  `include "sequence_7.sv"
  `include "sequence_8.sv"
  `include "sequence_9.sv"
  `include "sequence_10.sv"
  `include "sequence_11.sv"
  `include "sequence_12.sv"
  `include "sequence_13.sv"
  `include "sequence_14.sv"
  `include "sequence_15.sv"
  `include "sequence_16.sv"
  `include "sequence_17.sv"
  `include "sequence_18.sv"

  // Test (last — uses sequences)
  `include "rvprobe_sdram_flow_seq.sv"
  `include "rvprobe_sdram_bulk_seq.sv"
  `include "sdrc_top_test.sv"
endpackage
