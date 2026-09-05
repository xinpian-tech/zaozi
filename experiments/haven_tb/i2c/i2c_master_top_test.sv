// Template-generated (HAVEN); run_phase rewritten to start rvprobe's sequences.
class i2c_master_top_test extends uvm_test;
  `uvm_component_utils(i2c_master_top_test)
  i2c_master_top_env m_env;
  function new(string name = "i2c_master_top_test", uvm_component parent = null);
    super.new(name, parent);
  endfunction
  function void build_phase(uvm_phase phase);
    super.build_phase(phase);
    m_env = i2c_master_top_env::type_id::create("m_env", this);
  endfunction
  task run_phase(uvm_phase phase);
    phase.raise_objection(this);
    begin
      rvprobe_i2c_bulk_seq bulk;
      rvprobe_i2c_seq seq;
      rvprobe_i2c_poll_seq poll;
      bulk = rvprobe_i2c_bulk_seq::type_id::create("bulk");
      bulk.start(m_env.m_wb_agent.m_sequencer);
      seq = rvprobe_i2c_seq::type_id::create("seq");
      seq.start(m_env.m_wb_agent.m_sequencer);
`ifdef RVPROBE_FLOW
      begin
        rvprobe_i2c_flow_seq flow;
        flow = rvprobe_i2c_flow_seq::type_id::create("flow");
        flow.start(m_env.m_wb_agent.m_sequencer);
      end
`endif
`ifdef RVPROBE_RST
      begin
        rvprobe_i2c_rst_seq rsts;
        rsts = rvprobe_i2c_rst_seq::type_id::create("rsts");
        rsts.start(m_env.m_wb_agent.m_sequencer);
      end
`endif
      poll = rvprobe_i2c_poll_seq::type_id::create("poll");
      poll.start(m_env.m_wb_agent.m_sequencer);
    end
    #1000;
    phase.drop_objection(this);
  endtask
endclass
