"""Recheck saved candidates from incomplete pairs, with zero model calls.

No Stage-1 generation, new UT, candidate repairs or provider-response completion.
Older complete candidates are explicitly distinguished from the failed round.
"""
import backend_imports
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from run_records import save, utc, framework_hashes
from cycle_replay import digest
from rvprobe.backend.process import run


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tracking',type=Path,required=True)
    parser.add_argument('--haven-root',type=Path,required=True)
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--designs',nargs='+',required=True)
    parser.add_argument('--workers',type=int,choices=[1,2],default=2)
    args=parser.parse_args()
    out=args.out.resolve();out.mkdir(parents=True,exist_ok=False)
    began=time.monotonic()
    tracking=json.loads(args.tracking.read_text())
    record=dict(diagnostic_only=True,remote_llm_requests=0,status='running',started_utc=utc(),
                framework=framework_hashes(Path(__file__).resolve().parents[1]),jobs=[])
    tasks=[]
    for name in args.designs:
        source=Path(tracking['attempts'][name][-1]).resolve()
        summary=json.loads(source.read_text())
        flow=source.parent.parent
        fixed=json.loads((flow/'fixed-stage1.json').read_text())
        for arm in ('rvprobe','haven'):
            job=dict(design=name,arm=arm,status='queued',source_summary=str(source),
                     historical_status=summary['arms'][arm]['status'],
                     historical_error=summary['arms'][arm].get('error'),
                     failed_round=summary['arms'][arm].get('failed_round'))
            candidates=list((source.parent/arm).glob('round-*/candidate.json'))
            def order(p):
                parts=p.parent.name.split('-')
                return int(parts[1]),int(parts[-1]) if 'repair' in parts else 0
            if not candidates:
                job.update(status='unavailable',error='no saved complete candidate')
            else:
                candidate=max(candidates,key=order)
                job.update(source_round=candidate.parent.name,candidate_sha256=digest(candidate),
                    stage1=fixed['stage1'],
                    older_than_failed_round=bool(job['failed_round'] and order(candidate)[0]<job['failed_round']))
                target=out/name/arm
                command=[sys.executable,str(Path(__file__).with_name('recheck_native_ltl.py')),
                    '--run',str(flow),'--stage1',fixed['stage1'],'--haven-root',str(args.haven_root.resolve()),
                    '--out',str(target),'--round-directory',candidate.parent.name,'--arm',arm,
                    '--boundary','independent-dut-v1','--keep-going']
                tasks.append((job,command,target))
            record['jobs'].append(job)
    save(out/'progress.json',record)
    env={k:v for k,v in os.environ.items() if k not in
         ('RVPROBE_LLM_API_KEY','RVPROBE_LLM_BASE_URL','OPENAI_API_KEY','OPENAI_BASE_URL')}
    def execute(job,command,target):
        job.update(status='running',started_utc=utc())
        target.parent.mkdir(parents=True,exist_ok=True)
        with (target.parent/(job['arm']+'.log')).open('w') as log:
            try:
                result=run(command,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=1800,check=False)
                path=target/'summary.json'
                if path.exists():
                    details=json.loads(path.read_text())
                    job.update(status=details['status'],summary=str(path),
                        passed=sum(s['status']=='passed' for s in details['sequences']),
                        tested=len(details['sequences']),elapsed_seconds=details['elapsed_seconds'],
                        failures=[{k:s[k] for k in ('index','label','error','diagnostics') if k in s}
                                  for s in details['sequences'] if s['status']=='failed'])
                else:job.update(status='failed',error=f'no terminal replay report; exit {result.returncode}')
            except Exception as error:job.update(status='failed',error=str(error))
        job['finished_utc']=utc()
        return job
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures=[executor.submit(execute,*task) for task in tasks]
        for future in as_completed(futures):
            job=future.result();save(out/'progress.json',record)
            print(json.dumps({k:v for k,v in job.items() if k in ('design','arm','status','passed','tested','error')}),flush=True)
    record.update(status='passed' if all(j['status']=='passed' for j in record['jobs']) else 'finished_with_failures',
                  finished_utc=utc(),elapsed_seconds=time.monotonic()-began)
    save(out/'summary.json',record);save(out/'progress.json',record)
    return int(record['status']!='passed')


if __name__=='__main__':raise SystemExit(main())
