// Template-generated
class can_top_test extends uvm_test;
  `uvm_component_utils(can_top_test)

  can_top_env m_env;

  function new(string name = "can_top_test", uvm_component parent = null);
    super.new(name, parent);
  endfunction

  function void build_phase(uvm_phase phase);
    super.build_phase(phase);
    m_env = can_top_env::type_id::create("m_env", this);
  endfunction

  task run_phase(uvm_phase phase);
    phase.raise_objection(this);
`ifdef RVPROBE_FLOW
    begin
      rvprobe_can_flow_seq flow;
      flow = rvprobe_can_flow_seq::type_id::create("flow");
      flow.start(m_env.m_wb_agent.m_sequencer);
    end
`endif
    #1000;
    phase.drop_objection(this);
  endtask
endclass
