"""VCS concurrency regression for shared item dispatch (synthetic, no model)."""
import backend_imports
import argparse
from pathlib import Path
import shlex
import sys
from rvprobe.backend.process import run


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--haven-root',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    args = p.parse_args()
    sys.path.insert(0,str(args.haven_root.resolve()/'src'))
    from haven.utils.agent_dispatch import install_dispatch
    bp = {'module_name':'probe','sequence_dispatch':{'version':'broadcast-disjoint-pins-v1',
          'primary':'bus','secondary':['side']},'topology':{'agents':[
          {'name':'bus','input_signals':['addr']},{'name':'side','input_signals':['side_i']}]}}
    item = 'class probe_seq_item;\n bit [7:0] addr;\n bit side_i;\n function new(string name="item"); endfunction\nendclass'
    stub = '''class test_port;
      mailbox #(probe_seq_item) requests = new;
      mailbox #(bit) done = new;
      task get_next_item(output probe_seq_item item); requests.get(item); endtask
      task item_done(); done.put(1); endtask
    endclass'''
    def driver(name,delay):
        return f'''class {name};
          test_port seq_item_port = new;
          int received = 0;
          task run();
            probe_seq_item item;
            forever begin
              seq_item_port.get_next_item(item);
              #{delay};
              if (item.addr != received || item.side_i != (received % 2)) $fatal(1,"dispatch lost fields");
              received++;
              seq_item_port.item_done();
            end
          endtask
        endclass'''
    fixed = install_dispatch({'seq_item':item,'bus__driver':driver('bus_driver',2),
                               'side__driver':driver('side_driver',5)},bp)
    tb = '''module tb;
      bus_driver bus = new;
      side_driver side = new;
      initial begin
        fork bus.run(); side.run(); join_none
        for (int i=0;i<10;i++) begin
          probe_seq_item item;
          bit done;
          item = new;
          item.addr = i;
          item.side_i = i%2;
          bus.seq_item_port.requests.put(item);
          bus.seq_item_port.done.get(done);
          if (side.received != i+1 || bus.received != i+1) $fatal(1,"primary completed before secondary");
        end
        if ($time != 50) $fatal(1,"drivers were serialized instead of concurrent");
        $display("AGENT_DISPATCH_PASS 10"); $finish;
      end
      initial begin #1000; $fatal(1,"dispatch deadlock"); end
    endmodule'''
    out = args.out.resolve()
    out.mkdir(parents=True,exist_ok=False)
    (out/'tb.sv').write_text('`timescale 1ns/1ps\n'+'\n'.join([
        fixed['seq_item'],stub,fixed['bus__driver'],fixed['side__driver'],tb]))
    shell = Path(__file__).resolve().parent/'eda-shell'
    with (out/'run.log').open('w') as log:
        run([str(shell),'-c',shlex.join(['vcs','-full64','-sverilog','-top','tb','tb.sv','-o','simv'])+
             ' && ./simv'],cwd=out,stdout=log,stderr=-2,check=True,timeout=180)
    if 'AGENT_DISPATCH_PASS 10' not in (out/'run.log').read_text():
        raise ValueError('dispatch regression did not complete')
    print('AGENT_DISPATCH_PASS 10')


if __name__ == '__main__': main()
