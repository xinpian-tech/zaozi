"""Synthetic shared-UVM event regression. No model calls or benchmark answers."""
import argparse
import json
import os
from pathlib import Path
import sys

from sequence_framework import load_design
from event_trace import event_frames, idle_events
from event_transport import install_event_transport
from haven_shared import render_witness_sequence
from coverage_flow import HavenSimulation
from run_records import save


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--haven-root', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--solve', action='store_true', help='compile synthetic UT and obtain a fresh JG witness')
    parser.add_argument('--samples', type=int, default=1, help='attempt this many distinct witnesses for the synthetic intent')
    parser.add_argument('--response-bfm', action='store_true', help='retain a synthetic live response and reject a contradictory witness')
    parser.add_argument('--response-agent', action='store_true', help='exercise effective-interface binding for a reactive UVM agent')
    parser.add_argument('--uninitialized-output', action='store_true', help='negative synthetic RTL fixture: checked output stays X')
    parser.add_argument('--static-input', action='store_true', help='independent private raw storage and contradictory static request regression')
    parser.add_argument('--secondary-period-ps',type=int,default=6000)
    args = parser.parse_args()
    if args.response_bfm and args.response_agent:
        parser.error('choose one response fixture')
    response_mode = args.response_bfm or args.response_agent
    if args.static_input and (response_mode or args.solve or args.uninitialized_output):
        parser.error('static-input uses its own independent synthetic schedule')
    if args.uninitialized_output and (response_mode or args.solve):
        parser.error('uninitialized-output uses its own saved synthetic schedule')
    if response_mode and args.solve:
        parser.error('response fixtures use explicit synthetic schedules, not freshly solved witnesses')
    sys.path.insert(0, str(args.haven_root.resolve() / 'src'))
    from haven.utils.template_engine import TemplateEngine
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    root = Path(__file__).resolve().parent
    fixture = root / 'tests/fixtures'
    design = load_design(fixture / 'multiclock_design.json')
    if args.uninitialized_output:
        from dataclasses import replace
        design = replace(design, sources=(fixture/'multiclock_uninitialized.sv',))
    clocks = [{'port':'clock','period_ps':10000}, {'port':'c2','period_ps':args.secondary_period_ps}]
    config = {'reset_cycles':4, 'idle':{'x':0},
              'environment':{'version':'shared-event-environment-v1','clocks':clocks}}
    if args.static_input:
        from environment_contract import independent_environment
        config['environment']=independent_environment({**config['environment'],'static':{'x':1}})
    fresh_rows = None
    if args.solve:
        from sequence_framework import write_sources, parse_response
        from cycle_replay import witness_frames
        from process_runner import run
        replay = {**config,'version':1,'contract':'cycle-replay-v1',
                  'design':str(fixture/'multiclock_design.json'),'drain_cycles':1,
                  'request':{'x':1},'baseline':{'mode':'reset-only','idle_cycles':1}}
        save(out/'replay.json',replay)
        response = parse_response((fixture/'multiclock_intent.ltl').read_text())
        write_sources(out/'formal/sources',design,response)
        with (out/'formal.log').open('w') as log:
            run([sys.executable,str(root/'ut_harness.py'),str(out/'formal/sources'),
                 '--out',str(out/'formal/check'),'--replay-config',str(out/'replay.json'),
                 '--jg-time-limit','60s'],stdout=log,stderr=-2,check=True,timeout=600,
                env={**os.environ,'ZAOZI_EDA_SHELL':str(root/'eda-shell')})
        goal = json.loads((out/'formal/check/counts/goal.json').read_text())
        if goal['status'] != 'generated':
            raise ValueError('synthetic JG goal failed: '+json.dumps(goal))
        from witness_sampling import sample_goal, frozen_inputs
        _,_,job,_ = frozen_inputs(out/'formal/check',out/'replay.json')
        pool = sample_goal(job,goal,design,replay,out/'samples/counts',args.samples,20260906,'30s',root/'eda-shell')
        save(out/'sample-pool.json',pool)
        fresh_rows = [row for index,g in enumerate(pool) for row in witness_frames(design,replay,g,index)]
    bp = {'module_name':design.top, 'clock':{'port':'clock'}, 'clock_schedule':clocks, 'reset':{'name':'reset','level':'high'},
          'io_specification':{'inputs':[{'name':'c2','width':1},{'name':'x','width':1}],
                              'outputs':[{'name':'a','width':8},{'name':'b','width':8}],
                              'clocks':[{'name':'clock'},{'name':'c2'}]},
          'ref_model':{},'data_contracts':{'seq_item_fields':[]}}
    te = TemplateEngine()
    components = {k:te.render(k,bp) for k in ('interface','top','sequencer','agent','scoreboard','env')}
    components['seq_item'] = '''class clock_probe_seq_item extends uvm_sequence_item;
      `uvm_object_utils(clock_probe_seq_item)
      bit x; bit [7:0] a,b;
      function new(string name="item"); super.new(name); endfunction
    endclass'''
    components['driver'] = '''class clock_probe_driver extends uvm_driver #(clock_probe_seq_item);
      `uvm_component_utils(clock_probe_driver)
      virtual clock_probe_if vif;
      function new(string name, uvm_component parent); super.new(name,parent); endfunction
      function void build_phase(uvm_phase phase);
        super.build_phase(phase);
        if (!uvm_config_db#(virtual clock_probe_if)::get(this,"","vif",vif)) `uvm_fatal("VIF","missing")
      endfunction
      task run_phase(uvm_phase phase);
        clock_probe_seq_item item;
        forever begin
          seq_item_port.get_next_item(item);
          @(negedge vif.clock); vif.x = item.x;
          seq_item_port.item_done();
        end
      endtask
    endclass'''
    components['monitor'] = '''class clock_probe_monitor extends uvm_monitor;
      `uvm_component_utils(clock_probe_monitor)
      uvm_analysis_port #(clock_probe_seq_item) ap;
      virtual clock_probe_if vif;
      function new(string name, uvm_component parent); super.new(name,parent); ap=new("ap",this); endfunction
      function void build_phase(uvm_phase phase);
        super.build_phase(phase);
        if (!uvm_config_db#(virtual clock_probe_if)::get(this,"","vif",vif)) `uvm_fatal("VIF","missing")
      endfunction
      task run_phase(uvm_phase phase);
        forever begin
          @(posedge vif.clock);
          $display("EVENT_MONITOR %0d %b", longint'($realtime/1ps), vif.reset);
        end
      endtask
    endclass'''
    components['subscriber'] = '''class clock_probe_subscriber extends uvm_subscriber #(clock_probe_seq_item);
      `uvm_component_utils(clock_probe_subscriber)
      function new(string name, uvm_component parent); super.new(name,parent); endfunction
      function void write(clock_probe_seq_item item); endfunction
    endclass'''
    from environment_policy import derive_policy
    bfms = {}
    if args.response_bfm:
        bp['bfm_configs'] = [{'protocol':'spi_slave','signals':{'miso':'x'},'params':{}}]
        bp['environment_contract'] = {'version':'pin-ownership-v1','bfm_owned':{'x':'bfm:spi_slave'}}
        components['top'] = components['top'].replace('endmodule',
            'spi_slave_bfm u_spi_slave_bfm(.clk(clk),.rst(reset),.miso(vif.x));\nendmodule')
        bfms['bfm_spi_slave'] = '''interface spi_slave_bfm(input logic clk,rst,output wire miso);
          assign miso = rst ? 1'b0 : 1'b1;
          always @(posedge clk) $display("DEVICE_CLOCK %0d %b",longint'($realtime/1ps),rst);
        endinterface'''
    policy = derive_policy(bp if args.response_bfm else {})
    if args.response_agent:
        policy = derive_policy({'topology':{'agents':[{'name':'memory','mode':'reactive'}]},
            'environment_contract':{'version':'pin-ownership-v1','agent_owned':{'x':'memory'}}})
        components['driver'] = components['driver'].replace('vif.x = item.x;', '')
        extra_driver_classes = '''
        class clock_probe_memory_driver extends uvm_component;
          `uvm_component_utils(clock_probe_memory_driver)
          virtual clock_probe_if vif;
          function new(string name,uvm_component parent); super.new(name,parent); endfunction
          function void build_phase(uvm_phase phase);
            super.build_phase(phase);
            if (!uvm_config_db#(virtual clock_probe_if)::get(this,"","vif",vif)) `uvm_fatal("VIF","missing device interface")
          endfunction
          task run_phase(uvm_phase phase);
            fork
              forever begin vif.x = !vif.reset; @(vif.reset); end
              forever begin @(posedge vif.clock); $display("DEVICE_CLOCK %0d %b",longint'($realtime/1ps),vif.reset); end
            join
          endtask
        endclass
        class clock_probe_memory_agent extends uvm_agent;
          `uvm_component_utils(clock_probe_memory_agent)
          clock_probe_memory_driver m_driver;
          function new(string name,uvm_component parent); super.new(name,parent); endfunction
          function void build_phase(uvm_phase phase);
            super.build_phase(phase);
            m_driver = clock_probe_memory_driver::type_id::create("m_driver",this);
          endfunction
        endclass
        '''
        components['env'] = components['env'].replace('  function new',
            '  clock_probe_memory_agent m_memory;\n  function new',1).replace('super.build_phase(phase);',
            'super.build_phase(phase);\n m_memory = clock_probe_memory_agent::type_id::create("m_memory",this);',1)
    if args.static_input:
        from environment_contract import INDEPENDENT
        policy=derive_policy(bp,INDEPENDENT)
    fixed = install_event_transport(components, design, config, 'driver',policy)
    if args.response_agent:
        fixed['driver'] += extra_driver_classes
    rows = fresh_rows if fresh_rows is not None else (
        idle_events(design, config, config['reset_cycles'], 'reset') +
        event_frames(fixture / 'multiclock_event.vcd.txt', design, clocks))
    if response_mode or args.static_input:
        # Synthetic constant-response fixture, independent of benchmark data.
        a = b = 0
        previous_clocks = {name:0 for name in ('clock','c2')}
        for row in rows:
            active = row['kind']=='reset'
            row['drive']['x'] = 1 if args.static_input else int(not active)
            if active: a = b = 0
            row['expected'] = {'a':[a,255],'b':[b,255]}
            if not active:
                if row['clocks']['clock'] and not previous_clocks['clock']: a += 2
                if row['clocks']['c2'] and not previous_clocks['c2']: b += 1
            previous_clocks = dict(row['clocks'])
    for index,row in enumerate(rows): row.update(segment=0,beat=index)
    sequence = render_witness_sequence(design,rows,'multiclock_smoke')
    if args.static_input:
        # Raw transport is private nonrandom storage, populated by the renderer.
        sequence=sequence.replace('clock_probe_seq_item item;', '''clock_probe_seq_item item;
          begin
            clock_probe_seq_item probe_item = new("private_storage");
            if (probe_item.rvp_raw !== 0) `uvm_fatal("RAW_STORAGE", "native mode default wrong")
            probe_item.rvp_raw = 1;
            probe_item.rvp_drive_x = 1;
            if (!probe_item.randomize()) `uvm_fatal("RAW_STORAGE", "randomize failed")
            if (probe_item.rvp_raw !== 1 || probe_item.rvp_drive_x !== 1) `uvm_fatal("RAW_STORAGE", "private fields randomized")
            probe_item.rvp_drive_x = 0;
            if (probe_item.rvp_drive_x !== 0) `uvm_fatal("RAW_STORAGE", "request silently changed")
          end
        ''')
    eda = json.loads((root/'designs/haven_eda.json').read_text())
    eda['eda_env'] = {'shell':str(root/'eda-shell')}
    bundle = {'components':fixed,'blueprint':bp,'fingerprint':'synthetic-event-smoke',
              'bfm_components':bfms,'event_environment_policy':policy}
    from offline_validation import no_model_calls
    from coverage_flow import SimulationFailure
    try:
        with no_model_calls():
            result = HavenSimulation(bundle,design,eda,1)(out/'simulation',[sequence],rows)
    except SimulationFailure as error:
        if not args.uninitialized_output or error.diagnostics.get('kind') != 'unknown_concrete_output':
            raise
        if error.diagnostics['model_repair_allowed'] is not False:
            raise ValueError('X failure must not request model repair')
        result = {'status':'passed','negative_test':True,'unknown_output_rejected':True,
                  'new_llm_tokens':0,'diagnostics':error.diagnostics}
        save(out/'result.json',result)
        print(json.dumps({k:v for k,v in result.items() if k!='diagnostics'}))
        return
    if args.uninitialized_output:
        raise ValueError('uninitialized concrete output was accepted')
    import re
    log = (out/'simulation/sim.log').read_text()
    cover_hit = bool(re.search(r'u_dut\.goal, \d+ attempts, [1-9]\d* match', log))
    if not response_mode and not args.static_input and not cover_hit:
        raise ValueError('state replay passed but original RTL cover was not sampled')
    monitor = [(int(t),int(r)) for t,r in re.findall(r'^EVENT_MONITOR (\d+) ([01])$', log,re.M)]
    expected_monitor = []
    stamp, previous = 0, 0
    for row in rows:
        level = row['clocks'][design.clock]
        if level and not previous: expected_monitor.append((stamp+1,int(row['kind']=='reset')))
        stamp, previous = stamp+row['duration_ps'],level
    if monitor != expected_monitor:
        raise ValueError('native monitor did not observe effective DUT clock/reset')
    if response_mode:
        observed = [(int(t),int(r)) for t,r in re.findall(r'^DEVICE_CLOCK (\d+) ([01])$',log,re.M)]
        if observed != expected_monitor:
            raise ValueError('external device did not observe effective clock/reset')
        from copy import deepcopy
        bad = deepcopy(rows)
        next(row for row in bad if row['kind']=='witness')['drive']['x'] = 0
        bad_sequence = render_witness_sequence(design,bad,'contradictory_response')
        try:
            with no_model_calls():
                HavenSimulation(bundle,design,eda,1)(out/'rejected',[bad_sequence],bad)
        except ValueError:
            if 'ENVIRONMENT_WITNESS' not in (out/'rejected/sim.log').read_text():
                raise
        else:
            raise ValueError('contradictory symbolic response was accepted')
        result['live_response_checked'] = True
        result['contradictory_response_rejected'] = True
    if args.static_input:
        from copy import deepcopy
        bad=deepcopy(rows)
        bad[0]['drive']['x']=0
        bad_sequence=render_witness_sequence(design,bad,'contradictory_static')
        try:
            with no_model_calls():
                HavenSimulation(bundle,design,eda,1)(out/'rejected',[bad_sequence],bad)
        except SimulationFailure as error:
            if error.diagnostics.get('kind')!='candidate_environment_violation': raise
            log=(out/'rejected/sim.log').read_text()
            if 'RVPROBE_SAMPLE' in log:
                raise ValueError('contradictory request reached DUT sampling')
            result['static_input_regression']={'private_storage_checked':True,'explicit_request_preserved':True,
                'rejected_before_drive':True,'classification':error.diagnostics['kind']}
        else:
            raise ValueError('contradictory static input was accepted')
    result['original_rtl_cover_hit'] = cover_hit
    result['effective_monitor_checked'] = True
    save(out/'result.json',result)
    print(json.dumps({'status':'passed','replay':result['replay']},indent=2))


if __name__ == '__main__': main()
