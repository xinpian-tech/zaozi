"""Fresh repeated four-method cohorts, one global scheduler and fixed Stage 1.

No prior responses/results are reused. All terminal cells, including failures,
are archived; each repeat has its own table and no best-of selection.
"""
import backend_imports
import argparse
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
import csv
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

from design_inventory import DESIGNS
from frozen_stage1 import load_fixed_setup, verify_fixed_setup
from haven_design_batch import archive_design
from rvprobe.backend.process import run
from run_records import save, utc, totals, framework_hashes
from sequence_framework import ROOT

METHODS=('directed_stimulus','directed_sva','haven','rvprobe')
PROFILE=dict(model='deepseek-v4-flash',reasoning_effort='high',max_tokens=65536,
             temperature=0.3,rounds=3,attempts=3,request_timeout=1200,
             intents_per_round=4,sequences_per_intent=4,jg_time_limit='120s',
             sampling_time_limit='30s',sampling_seed=20260906)


def cells(repeats):
    # Same method-independent design order and no second repeat before the
    # first has been dispatched. All 128 cells remain independently auditable.
    return [(r,m,d) for r in range(1,repeats+1) for d in DESIGNS for m in METHODS]


def key(cell):
    r,m,d=cell
    return f'repeat-{r}/{m}/{d}'


def command(cell,work,ready,args):
    r,method,design=cell
    directory=work/key(cell)
    setup=json.loads(Path(ready[design]).read_text())
    common=['--env-file',str(args.env_file),'--encoded-witness-yosys',str(args.yosys),
            '--model',PROFILE['model'],'--timeout',str(PROFILE['request_timeout']),
            '--rounds',str(PROFILE['rounds'])]
    if method in ('haven','rvprobe'):
        argv=[sys.executable,str(ROOT/'experiments/haven_event_paired.py'),
              '--stage1-run',setup['stage1'],'--haven-root',setup['haven_snapshot'],
              '--shared-stage1-environment','--arm',method,'--out',str(directory/'flow'),
              '--sequences-per-intent','4',*common]
        if method=='haven':
            argv+=['--haven-max-tokens','65536','--haven-reasoning-effort','high']
        else:
            argv+=['--rvprobe-max-tokens','65536','--rvprobe-reasoning-effort','high',
                   '--rvprobe-request-timeout','1200','--rvprobe-dialogue-policy','incremental']
        return argv
    return [sys.executable,str(ROOT/'experiments/directed_experiment.py'),
            '--fixed-setup',ready[design],'--haven-root',setup['haven_snapshot'],
            '--out',str(directory),'--method',method,'--max-tokens','65536',
            '--reasoning-effort','high','--temperature','0.3',*common]


def price(costs):
    if not costs['requests']:
        return '0'
    breakdown=costs['usage_breakdown']
    if (not costs['token_accounting_complete'] or
        any(not breakdown[k]['complete'] for k in ('prompt_cache_hit_tokens','prompt_cache_miss_tokens'))):
        return None
    hit=breakdown['prompt_cache_hit_tokens']['reported_tokens']
    miss=breakdown['prompt_cache_miss_tokens']['reported_tokens']
    out=costs['usage_reported']['completion_tokens']
    return str((Decimal(hit)*Decimal('.02')+Decimal(miss)+Decimal(out)*4)/1_000_000)


def cell_result(cell,directory):
    method=cell[1]
    path=directory/('flow/paired/summary.json' if method in ('haven','rvprobe') else 'summary.json')
    data=json.loads(path.read_text()) if path.is_file() else {}
    arm=data.get('arms',{}).get(method,{}) if method in ('haven','rvprobe') else data
    baseline=data.get('baseline') or arm.get('baseline') or {}
    final=arm.get('final') or baseline
    costs=totals(directory)
    return dict(status=arm.get('status','failed'),error=arm.get('error') or data.get('error'),
        failure_kind=arm.get('failure_kind'),rounds=len(arm.get('rounds',[])),
        stop_reason=arm.get('stop_reason'),coverage=final.get('score'),baseline=baseline.get('score'),
        source_summary=str(path),costs=costs,cost_cny=price(costs))


def write_reports(archive,state):
    fields=['repeat','method','design','status','rounds','baseline','coverage','calls',
            'input_tokens','output_tokens','cache_hit_input','cache_miss_input','cost_cny',
            'wall_seconds','failure_kind','error','source_summary']
    rows=[]
    for name,item in state['cells'].items():
        r,m,d=name.split('/')
        costs=item.get('costs',{});usage=costs.get('usage_reported',{})
        bd=costs.get('usage_breakdown',{})
        rows.append(dict(repeat=int(r.split('-')[1]),method=m,design=d,
            **{k:item.get(k) for k in ('status','rounds','baseline','coverage','cost_cny','failure_kind','error','source_summary')},
            calls=costs.get('requests'),input_tokens=usage.get('prompt_tokens'),output_tokens=usage.get('completion_tokens'),
            cache_hit_input=bd.get('prompt_cache_hit_tokens',{}).get('reported_tokens'),
            cache_miss_input=bd.get('prompt_cache_miss_tokens',{}).get('reported_tokens'),wall_seconds=item.get('elapsed_seconds')))
    with (archive/'results.csv').open('w',newline='') as stream:
        writer=csv.DictWriter(stream,fieldnames=fields);writer.writeheader();writer.writerows(rows)
    lines=['# Two fresh four-way repeats','',
        'Each row is one independent new run. Coverage uses last-valid or baseline; all reported requests count.',
        'Missing provider usage is unknown. Reasoning is already included in output tokens.',
        '', '| Repeat | Method | Ended / 16 | Failed | Mean coverage (%) | Known cost (CNY) |',
        '| --- | --- | --- | --- | --- | --- |']
    for r in range(1,state['repeats']+1):
        for m in METHODS:
            ended=[row for row in rows if row['repeat']==r and row['method']==m and row['status'] in ('completed','failed')]
            coverage=[row['coverage'] for row in ended if row['coverage'] is not None]
            mean=f'{sum(coverage)/len(coverage):.6f}' if len(coverage)==16 else 'pending'
            cost=sum((Decimal(row['cost_cny']) for row in ended if row['cost_cny'] is not None),Decimal(0))
            lines.append(f'| {r} | {m} | {len(ended)} | {sum(row["status"]=="failed" for row in ended)} | {mean} | {cost} |')
    (archive/'results.md').write_text('\n'.join(lines)+'\n')


def source_snapshot(destination):
    """Index source files in a new repo; Nix must never ingest Mill's out/ tree."""
    destination.mkdir(parents=True,exist_ok=False)
    paths=subprocess.run(['git','ls-files','-co','--exclude-standard','-z'],cwd=ROOT,
                         text=True,capture_output=True,check=True).stdout.split('\0')
    copied={}
    for relative in sorted(set(paths)-{''}):
        path=ROOT/relative
        if not path.is_file() or path.is_symlink():
            continue
        if path.name=='.env' or path.name.startswith('.env.') or relative.startswith(('out/','.git/','.bsp/')):
            continue
        target=destination/relative;target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(path,target)
        copied[relative]=hashlib.sha256(path.read_bytes()).hexdigest()
    # No main-worktree index or commit is changed; this index is the Nix source filter.
    subprocess.run(['git','init','-q',str(destination)],check=True)
    subprocess.run(['git','add','--all'],cwd=destination,check=True)
    return copied


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('archive','work','stage1-map','env-file','yosys'):
        p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--repeats',type=int,default=2)
    p.add_argument('--jobs',type=int,default=3)
    p.add_argument('--prepare',action='store_true')
    args=p.parse_args()
    if not 1<=args.jobs<=3 or args.repeats!=2:p.error('this protocol requires two repeats and at most three global slots')
    archive,work=args.archive.resolve(),args.work.absolute()
    if work.exists() or work.is_symlink():raise FileExistsError('fresh scratch root required')
    if args.prepare:
        archive.mkdir(parents=True,exist_ok=False)
        copied=source_snapshot(archive/'framework')
        save(archive/'source-files.json',copied)
        save(archive/'protocol.json',dict(created_utc=utc(),profile=PROFILE,repeats=args.repeats,
            jobs=args.jobs,stage1_map=str(args.stage1_map.resolve()),stage1_map_sha256=hashlib.sha256(args.stage1_map.read_bytes()).hexdigest(),
            work=str(work),archive=str(archive),methods=METHODS,designs=DESIGNS,cells=128,
            rates_cny_per_million=dict(cache_hit_input=.02,cache_miss_input=1,output=4),
            no_history_reuse=True,stage1_model_calls=0,source_root=str(archive/'framework'),
            repair_policy='bounded-model-source-and-solver-feedback; no provider/infra semantic repair',
            haven_change='Only explicit provider configuration; unchanged prompt/DSL/templates/coverage policy'))
        print('Prepared clean source snapshot; no model requests');return
    protocol=json.loads((archive/'protocol.json').read_text())
    if ROOT.resolve()!=(archive/'framework').resolve() or protocol['profile']!=PROFILE:
        raise ValueError('run only the frozen protocol and source snapshot')
    hashes=json.loads((archive/'source-files.json').read_text())
    def check_source():
        for name,expected in hashes.items():
            if hashlib.sha256((ROOT/name).read_bytes()).hexdigest()!=expected:
                raise ValueError('frozen source changed: '+name)
    check_source()
    if protocol['stage1_map_sha256']!=hashlib.sha256(args.stage1_map.read_bytes()).hexdigest():
        raise ValueError('Stage-1 map changed')
    if (protocol['jobs'],protocol['work'])!=(args.jobs,str(work)):
        raise ValueError('frozen scheduling configuration changed')
    ready=json.loads(args.stage1_map.read_text()); identities={}
    for name in DESIGNS:
        _,identities[name]=load_fixed_setup(Path(ready[name]));verify_fixed_setup(identities[name])
    receipt=json.loads((archive/'preflight.json').read_text())
    if receipt.get('status')!='passed' or receipt.get('framework')!=framework_hashes(ROOT):
        raise ValueError('matching-source offline preflight required before payment')
    work.mkdir(parents=True)
    stop=archive/'STOP_NEW_REQUESTS'
    pending=cells(args.repeats)
    state=dict(status='running',started_utc=utc(),repeats=args.repeats,jobs=args.jobs,
               profile=PROFILE,cells={key(c):{'status':'queued'} for c in pending})
    def checkpoint():
        state['updated_utc']=utc();save(archive/'progress.json',state);write_reports(archive,state)
    def execute(cell):
        name=key(cell);directory=work/name
        directory.parent.mkdir(parents=True,exist_ok=True)
        temporary=work/'tmp'/name;temporary.mkdir(parents=True)
        log=temporary/'process.log';start=time.monotonic()
        record=dict(status='running',started_utc=utc(),command=command(cell,work,ready,args))
        try:
            check_source();verify_fixed_setup(identities[cell[2]])
            env={**os.environ,'TMPDIR':str(temporary),'RVPROBE_STORAGE_WORK_ROOT':str(work),
                 'RVPROBE_STORAGE_ARCHIVE_ROOT':str(archive), 'RVPROBE_STOP_NEW_MODEL_REQUESTS':str(stop)}
            with log.open('x') as stream:
                result=run(record['command'],cwd=ROOT,env=env,stdout=stream,stderr=-2,timeout=13*3600)
            record.update(cell_result(cell,directory),exit_code=result.returncode)
            if result.returncode or record['status'] not in ('completed','failed'):record['status']='failed'
            verify_fixed_setup(identities[cell[2]])
        except Exception as error:
            if directory.exists():record.update(cell_result(cell,directory))
            record.update(status='failed',error=str(error))
        finally:
            record.update(finished_utc=utc(),elapsed_seconds=time.monotonic()-start)
            directory.mkdir(parents=True,exist_ok=True)
            if temporary.exists():shutil.move(str(temporary),str(directory/'temporary'))
            try:
                archive_design(directory,archive/name,record,True)
                record.update(storage_status='relocated',archive=str(archive/name))
            except Exception as error:
                record.update(storage_status='archive_failed_local_retained',archive_error=str(error))
        return record
    active={};checkpoint()
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        while pending or active:
            if stop.exists():
                for c in pending:state['cells'][key(c)]={'status':'cancelled_before_start'}
                pending=[]
            while pending and len(active)<args.jobs:
                if shutil.disk_usage('/nix/store').free<3*1024**3 or shutil.disk_usage(work).free<2*1024**3:
                    state['storage_wait']='Need 3 GiB on Nix disk and 2 GiB scratch; no new requests dispatched'
                    break
                state.pop('storage_wait',None)
                c=pending.pop(0);state['cells'][key(c)]={'status':'running','started_utc':utc()}
                active[pool.submit(execute,c)]=c
            checkpoint()
            if active:
                done,_=wait(active,timeout=30,return_when=FIRST_COMPLETED)
                for future in done:
                    c=active.pop(future)
                    try:state['cells'][key(c)]=future.result()
                    except Exception as error:state['cells'][key(c)]={'status':'failed','error':str(error)}
            elif pending:time.sleep(30)
    state.update(status='stopped_by_user' if stop.exists() else 'finished',finished_utc=utc())
    checkpoint();save(archive/'summary.json',state)
    print(json.dumps({'status':state['status'],'terminal_cells':len(state['cells']),'report':str(archive/'results.md')}),flush=True)


if __name__=='__main__':main()
