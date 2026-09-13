"""Native regression for vector/per-bit tri-state overlap in auxiliary encoding.

Exhaust all defined data and noncontending enable combinations, including an
undriven bus. Z is compared as unknown: this is not general four-state equality.
"""
import argparse
import re
import shlex
import subprocess
import time
from pathlib import Path

from encoded_initialization import expose_collision_guards, preserve_single_driver_masks
from run_records import save, utc
from process_runner import run
import json

RTL = """module tri_probe(input [1:0] a,b,input ena,input [1:0] enb,output [1:0] sense,lone);
wire [1:0] bus_value;
assign bus_value = ena ? a : 2'bzz;
assign bus_value[0] = enb[0] ? b[0] : 1'bz;
assign bus_value[1] = enb[1] ? b[1] : 1'bz;
assign sense = bus_value;
assign lone = ena ? a : 2'bzz;
endmodule
"""
TB = """`timescale 1ns/1ps
module tb;
reg [1:0] a=0,b=0,enb=0;
reg ena=0;
wire [1:0] sense,value,mask;
wire [1:0] lone,lone_value,lone_mask;
integer ai,bi,ei,fi,bit_index,samples=0;
tri_probe original(a,b,ena,enb,sense,lone);
tri_encoded encoded(.a(a),.b(b),.ena(ena),.enb(enb),.sense_d(value),.sense_x(mask),.lone_d(lone_value),.lone_x(lone_mask));
initial begin
 for(ai=0;ai<4;ai=ai+1) for(bi=0;bi<4;bi=bi+1)
 for(ei=0;ei<2;ei=ei+1) for(fi=0;fi<4;fi=fi+1) if (!(ei && fi)) begin
  a=ai;b=bi;ena=ei;enb=fi;#1;
  for(bit_index=0;bit_index<2;bit_index=bit_index+1) begin
   if(mask[bit_index] !== $isunknown(sense[bit_index])) $fatal(1,"tristate mask mismatch");
   if(!mask[bit_index] && value[bit_index] !== sense[bit_index]) $fatal(1,"tristate data mismatch");
   if(lone_mask[bit_index] !== $isunknown(lone[bit_index])) $fatal(1,"lone tristate mask mismatch");
   if(!lone_mask[bit_index] && lone_value[bit_index] !== lone[bit_index]) $fatal(1,"lone tristate data mismatch");
  end
  samples=samples+1;
 end
 $display("TRISTATE_PASS %0d",samples);$finish;
end
endmodule
"""


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--yosys',type=Path,required=True)
    args=p.parse_args(); out=args.out.resolve();out.mkdir(exist_ok=False)
    began=time.monotonic()
    result=dict(status='running',diagnostic_only=True,remote_llm_requests=0,started_utc=utc())
    def yosys(script,name):
        (out/(name+'.ys')).write_text(script)
        with (out/(name+'.log')).open('w') as log:
            run([str(args.yosys),'-Q','-T','-s',name+'.ys'],cwd=out,stdout=log,stderr=subprocess.STDOUT,timeout=120,check=True)
    try:
        (out/'native.sv').write_text(RTL);(out/'tb.sv').write_text(TB)
        yosys('read_verilog -sv native.sv\nhierarchy -top tri_probe\nproc -noopt -ifx\nflatten\n'
              'tribuf\nsimplemap t:$tribuf\nwrite_json split.json\n','split')
        masked,singles=preserve_single_driver_masks(json.loads((out/'split.json').read_text()),'tri_probe')
        if len(singles)!=2:raise ValueError('lone-driver masks not preserved')
        save(out/'masked.json',masked)
        yosys('read_json masked.json\ntribuf -formal\nchformal -assert2assume\nwrite_json tri.json\n','lower')
        mapped,guards=expose_collision_guards(json.loads((out/'tri.json').read_text()),'tri_probe')
        if len(guards)!=4: raise ValueError('overlapping driver collision checks lost')
        save(out/'guarded.json',mapped)
        yosys('read_json guarded.json\nxprop -split-outputs -split-public -assume-def-inputs -required -formal\n'
              'techmap t:$bwmux\nopt_clean\nrename tri_probe tri_encoded\nwrite_verilog -noattr encoded.sv\n','encode')
        shell=Path(__file__).resolve().parent/'eda-shell'
        with (out/'compile.log').open('w') as log:
            run([str(shell),'-c',shlex.join(['vcs','-full64','-sverilog','+vcs+lic+wait',
                '-timescale=1ns/1ps','native.sv','encoded.sv','tb.sv','-top','tb','-o','simv'])],
                cwd=out,stdout=log,stderr=subprocess.STDOUT,timeout=300,check=True)
        with (out/'sim.log').open('w') as log:
            run([str(shell),'-c','./simv'],cwd=out,stdout=log,stderr=subprocess.STDOUT,timeout=120,check=True)
        match=re.search(r'^TRISTATE_PASS (\d+)$',(out/'sim.log').read_text(),re.M)
        if not match or int(match[1])!=80: raise ValueError('missing exhaustive native checks')
        result.update(status='passed',samples=int(match[1]),collision_guards=len(guards))
    except Exception as error:
        result.update(status='failed',error=str(error));raise
    finally:
        result.update(finished_utc=utc(),elapsed_seconds=time.monotonic()-began)
        save(out/'summary.json',result)


if __name__=='__main__':main()
