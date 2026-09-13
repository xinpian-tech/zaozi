"""Generic BFM-only protocol regression. No benchmark, model or coverage answers."""
import argparse
import json
from pathlib import Path
import shlex
import time
from jinja2 import Template
from process_runner import run
from run_records import save, utc
from cycle_replay import digest

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for key in ('haven-root','out','eda-shell'): parser.add_argument('--'+key,type=Path,required=True)
    args=parser.parse_args()
    out=args.out.resolve(); out.mkdir(parents=True,exist_ok=False)
    template=args.haven_root/'src/haven/templates/bfm/bfm_i2c_slave.sv.j2'
    bench=Path(__file__).parent/'tests/fixtures/i2c_slave_protocol_tb.sv'
    (out/'bfm.sv').write_text(Template(template.read_text()).render(params={}))
    record=dict(status='running',started_utc=utc(),new_llm_tokens=0,
                template_sha256=digest(template),bench_sha256=digest(bench))
    start=time.monotonic()
    try:
        for name,cmd in [('compile',['vcs','-full64','-sverilog','-timescale=1ns/1ps','bfm.sv',str(bench.resolve()),'-top','i2c_slave_protocol_tb','-o','simv']),('sim',['./simv'])]:
            with (out/(name+'.log')).open('w') as log:
                run([str(args.eda_shell.resolve()),'-c',shlex.join(cmd)],cwd=out,stdout=log,stderr=-2,check=True,timeout=300)
        if 'I2C_SLAVE_PROTOCOL_PASS' not in (out/'sim.log').read_text(): raise ValueError('missing pass marker')
        record['status']='passed'
    except Exception as error: record.update(status='failed',error=str(error))
    record.update(finished_utc=utc(),elapsed_seconds=time.monotonic()-start)
    save(out/'summary.json',record); print(json.dumps(record))
    return int(record['status']!='passed')

if __name__=='__main__': raise SystemExit(main())
