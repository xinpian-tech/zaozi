"""Resume an offline RQ1 sweep in a new audit directory; no model calls.

The caller must stop old workers first. Completed measurements are linked,
interrupted artifacts archived, and only missing work admitted (three slots).
"""
import backend_imports
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
from pathlib import Path
import subprocess
import sys
import time

from cycle_replay import digest
from haven_design_batch import archive_design
from run_records import fresh_directory, save, utc
from sweep_rq1_stimuli import POLICY, sources


def valid_complete(summary):
    return (summary.get('status') == 'completed' and
            summary.get('reference_check',{}).get('passed') is True and
            [c['count'] for c in summary.get('cells',[])] == list(range(1,9)) and
            not any(p['status'] == 'extension-failed' for p in summary['properties']))


def run(args):
    selected = sources(args.selection,args.rq1)
    recoveries = json.loads(args.recovery_map.read_text())
    if set(recoveries)-set(selected):
        raise ValueError('unknown recovery design')
    fresh_directory(args.out)
    began = time.monotonic()
    record = dict(status='running',policy=POLICY,started_utc=utc(),jobs=args.jobs,
        selection=str(args.selection.resolve()),selection_sha256=digest(args.selection),
        rq1=str(args.rq1.resolve()),rq1_sha256=digest(args.rq1),counts=list(range(1,9)),
        designs={n:dict(status='pending',**s) for n,s in selected.items()},
        new_llm_calls=0,new_llm_tokens=0,
        continuation=dict(prior_batch=str(args.prior.resolve()),recovery_map=str(args.recovery_map.resolve()),
            note='batch wall time is this continuation only; preserved/abandoned prior work is not zero-cost'))
    save(args.out/'sources.json',selected)
    save(args.out/'progress.json',record)
    pending = []
    for name,selection in selected.items():
        complete_path = args.prior/name/'summary.json'
        if complete_path.is_file():
            measured = json.loads(complete_path.read_text())
            if measured['source'] != selection:
                raise ValueError('completed source selection changed: '+name)
            if valid_complete(measured):
                (args.out/name).symlink_to((args.prior/name).resolve(),target_is_directory=True)
                record['designs'][name].update(status='completed',reused_complete=True,
                    reused_summary=str(complete_path.resolve()),summary_sha256=digest(complete_path),
                    elapsed_seconds=measured['elapsed_seconds'],reference_check=measured['reference_check'])
                continue
        prior_path = Path(recoveries[name]) if name in recoveries else None
        if prior_path:
            prior = json.loads(prior_path.read_text())
            if prior['design'] != name or prior['source'] != selection or prior['policy'] != POLICY:
                raise ValueError('recovery source/selection/policy changed: '+name)
            work = Path(prior['scratch'])
            # Archival preserves the original absolute scratch path as a link.
            # Do not relocate arbitrary caller paths or work owned by another run.
            if work.is_dir() and not work.is_symlink():
                if work.parent != Path('/dev/shm') or not work.name.startswith('rvp-rq1-count-'+name+'-'):
                    raise ValueError('unexpected interrupted scratch root: '+str(work))
                print(json.dumps(dict(design=name,phase='archive-interrupted',scratch=str(work))),flush=True)
                archive_design(work,args.prior/name,dict(status='failed',scope='interrupted-worker-only',
                    reason='worker interrupted before final summary; not a property failure'),True)
            record['designs'][name]['recovery_source'] = str(prior_path)
            record['designs'][name]['recovery_sha256'] = digest(prior_path)
        pending.append((name,prior_path))
    save(args.out/'progress.json',record)

    def invoke(name,prior):
        command = [sys.executable,str(Path(__file__).with_name('sweep_rq1_stimuli.py')),
            '--selection',str(args.selection.resolve()),'--rq1',str(args.rq1.resolve()),
            '--out',str(args.out.resolve()),'--design',name]
        if prior:
            command += ['--resume-from',str(prior)]
        print(json.dumps(dict(design=name,phase='worker-start',recovery=bool(prior))),flush=True)
        with (args.out/f'{name}.log').open('x') as stream:
            result = subprocess.run(command,stdout=stream,stderr=subprocess.STDOUT)
        path = args.out/name/'summary.json'
        if not path.is_file():
            return dict(status='failed',returncode=result.returncode,error='missing worker summary')
        measured = json.loads(path.read_text())
        state = {k:measured[k] for k in ('status','elapsed_seconds','reference_check','error') if k in measured}
        failures = [p['label'] for p in measured.get('properties',[]) if p['status']=='extension-failed']
        if failures:
            state.update(status='failed',error='extension implementation/infrastructure failures',properties=failures)
        return state

    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(invoke,name,prior):name for name,prior in pending}
        for future in as_completed(futures):
            name = futures[future]
            try:
                state = future.result()
            except Exception as error:
                state = dict(status='failed',error=str(error))
            record['designs'][name].update(state)
            save(args.out/'progress.json',record)
            print(json.dumps(dict(design=name,**state)),flush=True)
    record.update(status='completed' if all(r['status']=='completed' for r in record['designs'].values())
                  else 'completed_with_failures',finished_utc=utc(),elapsed_seconds=time.monotonic()-began)
    save(args.out/'summary.json',record)
    save(args.out/'progress.json',record)
    from report_rq1_stimuli import report
    report(args.out.resolve())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('selection','rq1','out','prior','recovery-map'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--jobs',type=int,choices=(1,2,3),default=3)
    run(parser.parse_args())
