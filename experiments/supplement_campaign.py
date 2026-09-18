"""Fixed-opportunity SV-constraint comparison and RVProbe feedback ablations.

No Stage-1 generation, no HAVEN changes, no cache-policy experiment. Failed
cells remain in the report; storage failure stops admission, not archival.
"""
import backend_imports
import argparse
import csv
import io
import json
import os
from pathlib import Path
import sys
import threading
import time

from batch_lifecycle import current_owner, process_identity
from design_inventory import DESIGNS
from experiment_storage import require_space
from frozen_stage1 import load_fixed_setup, verify_fixed_setup
from haven_design_batch import bounded_designs, archive_design
from run_records import framework_hashes, fresh_directory, save, totals, utc
from rvprobe.backend.process import run
from sequence_framework import ROOT

VARIANTS=('directed_sv_constraint','rvprobe_full','rvprobe_no_diagnostics','rvprobe_no_coverage')
DEFAULT_VARIANTS=tuple(v for v in VARIANTS if v!='directed_sv_constraint')


def command(variant, setup, record, directory, args):
    common=['--env-file',str(args.env_file),'--encoded-witness-yosys',str(args.yosys)]
    if variant=='directed_sv_constraint':
        return [sys.executable,str(ROOT/'experiments/directed_experiment.py'),
            '--fixed-setup',str(record),'--haven-root',setup['haven_snapshot'],
            '--out',str(directory/'experiment'),'--method',variant,'--fixed-rounds',
            '--max-tokens','393216','--reasoning-effort','max','--timeout','3600',*common]
    if variant not in VARIANTS:raise ValueError('unknown variant')
    return [sys.executable,str(ROOT/'experiments/haven_event_paired.py'),
        '--stage1-run',setup['stage1'],'--haven-root',setup['haven_snapshot'],
        '--out',str(directory/'flow'),'--arm','rvprobe','--shared-stage1-environment',
        '--boundary','independent-dut-v1','--rvprobe-feedback-mode',variant.removeprefix('rvprobe_'),
        '--rvprobe-fixed-rounds','--rvprobe-dialogue-policy','staged',
        '--rvprobe-max-tokens','393216','--rvprobe-request-timeout','3600',
        '--rvprobe-reasoning-effort','max',*common]


def read(path):
    return json.loads(path.read_text()) if path.is_file() else {}


def retained_cells(prior, keys):
    """Reuse terminal cells only, never resend an interrupted paid request."""
    result={}
    for key in keys:
        cell=prior.get('cells',{}).get(key,{})
        if cell.get('status') in ('queued','not_started',None):continue
        if cell.get('status') not in ('completed','failed') or cell.get('storage_status')!='relocated':
            raise ValueError('predecessor cell is not safely archived: '+key)
        result[key]=dict(cell)
    return result


def cell_metrics(directory, variant):
    root=directory/('experiment' if variant=='directed_sv_constraint' else 'flow/paired')
    path=root/'summary.json'
    if not path.is_file():path=root/'progress.json'
    result=read(path)
    arm=result if variant=='directed_sv_constraint' else result.get('arms',{}).get('rvprobe',{})
    rounds=arm.get('rounds',[])
    baseline=result.get('baseline') or {}
    final=arm.get('final') or (rounds[-1].get('coverage') if rounds else None) or baseline
    costs=totals(directory)
    return dict(status=arm.get('status',result.get('status','failed')),
        error=arm.get('error') or result.get('error'),coverage=final.get('score'),
        coverage_percent=final.get('percent'),baseline_coverage=baseline.get('score'),
        baseline_only=not any(r.get('added_sequences',0)>0 for r in rounds),
        recorded_rounds=len(rounds),accepted_rounds=sum(r.get('added_sequences',0)>0 for r in rounds),
        attempted_rounds=max([int(p.name.split('-')[1]) for p in
            (root if variant=='directed_sv_constraint' else root/'rvprobe').glob('round-*')],default=0),
        stage2_seconds=arm.get('stage2_seconds',arm.get('elapsed_seconds')),
        tokens=costs['usage_reported']['total_tokens'],requests=costs['requests'],
        token_accounting_complete=costs['token_accounting_complete'],costs=costs,summary=str(path))


def export(archive,state):
    rows=[]
    for key,cell in state['cells'].items():
        variant,design=key.split('/')
        rows.append(dict(variant=variant,design=design,**{k:cell.get(k) for k in (
            'status','coverage','baseline_coverage','baseline_only','tokens','token_accounting_complete',
            'requests','attempted_rounds','accepted_rounds','stage2_seconds','elapsed_seconds','error','storage_status')}))
    save(archive/'results.json',dict(updated_utc=utc(),status=state['status'],rows=rows,protocol=state['protocol']))
    stream=io.StringIO();writer=csv.DictWriter(stream,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
    (archive/'results.csv').write_text(stream.getvalue())
    lines=['# Supplementary experiments','',
        'Fixed 3-round opportunities; failures retain last valid cumulative coverage (baseline if none).',
        'Stage 1 is shared, frozen infrastructure; its historical generation cost is excluded.',
        'Tokens include failed attempts. Missing usage stays unknown. Concurrent wall times include contention.','',
        '| Variant | Design | Status | Coverage % | Tokens | Attempted / accepted rounds | Stage-2 s |',
        '|---|---|---|---:|---:|---:|---:|']
    def fmt(value):return '—' if value is None else str(round(value,2)) if isinstance(value,float) else str(value)
    for r in rows:
        lines.append('| '+' | '.join(fmt(v) for v in (r['variant'],r['design'],r['status'],r['coverage'],r['tokens'],
            f"{fmt(r['attempted_rounds'])} / {fmt(r['accepted_rounds'])}",r['stage2_seconds']))+' |')
    (archive/'results.md').write_text('\n'.join(lines)+'\n')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('work','archive','stage1-map','env-file','yosys'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--jobs',type=int,default=3)
    parser.add_argument('--designs',nargs='+',choices=DESIGNS,default=list(DESIGNS))
    parser.add_argument('--variants',nargs='+',choices=VARIANTS,default=list(DEFAULT_VARIANTS))
    parser.add_argument('--continue-from',type=Path,
        help='Wait for a draining campaign; retain archived selected cells, dispatch only unstarted cells')
    args=parser.parse_args()
    if args.jobs<1 or len(set(args.designs))!=len(args.designs) or len(set(args.variants))!=len(args.variants):
        parser.error('positive jobs and unique cells required')
    args.work=args.work.resolve();args.archive=args.archive.resolve()
    if args.work==args.archive or args.work in args.archive.parents or args.archive in args.work.parents:
        parser.error('independent new work/archive directories required')
    prior=None
    if args.continue_from:
        predecessor=args.continue_from.resolve()
        prior=read(predecessor/'progress.json')
        if not prior:parser.error('predecessor progress is missing')
        print('Waiting for predecessor to archive its active cells: '+str(predecessor),flush=True)
        while process_identity(prior.get('pid'))==prior.get('owner') and prior.get('owner'):
            time.sleep(15)
            prior=read(predecessor/'progress.json')
        if prior.get('status') not in ('finished','blocked'):
            parser.error('predecessor exited without terminal accounting; do not resend its requests')
        current_framework=framework_hashes(ROOT)
        changes={k for k in set(prior['framework'])|set(current_framework)
                 if prior['framework'].get(k)!=current_framework.get(k)}
        if changes-{'experiments/supplement_campaign.py'}:
            parser.error('continuation changed experimental implementation, not just scheduling: '+str(changes))
    ready=read(args.stage1_map)
    fixed={name:load_fixed_setup(Path(ready[name])) for name in args.designs}
    if any(not identity.get('shared_environment') for _,identity in fixed.values()):
        parser.error('all designs must have explicitly admitted shared Stage-1 environments')
    work=fresh_directory(args.work);archive=fresh_directory(args.archive)
    os.environ['RVPROBE_STORAGE_WORK_ROOT']=str(work)
    os.environ['RVPROBE_STORAGE_ARCHIVE_ROOT']=str(archive)
    keys=[v+'/'+d for d in args.designs for v in args.variants]
    protocol=dict(model='deepseek-v4-flash-vision-exp',temperature=0.3,reasoning_effort='max',
        max_tokens=393216,request_timeout_seconds=3600,dialogue_policy='staged',
        rounds=3,fixed_round_opportunities=True,source_attempts=3,runtime_repairs_per_round=1,
        intents_per_round=4,sequences_per_intent=4,seed=20260906,jg_seconds=120,resampling_seconds=30,
        constraint_compile_seconds=300,constraint_solve_seconds=120,
        stage1_model_calls=0,stage1_map=str(args.stage1_map.resolve()),
        coverage_policy='last valid cumulative native simulation; baseline if zero accepted additions',
        stopping_policy='fixed opportunities, except terminal framework/provider/repair-budget failures',
        limitations=['Single seed; stochastic comparison, not statistical significance.',
            'No cache experiment; staged dialogue frozen across ablations.',
            'SV randomize solves input-array constraints without DUT transition relation.',
            'Historical early-stopping runs are not the controlled feedback-ablation reference.',
            'Shared Stage-1 provenance includes human diagnostic adaptations; not wholly model-authored.'])
    state=dict(status='running',pid=os.getpid(),owner=current_owner(),started_utc=utc(),jobs=args.jobs,
        expected_cells=len(keys),framework=framework_hashes(ROOT),protocol=protocol,
        cells={key:{'status':'queued'} for key in keys})
    if prior is not None:
        if any(prior['protocol'].get(k)!=protocol[k] for k in protocol):
            parser.error('predecessor experimental settings differ')
        retained=retained_cells(prior,keys)
        for key,cell in retained.items():
            source=Path(cell['archive']).resolve()
            if not (source/'design-result.json').is_file():raise ValueError('missing retained archive: '+key)
            destination=archive/key;destination.parent.mkdir(parents=True,exist_ok=True)
            destination.symlink_to(source,target_is_directory=True)
            cell.update(retained_from=str(predecessor),original_framework=prior['framework'])
            state['cells'][key]=cell
        state['continuation']=dict(predecessor=str(predecessor),retained_cells=list(retained),
            excluded_variants=sorted({k.split('/')[0] for k in prior['cells']}-set(args.variants)),
            reason='User cancelled Direct SV Constraint; preserve in-flight RVProbe work without resending')
    lock=threading.RLock()
    def checkpoint():
        with lock:
            state['updated_utc']=utc();save(work/'progress.json',state);save(archive/'progress.json',state)
            export(archive,state)
    def update(key,**fields):
        with lock:state['cells'][key].update(fields);checkpoint()
    checkpoint()
    save(archive/'fixed-identities.json',{name:value[1] for name,value in fixed.items()})
    def execute(key):
        variant,name=key.split('/');directory=work/variant/name
        directory.mkdir(parents=True);temp=directory/'tmp';temp.mkdir()
        began=time.monotonic();setup,identity=fixed[name]
        update(key,status='running',started_utc=utc())
        try:
            verify_fixed_setup(identity)
            argv=command(variant,setup,ready[name],directory,args)
            save(directory/'command.json',argv)
            with (directory/'process.log').open('x') as stream:
                result=run(argv,cwd=ROOT,env={**os.environ,'TMPDIR':str(temp)},
                    stdout=stream,stderr=-2,timeout=13*3600)
            metrics=cell_metrics(directory,variant)
            if result.returncode or metrics['status'] not in ('completed','failed'):
                metrics.update(status='failed',error=metrics.get('error') or f'process exit {result.returncode}')
            update(key,**metrics,exit_code=result.returncode)
        except Exception as error:
            update(key,**{**cell_metrics(directory,variant),'status':'failed','error':str(error)})
        finally:
            try:verify_fixed_setup(identity)
            except Exception as error:update(key,status='failed',fixed_stage1_error=str(error))
            update(key,finished_utc=utc(),elapsed_seconds=time.monotonic()-began)
            try:
                update(key,storage_status='archiving')
                archive_design(directory,archive/variant/name,dict(state['cells'][key]),True)
                update(key,storage_status='relocated',archive=str(archive/variant/name))
            except Exception as error:
                update(key,storage_status='failed_local_retained',archive_error=str(error))
                return f'{key}: archive failure, scratch retained: {error}'
        return None
    def admit(active):
        require_space(work,minimum=(active+1)*1024**3)
        if framework_hashes(ROOT)!=state['framework']:
            raise OSError('framework changed during controlled campaign; no more cells admitted')
    pending,error=bounded_designs([key for key in keys if state['cells'][key]['status']=='queued'],execute,args.jobs,admit)
    for key in pending:state['cells'][key].update(status='not_started',error=error)
    state.update(status='blocked' if error else 'finished',finished_utc=utc(),admission_error=error)
    checkpoint();save(archive/'summary.json',state)
    print(json.dumps({'status':state['status'],'cells':len(keys)-len(pending),'archive':str(archive)}),flush=True)
    return int(bool(error))


if __name__=='__main__':raise SystemExit(main())
