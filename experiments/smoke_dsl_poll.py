"""Real four-state simulation of native generated poll termination, no model."""
import argparse
from pathlib import Path
import shlex
import sys
import time

from process_runner import run
from run_records import save, utc
from cycle_replay import digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ('haven-root','out','eda-shell'):
        parser.add_argument('--'+key,type=Path,required=True)
    args = parser.parse_args()
    sys.path.insert(0,str(args.haven_root.resolve()/'src'))
    from haven.dsl.codegen import DSLCodegen
    from haven.dsl.schema import BusFieldMapping, PollStep
    out=args.out.resolve();out.mkdir(parents=True,exist_ok=False)
    bm=BusFieldMapping(addr='addr',data='data',we='we',read_data='read_data')
    step=PollStep(name='probe',addr='0',store='observed',condition="observed == 32'h59",timeout=3)
    generated='\n'.join(DSLCodegen()._render_poll(step,'probe',bm,'    '))
    bench='''`timescale 1ns/1ps
module poll_tb;
  import uvm_pkg::*;
  `include "uvm_macros.svh"
  class probe_seq_item extends uvm_sequence_item;
    `uvm_object_utils(probe_seq_item)
    logic [31:0] addr,data,read_data;
    logic we;
    function new(string name="item");super.new(name);endfunction
  endclass
  int mode=0,calls=0;
  task automatic start_item(probe_seq_item item);endtask
  task automatic finish_item(probe_seq_item item);
    calls++;
    item.read_data = mode==1 ? 'x : mode==0 && calls==3 ? 32'h59 : 32'h0;
  endtask
  task automatic poll_once();
    probe_seq_item txn;
    logic [31:0] observed;
GENERATED
  endtask
  initial begin
    uvm_report_server server;
    server=uvm_report_server::get_server();
    for(int m=0;m<3;m++) begin
      mode=m;calls=0;poll_once();
      if(calls!=3 || server.get_severity_count(UVM_ERROR)!=m)
        $fatal(1,"poll accepted unknown/mismatch or rejected final-attempt success");
    end
    $display("DSL_POLL_PROTOCOL_PASS final-attempt success, X rejection, mismatch rejection");$finish;
  end
endmodule
'''.replace('GENERATED',generated)
    (out/'poll.sv').write_text(bench)
    began=time.monotonic()
    record=dict(status='running',started_utc=utc(),remote_llm_requests=0,
                codegen_sha256=digest(args.haven_root/'src/haven/dsl/codegen.py'))
    try:
        for name,command in [('compile',['vcs','-full64','-sverilog','-ntb_opts','uvm-1.2',
                                         '-timescale=1ns/1ps','poll.sv','-top','poll_tb','-o','simv']),('sim',['./simv'])]:
            with (out/(name+'.log')).open('w') as log:
                run([str(args.eda_shell.resolve()),'-c',shlex.join(command)],cwd=out,
                    stdout=log,stderr=-2,timeout=120,check=True)
        if 'DSL_POLL_PROTOCOL_PASS' not in (out/'sim.log').read_text():
            raise ValueError('missing poll regression pass marker')
        record['status']='passed'
    except Exception as error:record.update(status='failed',error=str(error))
    record.update(finished_utc=utc(),elapsed_seconds=time.monotonic()-began)
    save(out/'summary.json',record)
    return int(record['status']!='passed')


if __name__=='__main__':raise SystemExit(main())
