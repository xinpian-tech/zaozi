"""Explicitly authorized continuation after stopping the first HAVEN batch.

Retain the original stopped cohort; route only unfinished cells to a fresh run.
The service reserves the existing HAVEN peer slot while current direct jobs
drain, so no model request is interrupted to restore the three-design limit.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from design_inventory import DESIGNS
from experiment_storage import archive_tree
from run_records import save, utc, totals


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root',type=Path,required=True)
    p.add_argument('--old-work',type=Path,required=True)
    p.add_argument('--work',type=Path,required=True)
    p.add_argument('--haven-root',type=Path,required=True)
    p.add_argument('--env-file',type=Path,required=True)
    p.add_argument('--stage1-map',type=Path,required=True)
    p.add_argument('--yosys',type=Path,required=True)
    args=p.parse_args()
    root=args.root.resolve();old=root/'haven';archive=root/'haven-continuation'
    if archive.exists() or args.work.exists():raise ValueError('continuation must use new directories')
    completed=[];remaining=[]
    for name in DESIGNS:
        path=old/name/'flow/paired/summary.json'
        record=json.loads(path.read_text()) if path.exists() else {}
        if record.get('status') in ('completed','failed'):completed.append(name)
        else:remaining.append(name)
    record=dict(status='waiting_for_slot',authorized_by='user: haven实验停下之后执行刚才的/goal',
        started_utc=utc(),preserved_terminal=completed,designs=remaining,
        prior_interrupted={},archive=str(archive),work=str(args.work))
    for name in remaining:
        source=args.old_work/name
        if source.exists() and not source.is_symlink():
            destination=root/'haven-interrupted'/name
            archive_tree(source,destination)
            record['prior_interrupted'][name]={'artifacts':str(destination),'costs':totals(destination)}
        prior=old/name
        if prior.exists() or prior.is_symlink():
            retained=old/(name+'.before-continuation')
            if retained.exists():raise ValueError('old cell already preserved')
            prior.rename(retained)
        prior.symlink_to(archive/name,target_is_directory=True)
    save(root/'haven-continuation.json',record)
    # The old unit name is now this new one-shot continuation. The direct
    # scheduler already counts that peer, so it cannot admit another job.
    while True:
        progress=root/'direct-progress.json'
        data=json.loads(progress.read_text()) if progress.exists() else {}
        direct=sum(v.get('status')=='running' for v in data.get('cells',{}).values())
        result=subprocess.run(['systemctl','show','rvprobe-fourway-rvprobe-20260915-v1.service',
            '-p','ActiveState','--value'],capture_output=True,text=True,check=True)
        other=int(result.stdout.strip() in ('active','activating'))
        if direct+other < 3:break
        time.sleep(15)
    record.update(status='running',launched_utc=utc());save(root/'haven-continuation.json',record)
    # Preserve the stopped progress; summaries of completed cells take priority
    # over this progress when the existing four-way exporter reads them.
    progress=old/'progress.json'
    if progress.exists():progress.rename(old/'progress.before-continuation.json')
    progress.symlink_to(archive/'progress.json')
    command=[sys.executable,str(Path(__file__).with_name('haven_design_batch.py')),
        '--haven-root',str(args.haven_root),'--env-file',str(args.env_file),
        '--stage1-map',str(args.stage1_map),'--encoded-witness-yosys',str(args.yosys),
        '--designs',*remaining,'--arm','haven','--jobs','1','--out',str(args.work),
        '--archive-root',str(archive),'--relocate-completed']
    os.execv(sys.executable,command)


if __name__=='__main__':main()
