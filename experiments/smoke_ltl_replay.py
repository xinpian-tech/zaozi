"""Native VCS observer semantics: success, miss, X, gating, and native past."""
import backend_imports
import argparse
import json
from pathlib import Path
import shlex
import time
from rvprobe.backend.process import run
from run_records import save,utc
from test_ltl_replay import metadata,SV,FIXTURES
from rvprobe.backend.replay import monitor, check_hit
from sequence_framework import load_design


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args();out=args.out.resolve();out.mkdir(parents=True,exist_ok=False)
    record=dict(started_utc=utc(),diagnostic_only=True,remote_llm_requests=0,cases=[])
    start=time.monotonic()
    design=load_design(FIXTURES/'tiny_design.json')
    cases=[('hit','valid & done_net','1',1,True),
           ('miss','valid & done_net','0',1,False),
           ('unknown','valid & done_net',"1'bx",1,False),
           ('inactive','valid & done_net','1',0,False),
           ('past','valid & $past(payload, 2) == payload','1',1,True),
           ('explicit_disable_hit','disable iff (!valid) valid & done_net','1',1,True),
           ('explicit_disable_abort','disable iff (valid) valid & done_net','1',1,False),
           ('explicit_disable_inactive','disable iff (!valid) valid & done_net','1',0,False),
           ('explicit_disable_unknown','disable iff (!valid) valid & done_net',"1'bx",1,False)]
    shell=str(Path(__file__).resolve().parent/'eda-shell')
    for label,expression,value,active,expected in cases:
        folder=out/label;folder.mkdir()
        meta=metadata(SV.replace("valid & payload <= 8'h7",expression))
        name,code=monitor(meta,design)
        (folder/'observer.sv').write_text(code)
        (folder/'bench.sv').write_text(f'''module bench;
bit clock=0, reset=1, rvp_active={active};
logic [7:0] payload=7, result=7;
logic valid=1,done={value};
always #5 clock=~clock;
{name} observer(.*);
initial begin #20;reset=0;#80;$finish;end
endmodule
''')
        item=dict(case=label,expected_hit=expected)
        try:
            for phase,command in [('compile',['vcs','-full64','-sverilog','-assert','svaext',
                    '-timescale=1ns/1ps','observer.sv','bench.sv','-top','bench','-o','simv']),('sim',['./simv'])]:
                with (folder/(phase+'.log')).open('w') as log:
                    run([shell,'-c',shlex.join(command)],cwd=folder,stdout=log,stderr=-2,check=True,timeout=180)
            hit=True
            try:check_hit(meta,(folder/'sim.log').read_text())
            except ValueError:hit=False
            if hit!=expected:raise ValueError('native cover acceptance differs from expected')
            item.update(status='passed',actual_hit=hit)
        except Exception as error:item.update(status='failed',error=str(error))
        record['cases'].append(item)
    record.update(status='passed' if all(c['status']=='passed' for c in record['cases']) else 'failed',
                  finished_utc=utc(),elapsed_seconds=time.monotonic()-start)
    save(out/'summary.json',record);print(json.dumps(record))
    return int(record['status']!='passed')


if __name__=='__main__':raise SystemExit(main())
