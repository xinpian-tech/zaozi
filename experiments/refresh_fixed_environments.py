"""Offline migration of saved environments; retain every compiled sequence verbatim."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
from run_records import save, utc


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('stages','haven-root','out'): p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--designs',nargs='+',required=True)
    p.add_argument('--normalize-sequence-constraints',action='store_true')
    args=p.parse_args(); out=args.out.resolve(); out.mkdir(parents=True,exist_ok=False)
    stages=json.loads(args.stages.read_text()); results={}; mapping={}
    root=Path(__file__).parent.resolve()
    for name in args.designs:
        stage=Path(stages[name]).resolve()
        if stage.is_file(): stage=Path(json.loads(stage.read_text())['stage1'])
        record={'status':'running','started_utc':utc(),'stage1_model_calls':0,'source':str(stage)}
        results[name]=record; save(out/'progress.json',results)
        with (out/(name+'.log')).open('x') as log:
            result=subprocess.run([sys.executable,str(root/'recover_stage1_dsl.py'),
                '--stage1-run',str(stage),'--haven-root',str(args.haven_root.resolve()),
                '--out',str(out/name),'--config',str(root/'designs/haven_eda.json'),
                '--reuse-dsl','--reuse-compiled-sequences','--refresh-infrastructure',
                '--refresh-bfms','--refresh-environment',
                *(['--normalize-sequence-constraints'] if args.normalize_sequence_constraints else [])],
                stdout=log,stderr=subprocess.STDOUT,timeout=1200)
        record.update(status='stage1_ready' if result.returncode==0 else 'failed',finished_utc=utc())
        if result.returncode==0: mapping[name]=str(out/name/'stage1-costs.json')
        save(out/'progress.json',results); save(out/'stages.json',mapping)
        print(name,record['status'],flush=True)


if __name__=='__main__': main()
