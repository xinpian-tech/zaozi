"""Four-state electrical regression of the generic resolved-wire wrapper."""
import backend_imports
import argparse
from pathlib import Path
import shlex
from bidirectional_wrapper import render
from rvprobe.backend.process import run
from run_records import save


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out',type=Path,required=True)
    args=p.parse_args(); out=args.out.resolve();out.mkdir(parents=True,exist_ok=False)
    source,_=render('original','adapted',[
        dict(name='oe',direction='input',width=2),dict(name='value',direction='input',width=2),
        dict(name='pad',direction='inout',width=2),dict(name='readback',direction='output',width=2)])
    (out/'adapted.v').write_text(source)
    (out/'test.sv').write_text('''`timescale 1ns/1ps
module original(input [1:0] oe,value,inout [1:0] pad,output [1:0] readback);
  for(genvar i=0;i<2;i++) assign pad[i]=oe[i]?value[i]:1'bz;
  assign readback=pad;
endmodule
module tb;
  logic [1:0] oe=0,value=0,pad_drive=0,pad_enable=0;
  wire [1:0] pad_sense,readback;
  adapted dut(.*);
  task check(input logic [1:0] expected);
    #1; if(pad_sense !== expected || readback !== expected) $fatal(1,"resolved IO mismatch");
  endtask
  initial begin
    check(2'bzz);
    oe=3; value=2; check(2'b10);
    oe=0; pad_enable=3;pad_drive=1;check(2'b01);
    oe=3;value=1;check(2'b01);
    value=2;check(2'bxx);
    oe=1;value=1;pad_enable=2;pad_drive=2;check(2'b11);
    $display("BIDIRECTIONAL_PASS floating, DUT drive, external drive, same drive, contention, per-bit enables");$finish;
  end
endmodule
''')
    shell=Path(__file__).resolve().parent/'eda-shell'
    for name,command in [('compile',['vcs','-full64','-sverilog','-timescale=1ns/1ps','adapted.v','test.sv','-top','tb','-o','simv']),('sim',['./simv'])]:
        with (out/(name+'.log')).open('w') as log:
            run([str(shell),'-c',shlex.join(command)],cwd=out,stdout=log,stderr=-2,check=True,timeout=180)
    if 'BIDIRECTIONAL_PASS' not in (out/'sim.log').read_text(): raise ValueError('missing electrical regression marker')
    save(out/'summary.json',dict(status='passed',cases=6,remote_llm_requests=0))


if __name__=='__main__': main()
