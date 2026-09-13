"""Compare valid sampled history; verify conservative unknown auxiliary prehistory."""
import argparse
from pathlib import Path
import shlex
import subprocess
import time
from encoded_past import lower_past
from process_runner import run
from run_records import save, utc

RTL = '''module history_probe(input clock, slow, input [7:0] a,
output [7:0] h1, h3, nested, slow_history);
wire [7:0] internal_data = a;
wire [7:0] first_history = $past(internal_data, 1, , @(posedge clock));
assign h1 = first_history;
assign h3 = $past(first_history, 2, , @(posedge clock));
assign nested = $past($past(a, 2, , @(posedge clock)), 1, , @(posedge clock));
assign slow_history = $past(a, 2, , @(posedge slow));
endmodule
'''
TB = '''`timescale 1ns/1ps
module tb;
reg clock=0, slow=0;
reg [7:0] a=0;
wire [7:0] h1,h3,nested,slow_history,d1,d3,dn,ds;
integer checks=0, slow_checks=0, differences=0, mismatches=0, i;
history_probe original(clock,slow,a,h1,h3,nested,slow_history);
history_lowered lowered(clock,slow,a,d1,d3,dn,ds);
always #5 clock=~clock;
always #15 slow=~slow;
check_fast: assert property (@(posedge clock)
  ((checks < 1) ? $isunknown(d1) : (h1 === d1)) &&
  ((checks < 3) ? ($isunknown(d3) && $isunknown(dn)) : ((h3 === d3) && (nested === dn))))
  else begin mismatches=mismatches+1; $display("FAST_MISMATCH %0t native=%h,%h,%h lower=%h,%h,%h",$time,$sampled(h1),$sampled(h3),$sampled(nested),$sampled(d1),$sampled(d3),$sampled(dn)); end
check_slow: assert property (@(posedge slow)
  (slow_checks < 2) ? $isunknown(ds) : (slow_history === ds))
  else begin mismatches=mismatches+1; $display("SLOW_MISMATCH %0t native=%h lower=%h",$time,$sampled(slow_history),$sampled(ds)); end
always @(posedge clock) checks=checks+1;
always @(posedge slow) slow_checks=slow_checks+1;
initial begin
  for(i=0;i<36;i=i+1) begin
    @(negedge clock);
    if(i>4 && h1 !== h3) differences=differences+1;
    case(i%6)
      0:a=8'hxx;
      1:a=8'hzz;
      2:a=8'h81;
      3:a=8'h00;
      4:a=8'hff;
      5:a=i;
    endcase
  end
  if(differences == 0) $fatal(1,"off-by-two negative control did not differ");
  if(mismatches != 0) $fatal(1,"past history differs in %0d checks",mismatches);
  $display("PAST_EQUIVALENCE_PASS %0d NEGATIVE_DIFFERENCES %0d", checks,differences);
  $finish;
end
endmodule
'''


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--yosys',type=Path,required=True)
    args=p.parse_args(); out=args.out.resolve(); out.mkdir(exist_ok=False)
    began=time.monotonic()
    record=dict(status='running',diagnostic_only=True,remote_llm_requests=0,started_utc=utc())
    try:
        lowered,info=lower_past(RTL,{'clock','slow'})
        (out/'original.sv').write_text(RTL)
        (out/'lowered.sv').write_text(lowered.replace('module history_probe','module history_lowered'))
        (out/'tb.sv').write_text(TB)
        save(out/'histories.json',info)
        with (out/'yosys.log').open('w') as log:
            run([str(args.yosys.resolve()), '-Q', '-T', '-p',
                 'read_verilog -sv lowered.sv; hierarchy -check -top history_lowered; proc -noopt -ifx; check'],
                cwd=out,stdout=log,stderr=subprocess.STDOUT,timeout=120,check=True)
        shell=Path(__file__).resolve().parent/'eda-shell'
        with (out/'compile.log').open('w') as log:
            run([str(shell),'-c',shlex.join(['vcs','-full64','-sverilog','+vcs+lic+wait',
                '-assert','svaext','-timescale=1ns/1ps','original.sv','lowered.sv','tb.sv','-top','tb','-o','simv'])],
                cwd=out,stdout=log,stderr=subprocess.STDOUT,timeout=300,check=True)
        with (out/'sim.log').open('w') as log:
            run([str(shell),'-c','./simv'],cwd=out,stdout=log,stderr=subprocess.STDOUT,timeout=120,check=True)
        if 'PAST_EQUIVALENCE_PASS' not in (out/'sim.log').read_text():
            raise ValueError('missing native history equivalence result')
        record.update(status='passed',valid_history_equivalence=True,
                      prehistory='auxiliary unknown; native default may differ before sufficient samples')
    except Exception as error:
        record.update(status='failed',error=str(error)); raise
    finally:
        record.update(elapsed_seconds=time.monotonic()-began,finished_utc=utc())
        save(out/'summary.json',record)


if __name__=='__main__':main()
