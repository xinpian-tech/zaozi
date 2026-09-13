"""Exercise the real MII boundary mux with synthetic pins; zero model calls."""
import argparse
import json
from pathlib import Path
import shlex
import time
from types import SimpleNamespace
from jinja2 import Template
from event_transport import install_event_transport
from environment_policy import derive_policy
from sequence_framework import Port
from process_runner import run
from run_records import save, utc
from cycle_replay import digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('haven-root','out','eda-shell'): parser.add_argument('--'+name,type=Path,required=True)
    args = parser.parse_args()
    out = args.out.resolve(); out.mkdir(parents=True,exist_ok=False)
    widths = {k:4 if k in ('rxd','txd') else 1 for k in ('rxd','rx_dv','rx_er','rx_clk','tx_clk','col','crs','txd','tx_en','tx_er')}
    ports = [Port('clock','input',1,'clock'),Port('reset','input',1)] + [
        Port(k,'output' if k in ('txd','tx_en','tx_er') else 'input',w,'clock' if k in ('rx_clk','tx_clk') else 'bits')
        for k,w in widths.items()]
    design = SimpleNamespace(top='probe',clock='clock',reset='reset',reset_active_low=False,
                             ports=ports,data_ports=ports[2:],parameters={})
    mapping = {k:k for k in widths}
    clocks = [{'port':'clock','period_ps':10000},{'port':'rx_clk','period_ps':8000},{'port':'tx_clk','period_ps':8000}]
    bp = {'bfm_configs':[{'protocol':'mii_phy','signals':mapping}], 'clock_schedule':clocks,
          'environment_contract':{'version':'pin-ownership-v1','bfm_owned':{
              k:'bfm:mii_phy' for k in widths if k not in ('txd','tx_en','tx_er')}}}
    decl = '\n'.join(f'wire [{w-1}:0] {k};' for k,w in widths.items())
    components = {'seq_item':'class item; endclass',
        'interface':'interface probe_if(input clock,reset);\n'+decl+'\nendinterface',
        'driver':'class driver; task run(); forever begin seq_item_port.get_next_item(item); end endtask endclass',
        'top':'''module top; import uvm_pkg::*;
          logic clk=0, reset=1;
          always #5 clk=~clk;
          probe_if vif(clk,reset);
          probe u_dut(.clock(clk),.reset(reset),'''+','.join(f'.{k}(vif.{k})' for k in widths)+''');
          mii_phy_bfm u_mii_phy_bfm(.clk(clk),.rst(reset),'''+','.join(f'.{k}(vif.{k})' for k in widths)+''',
            .external_clock_mode(0), .external_rx_clk(0), .external_tx_clk(0),
            .external_stimulus_mode(0), .external_rxd(0), .external_rx_dv(0), .external_rx_er(0));
          initial begin run_test("test"); end
        endmodule'''}
    fixed = install_event_transport(components,design,{'environment':{'version':'shared-event-environment-v1','clocks':clocks}},'driver',derive_policy(bp))
    if fixed['top'].count('.external_clock_mode(')!=1:
        raise ValueError('duplicate native adapter connection')
    # This fixture tests wiring and clock control, not benchmark stimulus.
    stimulus = '''
      integer native_edges=0, raw_edges=0;
      always @(posedge rvp_pin_rx_clk) if(vif.rvp_raw_mode) raw_edges++; else native_edges++;
      initial begin
        #20; reset=0;
        #60;
        if(native_edges != 10) $fatal(1,"native BFM clock schedule");
        if(u_mii_phy_bfm.rx_clk !== u_dut.rx_clk || u_mii_phy_bfm.tx_clk !== u_dut.tx_clk)
          $fatal(1,"native device and DUT clocks differ");
        vif.rvp_raw_mode=1; vif.rvp_reset_request=1;
        vif.rvp_drive_rxd=4'h9; vif.rvp_drive_rx_dv=1; vif.rvp_drive_rx_er=0;
        #1;
        if(u_dut.rxd !== 4'h9 || u_dut.rx_dv !== 1) $fatal(1,"raw RX stimulus not routed through PHY");
        vif.rvp_reset_request=0;
        repeat(4) begin
          vif.rvp_drive_rx_clk=0; vif.rvp_drive_tx_clk=0; #4;
          vif.rvp_drive_rx_clk=1; vif.rvp_drive_tx_clk=1; #4;
          if(u_mii_phy_bfm.rx_clk !== u_dut.rx_clk || u_mii_phy_bfm.tx_clk !== u_dut.tx_clk)
            $fatal(1,"event device and DUT clocks differ");
        end
        if(raw_edges != 4) $fatal(1,"native oscillator leaked into event clock");
        vif.rvp_drive_rx_dv=0; #1;
        if(u_dut.rxd !== 4'h3 || u_dut.rx_dv !== 1) $fatal(1,"idle raw stimulus erased native loopback");
        $display("MII_TRANSPORT_PASS"); $finish;
      end
      initial begin #10000; $fatal(1,"adapter timeout"); end
    '''
    fixed['top'] = fixed['top'].replace('run_test("test");','begin end')
    fixed['top'] = fixed['top'].replace('endmodule',stimulus+'\nendmodule')
    dut = 'module probe('+','.join(f'{p.direction} wire [{p.width-1}:0] {p.name}' for p in ports)+');\nassign txd=4\'h3; assign tx_en=!reset; assign tx_er=0;\nendmodule'
    template = args.haven_root/'src/haven/templates/bfm/bfm_mii_phy.sv.j2'
    (out/'bfm.sv').write_text(Template(template.read_text()).render(params={}))
    (out/'probe.sv').write_text(dut)
    (out/'interface.sv').write_text(fixed['interface'])
    (out/'top.sv').write_text(fixed['top'])
    record = {'status':'running','started_utc':utc(),'new_llm_tokens':0,
              'source_sha256':{str(p):digest(p) for p in (Path(__file__),template,Path(__file__).parent/'event_transport.py')}}
    began = time.monotonic()
    try:
        for name,cmd in [('compile',['vcs','-full64','-sverilog','-ntb_opts','uvm-1.2','-timescale=1ns/1ps','bfm.sv','interface.sv','probe.sv','top.sv','-top','top','-o','simv']),('sim',['./simv'])]:
            with (out/(name+'.log')).open('w') as log:
                run([str(args.eda_shell.resolve()),'-c',shlex.join(cmd)],cwd=out,stdout=log,stderr=-2,timeout=300,check=True)
        if 'MII_TRANSPORT_PASS' not in (out/'sim.log').read_text(): raise ValueError('missing adapter pass marker')
        record['status']='passed'
    except Exception as error: record.update(status='failed',error=str(error))
    record.update(finished_utc=utc(),elapsed_seconds=time.monotonic()-began)
    save(out/'summary.json',record); print(json.dumps(record))
    return int(record['status']!='passed')


if __name__=='__main__': raise SystemExit(main())
