"""VCS regression for procedural-if versus ternary X semantics in encoding."""
import backend_imports
import argparse
from pathlib import Path
import re
import shlex
import subprocess
import time

from rvprobe.backend.process import run
from run_records import save, utc

RTL = """module branch_probe(input clock, reset, we, input [1:0] cfg,
output reg [3:0] count, ternary_count);
reg [1:0] setting;
always @(posedge clock) if (we) setting <= cfg;
always @(posedge clock) begin
  if (reset) count <= 0;
  else if (count >= setting) count <= 0;
  else count <= count + 1'b1;
end
always @(posedge clock) begin
  if (reset) ternary_count <= 0;
  else ternary_count <= count >= setting ? 0 : count + 1'b1;
end
endmodule
"""
TB = """`timescale 1ns/1ps
module tb;
reg clock=0, reset=1, we=0;
reg [1:0] cfg=0;
wire [3:0] count, ternary_count;
wire [3:0] correct_d, correct_x, ternary_d, ternary_x;
wire [3:0] old_d, old_x;
integer samples=0, old_differences=0, b;
branch_probe native(clock, reset, we, cfg, count, ternary_count);
branch_ifx exact(.clock(clock), .reset(reset), .we(we), .cfg(cfg),
  .count_d(correct_d), .count_x(correct_x), .ternary_count_d(ternary_d), .ternary_count_x(ternary_x));
branch_default old(.clock(clock), .reset(reset), .we(we), .cfg(cfg), .count_d(old_d), .count_x(old_x));
always #5 clock=~clock;
always @(posedge clock) begin
  #1;
  for(b=0;b<4;b=b+1) begin
    if (correct_x[b] !== $isunknown(count[b])) $fatal(1,"procedural mask mismatch");
    if (!correct_x[b] && correct_d[b] !== count[b]) $fatal(1,"procedural value mismatch");
    if (ternary_x[b] !== $isunknown(ternary_count[b])) $fatal(1,"ternary mask mismatch");
    if (!ternary_x[b] && ternary_d[b] !== ternary_count[b]) $fatal(1,"ternary value mismatch");
    if (old_x[b] !== $isunknown(count[b])) old_differences=old_differences+1;
  end
  samples=samples+1;
end
initial begin
  repeat(3) @(negedge clock);
  reset=0;
  repeat(8) @(negedge clock);
  we=1; cfg=2;
  @(negedge clock); we=0;
  repeat(12) @(negedge clock);
  if(old_differences == 0) $fatal(1,"negative control failed to distinguish proc modes");
  $display("IFX_PASS %0d DEFAULT_DIFFERENCES %0d",samples,old_differences);
  $finish;
end
endmodule
"""


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--yosys',type=Path,required=True)
    args=p.parse_args()
    out=args.out.resolve(); out.mkdir(exist_ok=False)
    began=time.monotonic()
    record=dict(status='running',diagnostic_only=True,remote_llm_requests=0,started_utc=utc())
    try:
        (out/'native.sv').write_text(RTL)
        (out/'tb.sv').write_text(TB)
        for mode in ('default','ifx'):
            commands=['read_verilog -sv native.sv','hierarchy -top branch_probe',
                'proc -noopt'+(' -ifx' if mode=='ifx' else ''),
                'setundef -undriven -undef', 'xprop -split-outputs -split-public -assume-def-inputs -required -formal',
                'opt_clean',
                f'rename branch_probe branch_{mode}',f'write_verilog -noattr {mode}.sv']
            (out/f'{mode}.ys').write_text('\n'.join(commands)+'\n')
            with (out/f'{mode}.log').open('w') as log:
                run([str(args.yosys),'-Q','-T','-s',mode+'.ys'],cwd=out,stdout=log,
                    stderr=subprocess.STDOUT,check=True,timeout=120)
        shell=Path(__file__).resolve().parent/'eda-shell'
        with (out/'compile.log').open('w') as log:
            run([str(shell),'-c',shlex.join(['vcs','-full64','-sverilog','+vcs+lic+wait',
                '-timescale=1ns/1ps','native.sv','default.sv','ifx.sv','tb.sv','-top','tb','-o','simv'])],
                cwd=out,stdout=log,stderr=subprocess.STDOUT,timeout=300,check=True)
        with (out/'sim.log').open('w') as log:
            run([str(shell),'-c','./simv'],cwd=out,stdout=log,stderr=subprocess.STDOUT,timeout=120,check=True)
        match=re.search(r'^IFX_PASS (\d+) DEFAULT_DIFFERENCES (\d+)$',(out/'sim.log').read_text(),re.M)
        if not match: raise ValueError('missing native equivalence checks')
        record.update(status='passed',samples=int(match[1]),negative_control_differences=int(match[2]))
    except Exception as error:
        record.update(status='failed',error=str(error))
        raise
    finally:
        record.update(elapsed_seconds=time.monotonic()-began,finished_utc=utc())
        save(out/'summary.json',record)


if __name__=='__main__': main()
