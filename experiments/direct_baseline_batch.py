"""Continue all direct-baseline design cells despite individual failures.

One-shot campaign, not a scheduled timer. Share three EDA slots with the
already launched HAVEN/RVProbe services, then use freed slots automatically.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from batch_lifecycle import current_owner
from design_inventory import DESIGNS
from directed_baselines import METHODS
from experiment_storage import require_space
from haven_design_batch import archive_design
from rvprobe.backend.process import run
from run_records import save, fresh_directory, utc, framework_hashes
from fourway_report import export
from sequence_framework import ROOT


def live_units(units):
    live=0
    for unit in units:
        result=subprocess.run(['systemctl','show',unit,'--property=ActiveState','--value'],capture_output=True,text=True,check=True)
        live+=result.stdout.strip() in ('active','activating','deactivating')
    return live


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('work','archive','stage1-map','haven-root','env-file','yosys','ethmac'):
        p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--peer-unit',action='append',default=[])
    p.add_argument('--jobs',type=int,default=3)
    args=p.parse_args()
    if args.jobs<1:p.error('positive jobs required')
    work=fresh_directory(args.work.resolve());archive=args.archive.resolve()
    os.environ['RVPROBE_STORAGE_WORK_ROOT']=str(work)
    os.environ['RVPROBE_STORAGE_ARCHIVE_ROOT']=str(archive)
    ready=json.loads(args.stage1_map.read_text())
    pending=[(method,name) for name in DESIGNS for method in METHODS]
    state=dict(status='running',started_utc=utc(),owner=current_owner(),expected_direct_cells=len(pending),
        cells={m+'/'+n:{'status':'queued'} for m,n in pending},framework=framework_hashes(ROOT),
        peer_units=args.peer_unit,shared_slots=args.jobs)
    def checkpoint():
        state['updated_utc']=utc();save(work/'progress.json',state);save(archive/'direct-progress.json',state)
        export(archive,args.ethmac)
    def execute(method,name):
        directory=work/method/name
        directory.parent.mkdir(parents=True,exist_ok=True)
        temp=work/'tmp'/method/name;temp.mkdir(parents=True)
        log=work/(method+'-'+name+'.log')
        record=dict(status='running',method=method,design=name,started_utc=utc())
        start=time.monotonic()
        try:
            cmd=[sys.executable,str(ROOT/'experiments/directed_experiment.py'),
                '--fixed-setup',ready[name],'--haven-root',str(args.haven_root),'--env-file',str(args.env_file),
                '--out',str(directory),'--method',method,'--encoded-witness-yosys',str(args.yosys)]
            env={**os.environ,'TMPDIR':str(temp),'RVPROBE_STORAGE_WORK_ROOT':str(work),
                 'RVPROBE_STORAGE_ARCHIVE_ROOT':str(archive)}
            with log.open('x') as stream:
                result=run(cmd,cwd=ROOT,env=env,stdout=stream,stderr=-2,timeout=13*3600)
            summary=json.loads((directory/'summary.json').read_text()) if (directory/'summary.json').exists() else {}
            record.update(status=summary.get('status','failed'),error=summary.get('error'),exit_code=result.returncode)
            if result.returncode and record['status']!='failed':record.update(status='failed',error='nonzero process exit')
        except Exception as error:
            record.update(status='failed',error=str(error))
        finally:
            record.update(finished_utc=utc(),elapsed_seconds=time.monotonic()-start)
            directory.mkdir(parents=True,exist_ok=True)
            if temp.exists():
                import shutil
                shutil.move(str(temp),str(directory/'temporary'))
            if log.exists():
                import shutil
                shutil.copy2(log,directory/'process.log')
            try:
                archive_design(directory,archive/method/name,record,True)
                record['storage_status']='relocated'
            except Exception as error:
                record['storage_status']='archive_failed_local_retained';record['archive_error']=str(error)
        return record
    checkpoint()
    active={}
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        while pending or active:
            peers=live_units(args.peer_unit)
            while pending and len(active)+peers<args.jobs:
                try:require_space(work,minimum=(len(active)+1)*1024**3)
                except OSError as error:
                    state['storage_wait']=str(error);break
                method,name=pending.pop(0);state['cells'][method+'/'+name]={'status':'running','started_utc':utc()}
                active[pool.submit(execute,method,name)]=(method,name)
            checkpoint()
            if active:
                done,_=wait(active,timeout=30,return_when=FIRST_COMPLETED)
                for future in done:
                    method,name=active.pop(future)
                    try:record=future.result()
                    except Exception as error:record={'status':'failed','error':str(error)}
                    state['cells'][method+'/'+name]=record
            else:time.sleep(30)
    # Keep refreshing the all-method report until independently launched peers finish.
    while live_units(args.peer_unit):
        state['status']='waiting_for_peer_results';checkpoint();time.sleep(30)
    state.update(status='finished',finished_utc=utc());checkpoint()
    save(archive/'direct-summary.json',state)
    report=export(archive,args.ethmac)
    print(json.dumps({'status':'finished','terminal_cells':report['terminal_cells'],'report':str(archive/'results.md')}),flush=True)


if __name__=='__main__':main()
