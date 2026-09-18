"""Native SVA vs known-state goto repetition, including an X-counting negative control.

Synthetic framework regression only; never model context or a DUT-specific answer.
"""
import argparse
import re
import shlex
import subprocess
from pathlib import Path
import backend_imports
from rvprobe.backend.encoding import leaves
from rvprobe.backend.process import run
from run_records import save


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args()
    out=args.out.resolve();out.mkdir(parents=True,exist_ok=False)
    original='@(posedge clock) start ##1 p[->2] ##1 finish'
    atoms=[];encoded=leaves(original,atoms)
    rails='\n'.join(f'wire rvp_encoded_atom_{i}_d = ({atom}) === 1\'b1;\n'
                    f'wire rvp_encoded_atom_{i}_x = $isunknown({atom});' for i,atom in enumerate(atoms))
    code=f'''`timescale 1ns/1ps
module tb;
reg clock=0, start=0, p=0, finish=0;
integer native_hits=0, encoded_hits=0, naive_hits=0;
always #5 clock=~clock;
{rails}
native_goal: cover property ({original}) native_hits++;
encoded_goal: cover property ({encoded}) encoded_hits++;
naive_goal: cover property (@(posedge clock) start ##1
  (rvp_encoded_atom_1_d || rvp_encoded_atom_1_x)[->2] ##1 finish) naive_hits++;
task beat(input logic s,pv,f);
  @(negedge clock); start=s; p=pv; finish=f;
endtask
initial begin
  beat(1,0,0); beat(0,1'bx,0); beat(0,0,0); beat(0,1,0);
  beat(0,0,1); beat(0,0,0);
  #1;
  if(native_hits!=0 || encoded_hits!=0 || naive_hits!=1)
    $fatal(1,"X cannot count: native=%0d encoded=%0d naive=%0d",native_hits,encoded_hits,naive_hits);
  beat(1,0,0); beat(0,0,0); beat(0,1'bx,0); beat(0,1,0);
  beat(0,0,0); beat(0,1,0); beat(0,0,1); beat(0,0,0);
  #1;
  if(native_hits!=1 || encoded_hits!=1 || naive_hits!=1)
    $fatal(1,"X can be skipped: native=%0d encoded=%0d naive=%0d",native_hits,encoded_hits,naive_hits);
  beat(1,0,0); beat(0,0,0); beat(0,1,0);
  beat(0,0,0); beat(0,1,0); beat(0,0,1); beat(0,0,0);
  #1;
  if(native_hits!=2 || encoded_hits!=2 || naive_hits!=2)
    $fatal(1,"known case: native=%0d encoded=%0d naive=%0d",native_hits,encoded_hits,naive_hits);
  $display("GOTO_PASS %0d %0d %0d",native_hits,encoded_hits,naive_hits);
  $finish;
end
endmodule
'''
    (out/'tb.sv').write_text(code)
    shell=Path(__file__).resolve().parent/'eda-shell'
    record=dict(status='running',diagnostic_only=True,remote_llm_requests=0)
    try:
        for command,name in ((['vcs','-full64','-sverilog','+vcs+lic+wait','-assert','svaext',
                'tb.sv','-top','tb','-o','simv'],'compile.log'),(['./simv'],'sim.log')):
            with (out/name).open('w') as log:
                run([str(shell),'-c',shlex.join(command)],cwd=out,stdout=log,
                    stderr=subprocess.STDOUT,check=True,timeout=300)
        match=re.search(r'GOTO_PASS (\d+) (\d+) (\d+)',(out/'sim.log').read_text())
        if not match:raise ValueError('missing goto regression receipt')
        record.update(status='passed',native_hits=int(match[1]),encoded_hits=int(match[2]),naive_hits=int(match[3]))
    except Exception as error:
        record.update(status='failed',error=str(error))
        raise
    finally:
        save(out/'summary.json',record)


if __name__=='__main__':main()
