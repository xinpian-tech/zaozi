"""No LLM: native four-state RAM/partial-reset vs encoded rails, plus JG covers.

The old duplicate-output path is a required negative control. Do not initialize
the DUT memory: unknown data must remain unknown until actually written.
"""
import backend_imports
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess

from rvprobe.backend.initialization import canonicalize_output_aliases, materialize_initializers, BOOT
from rvprobe.backend.initialization import pack_output_bits, bind_packed_expression, audit_encoded_goal_rails
from rvprobe.backend.process import run
from run_records import save

RTL = """module init_alias(input clock, reset, we, input [1:0] addr,
input [7:0] data, output [7:0] value, output reg [7:0] partial,
output atom0, atom1, output reg written, output bit0, bit7,
output [3:0] overlap, repeated);
reg [7:0] mem[0:3];
assign value = mem[addr];
assign atom0 = value == 8'hff;
assign atom1 = atom0;
assign bit0 = value[0];
assign bit7 = value[7];
assign overlap = value[5:2];
assign repeated = {value[0], value[7], value[0], value[7]};
always @(posedge clock) begin
  if (reset) begin written <= 0; partial[3:0] <= 0; end
  else if (we) begin mem[addr] <= data; written <= 1; partial <= data; end
end
endmodule
"""

TB = """`timescale 1ns/1ps
module tb;
reg clock=0, reset=1, we=0;
reg [1:0] addr=0;
reg [7:0] data=0;
wire [7:0] value, partial, value_d, value_x, partial_d, partial_x;
wire atom0, atom1, atom0_d, atom0_x, atom1_old_d, atom1_old_x, bit0_old_x;
integer samples=0, alias_errors=0, partial_alias_errors=0, b;
init_alias original(.clock(clock),.reset(reset),.we(we),.addr(addr),.data(data),
  .value(value),.partial(partial),.atom0(atom0),.atom1(atom1));
PACKED_DECLARATIONS
fixed_alias fixed_model(.clock(clock),.reset(reset),.we(we),.addr(addr),.data(data),
  .rvp_encoded_outputs_d(packed_d),.rvp_encoded_outputs_x(packed_x));
old_alias old_model(.clock(clock),.reset(reset),.we(we),.addr(addr),.data(data),
  .atom1_d(atom1_old_d),.atom1_x(atom1_old_x),.bit0_x(bit0_old_x));
always #5 clock=~clock;
always @(posedge clock) begin
  #1;
  for(b=0;b<8;b=b+1) begin
    if(value_x[b] !== $isunknown(value[b])) $fatal(1,"RAM mask differs");
    if(!value_x[b] && value_d[b] !== value[b]) $fatal(1,"RAM value differs");
    if(partial_x[b] !== $isunknown(partial[b])) $fatal(1,"partial reset mask differs");
    if(!partial_x[b] && partial_d[b] !== partial[b]) $fatal(1,"partial reset value differs");
  end
  if(atom0_x !== $isunknown(atom0)) $fatal(1,"canonical predicate mask differs");
  if(!atom0_x && atom0_d !== atom0) $fatal(1,"canonical predicate value differs");
  if(atom1_old_x !== $isunknown(atom1)) alias_errors=alias_errors+1;
  if(bit0_old_x !== $isunknown(value[0])) partial_alias_errors=partial_alias_errors+1;
  PACKED_CHECKS
  samples=samples+1;
end
initial begin
  repeat(3) @(negedge clock); reset=0;
  repeat(2) @(negedge clock); we=1; data=8'hff;
  @(negedge clock); we=0; addr=1;
  repeat(2) @(negedge clock); we=1; data=8'h00;
  @(negedge clock); we=0; addr=0; reset=1;
  repeat(2) @(negedge clock); reset=0; addr=2;
  repeat(2) @(negedge clock);
  if(alias_errors==0) $fatal(1,"old output alias negative control did not fail");
  if(partial_alias_errors==0) $fatal(1,"old partial alias negative control did not fail");
  $display("ALIAS_INIT_PASS %0d OLD_ERRORS %0d PARTIAL_ERRORS %0d",samples,alias_errors,partial_alias_errors);
  $finish;
end
endmodule
"""


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--yosys',type=Path,required=True)
    args=parser.parse_args()
    out=args.out.resolve();out.mkdir(exist_ok=False)
    shell=Path(__file__).resolve().parent/'eda-shell'
    def command(argv,log,timeout=300):
        with (out/log).open('w') as stream:
            run(argv,cwd=out,stdout=stream,stderr=subprocess.STDOUT,timeout=timeout,check=True)
    (out/'native.sv').write_text(RTL)
    command([str(args.yosys),'-Q','-T','-p',
        'read_verilog -sv native.sv; hierarchy -top init_alias; proc -noopt -ifx; memory_map; '
        'setundef -undriven -undef; write_json before.json'], 'prepare.log')
    model=json.loads((out/'before.json').read_text())
    mapped,aliases=canonicalize_output_aliases(model,'init_alias')
    assert aliases=={'atom1':'atom0'},aliases
    mapped,bindings=pack_output_bits(mapped,'init_alias')
    width=len(mapped['modules']['init_alias']['ports']['rvp_encoded_outputs']['bits'])
    declarations=[f'wire [{width-1}:0] packed_d, packed_x;']
    for name in ('value','partial','atom0'):
        for rail in ('d','x'):
            expr=bind_packed_expression(f'{name}_{rail}',bindings).replace('rvp_encoded_outputs_', 'packed_')
            declarations.append(f'assign {name}_{rail} = {expr};')
    checks=[]
    for name,expected in [('bit0','value[0]'),('bit7','value[7]'),
                          ('overlap','value[5:2]'),('repeated','{value[0],value[7],value[0],value[7]}')]:
        for bit,index in enumerate(bindings[name]):
            native=f'original.{name}[{bit}]' if len(bindings[name])>1 else expected
            checks.append(f'if(packed_x[{index}] !== $isunknown({native})) '
                          f'$fatal(1,"{name} mask differs");')
            checks.append(f'if(!packed_x[{index}] && packed_d[{index}] !== {native}) '
                          f'$fatal(1,"{name} value differs");')
    (out/'tb.sv').write_text(TB.replace('PACKED_DECLARATIONS','\n'.join(declarations))
                           .replace('PACKED_CHECKS','\n'.join(checks)))
    save(out/'fixed.json',mapped)
    save(out/'output-bindings.json',bindings)
    for mode,source in [('old','before.json'),('fixed','fixed.json')]:
        command([str(args.yosys),'-Q','-T','-p',f'read_json {source}; '
            'xprop -split-outputs -split-public -assume-def-inputs -required -formal; '
            f'techmap t:$bwmux; opt_clean; rename init_alias {mode}_alias; '
            f'write_json {mode}-rails.json; write_verilog -noattr {mode}.sv'], mode+'.log')
    audit=audit_encoded_goal_rails(json.loads((out/'fixed-rails.json').read_text()),'fixed_alias',
                                  [bind_packed_expression('bit0_d && !bit0_x',bindings)])
    save(out/'rail-audit.json',audit)
    try:
        audit_encoded_goal_rails(json.loads((out/'old-rails.json').read_text()),'old_alias',
                                 ['bit0_d && !bit0_x'])
    except ValueError as error:
        if 'unencoded X/Z' not in str(error):
            raise
    else:
        raise ValueError('unsafe partial alias was not rejected by the rail audit')
    command([str(shell),'-c',shlex.join(['vcs','-full64','-sverilog','+vcs+lic+wait',
        '-timescale=1ns/1ps','native.sv','old.sv','fixed.sv','tb.sv','-top','tb','-o','simv'])],'compile.log')
    command([str(shell),'-c','./simv'],'sim.log')
    match=re.search(r'ALIAS_INIT_PASS (\d+) OLD_ERRORS (\d+) PARTIAL_ERRORS (\d+)',(out/'sim.log').read_text())
    if not match:raise ValueError('native/encoded checks missing')
    reset="reset 1'b1\nwe 1'b0\naddr 2'b0\ndata 8'b0\n10\nreset 1'b0\n$\n"
    code,sequence,initialization=materialize_initializers((out/'fixed.sv').read_text(), reset, [{'factor':1}])
    # Repeated goal deliberately uses the canonical predicate twice. No data
    # written -> cannot satisfy known true; written FF -> a genuine witness.
    code=code.replace('endmodule', bind_packed_expression('''
bad: cover property (@(posedge clock) !written_d && atom0_d && !atom0_x);
good: cover property (@(posedge clock) (written_d && !written_x && atom0_d && !atom0_x)[*4]);
endmodule''',bindings))
    (out/'goal.sv').write_text(code);(out/'reset.seq').write_text(sequence)
    save(out/'initialization.json',initialization)
    (out/'solve.tcl').write_text(f'''clear -all
analyze -sv12 {{{out}/goal.sv}}
elaborate -top fixed_alias
clock clock
reset -sequence {{{out}/reset.seq}}
assume {{{BOOT} == 1'b0}}
assume {{reset == 1'b0}}
set_prove_time_limit 30s
prove -all
puts "BAD [get_property_info fixed_alias.bad -list status]"
puts "GOOD [get_property_info fixed_alias.good -list status]"
exit
''')
    command([str(shell),'-c',shlex.join(['jg','-batch','-tcl',str(out/'solve.tcl'),'-proj',str(out/'jgproject')])],'jg.log')
    results=dict(re.findall(r'^(BAD|GOOD) (\w+)$',(out/'jg.log').read_text(),re.M))
    assert results=={'BAD':'unreachable','GOOD':'covered'},results
    save(out/'summary.json',dict(status='passed',diagnostic_only=True,remote_llm_requests=0,
        samples=int(match[1]),old_alias_errors=int(match[2]),old_partial_alias_errors=int(match[3]),
        jg=results,aliases=aliases))


if __name__=='__main__':main()
