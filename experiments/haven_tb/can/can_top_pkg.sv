package can_top_pkg;
  import uvm_pkg::*;
  `include "uvm_macros.svh"

  // Components (dependency order)
  `include "can_top_seq_item.sv"
  `include "can_top_wb_agent__sequencer.sv"
  `include "can_top_wb_agent__driver.sv"
  `include "can_top_wb_agent__monitor.sv"
  `include "can_top_wb_agent__subscriber.sv"
  `include "can_top_wb_agent__agent.sv"
  `include "can_top_can_agent__driver.sv"
  `include "can_top_can_agent__monitor.sv"
  `include "can_top_can_agent__subscriber.sv"
  `include "can_top_can_agent__agent.sv"
  `include "can_top_status_agent__monitor.sv"
  `include "can_top_status_agent__subscriber.sv"
  `include "can_top_status_agent__agent.sv"
  `include "can_top_subscriber.sv"
  `include "can_top_scoreboard.sv"
  `include "can_top_env.sv"

  // Sequences
  // `include "sequence_1.sv"  // rvprobe: HAVEN's generated sequences do not compile
  // `include "sequence_2.sv"  // rvprobe: HAVEN's generated sequences do not compile
  // `include "sequence_3.sv"  // rvprobe: HAVEN's generated sequences do not compile
  // `include "sequence_4.sv"  // rvprobe: HAVEN's generated sequences do not compile
  // `include "sequence_5.sv"  // rvprobe: HAVEN's generated sequences do not compile
  // `include "sequence_6.sv"  // rvprobe: HAVEN's generated sequences do not compile

  // Test (last — uses sequences)
  `include "rvprobe_can_flow_seq.sv"
  `include "can_top_test.sv"
endpackage
