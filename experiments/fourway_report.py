"""Audit all 64 prespecified cells, including failures and unstarted work."""
import argparse
import csv
import io
import json
from pathlib import Path
from design_inventory import DESIGNS
from run_records import save, utc

METHODS=('directed_stimulus','directed_sva','haven','rvprobe')


def read(path):
    return json.loads(path.read_text()) if path.is_file() else None


def cell(root,method,design,ethmac,continuations=None):
    base=(ethmac/'ethmac' if method=='rvprobe' and design=='ethmac' else root/method/design)
    summary_path=base/('flow/paired/summary.json' if method in ('haven','rvprobe') else 'summary.json')
    original_summary=summary_path
    override=(continuations or {}).get(method+'/'+design)
    if override:
        if method not in ('directed_stimulus','directed_sva'):
            raise ValueError('only explicitly resumed direct cells can be overlaid')
        summary_path=Path(override)
        resumed=read(summary_path)
        if not resumed or Path(resumed['prior_summary']).resolve()!=original_summary.resolve():
            raise ValueError('continuation does not belong to this original cell')
        base=summary_path.parent
    summary=read(summary_path)
    marker=read(base/'design-result.json')
    row=dict(design=design,method=method,status='not_started',coverage=None,tokens=None,
             requests=None,rounds=0,accepted_rounds=0,attempted_rounds=0,seconds=None,total_seconds=None,
             usage_complete=None,error=None,summary=str(summary_path),bins=None,baseline_bins=None)
    if summary is None:
        if marker:row.update(status=marker['status'],error=marker.get('error'),total_seconds=marker.get('elapsed_seconds'))
        else:
            if method in ('haven','rvprobe'):
                batch=ethmac if method=='rvprobe' and design=='ethmac' else root/method
                progress=read(batch/'progress.json') or {}
                item=progress.get('designs',{}).get(design,{})
                if item:
                    status=item.get('status','not_started')
                    row.update(status='running' if status.startswith(('running','validating')) else status,error=item.get('error'))
            else:
                progress=read(root/'direct-progress.json') or {}
                item=progress.get('cells',{}).get(method+'/'+design,{})
                if item:row.update(status=item.get('status','not_started'),error=item.get('error'))
        return row
    arm=summary.get('arms',{}).get(method,summary)
    costs=arm.get('costs',summary.get('costs',{}))
    final=arm.get('final') or {};baseline=summary.get('baseline') or arm.get('baseline') or {}
    rounds=arm.get('rounds',[])
    round_root=base/'flow/paired'/method if method in ('haven','rvprobe') else base/'experiment'
    attempted={p.name.split('-')[1] for p in round_root.glob('round-*') if p.is_dir()}
    row.update(status=arm.get('status',summary['status']),coverage=final.get('score'),bins=final.get('bins'),
        baseline_bins=baseline.get('bins'),tokens=costs.get('usage_reported',{}).get('total_tokens'),
        requests=costs.get('requests'),rounds=len(rounds),
        accepted_rounds=sum(r.get('status')!='no_validated_sequences' for r in rounds),
        attempted_rounds=max(len(attempted),len(rounds),arm.get('failed_round') or 0),
        seconds=arm.get('elapsed_seconds') if method in ('haven','rvprobe') else summary.get('stage2_seconds'),
        total_seconds=summary.get('elapsed_seconds'),usage_complete=costs.get('token_accounting_complete'),
        error=arm.get('error',summary.get('error')),stop_reason=arm.get('stop_reason'),
        unresolved_intents=[g for r in rounds for g in r.get('metadata',{}).get('unresolved_intents',[])])
    row['failure_kind']=arm.get('failure_kind',summary.get('failure_kind'))
    if override:
        row.update(prior_summary=str(original_summary),
            incremental_tokens=summary['incremental_costs']['usage_reported']['total_tokens'],
            prior_tokens=summary['prior_costs']['usage_reported']['total_tokens'])
    if row['status']=='failed' and method=='rvprobe' and arm.get('failed_round'):
        failed=[]
        for path in round_root.glob(f"round-{arm['failed_round']}*/generation/summary.json"):
            child=read(path)
            if child and child.get('status')=='failed':failed.append((child.get('finished_utc',''),path,child))
        if failed:
            _,path,child=max(failed,key=lambda r:r[0])
            row.update(outer_error=row['error'],error=child.get('error',row['error']),
                       failure_kind=child.get('failure_kind'),failure_evidence=str(path))
            if not child.get('error'):
                events=path.with_name('events.jsonl')
                for line in events.read_text().splitlines() if events.is_file() else []:
                    try:event=json.loads(line)
                    except json.JSONDecodeError:continue
                    if event.get('phase')=='model-request' and event.get('status')=='failed':
                        row.update(error=event.get('error') or event.get('error_type') or row['error'],
                            failure_kind=event.get('failure_kind') or event.get('error_type'),
                            failure_evidence=str(events))
    return row


def export(root,ethmac,continuations=None,output=None):
    root=Path(root);output=Path(output) if output else root
    output.mkdir(parents=True,exist_ok=True)
    allowed={m+'/'+d for d in DESIGNS for m in METHODS}
    if set(continuations or {})-allowed:raise ValueError('unknown continuation cell')
    rows=[cell(root,method,design,Path(ethmac),continuations) for design in DESIGNS for method in METHODS]
    denominator_issues=[];baseline_issues=[]
    for design in DESIGNS:
        group=[r for r in rows if r['design']==design and r['bins']]
        shapes={json.dumps({m:v[0] for m,v in r['bins'].items()},sort_keys=True) for r in group}
        if len(shapes)>1:denominator_issues.append(design)
        baselines={json.dumps(r['baseline_bins'],sort_keys=True) for r in group if r['baseline_bins']}
        if len(baselines)>1:baseline_issues.append(design)
    result=dict(updated_utc=utc(),expected_cells=64,terminal_cells=sum(r['status'] in ('completed','failed') for r in rows),
        completed_cells=sum(r['status']=='completed' for r in rows),rows=rows,denominator_mismatches=denominator_issues,
        baseline_mismatches=baseline_issues,
        notes=['All prespecified cells retained; failed cells show last accepted coverage, not zero or a guessed final result.',
               'Stage 1 is fixed experimental environment; no Stage-1 LLM costs included.',
               'seconds is Stage-2 wall time; total_seconds also includes baseline/setup.',
               'Direct stimulus is deterministic model-authored input events; no solver or intent-satisfaction certificate.',
               'Direct SVA and RVProbe share native solving, sampling, known-state correction and original-cover replay.',
               'HAVEN keeps its fixed native Stage-2 interface and prompt; its context differs from IO-only frontends.',
               'RVProbe ETHMAC is the already running post-fix run, not a success-selected historical replacement.',
               'Missing provider usage stays unknown; reported tokens may be a subtotal when usage_complete is false.'])
    result['notes'].append('Completed means the runner reached a normal terminal condition; model_stop or no_generated_sequences does not certify new stimulus or intent satisfaction. Accepted rounds exclude no_validated_sequences; attempted rounds include interrupted and STOP rounds.')
    result['continuations']=continuations or {}
    aggregates=[]
    for method in METHODS:
        group=[r for r in rows if r['method']==method]
        scores=[r['coverage'] for r in group if r['coverage'] is not None]
        known_tokens=[r['tokens'] for r in group if r['tokens'] is not None]
        times=[r['seconds'] for r in group if r['seconds'] is not None]
        aggregates.append(dict(method=method,completed=sum(r['status']=='completed' for r in group),
            failed=sum(r['status']=='failed' for r in group),
            mean_last_accepted_coverage=sum(scores)/len(scores) if scores else None,
            coverage_cells=len(scores),reported_tokens=sum(known_tokens) if known_tokens else None,
            usage_incomplete_cells=sum(r['usage_complete'] is not True for r in group),
            recorded_rounds=sum(r['rounds'] for r in group),
            accepted_rounds=sum(r['accepted_rounds'] for r in group),
            attempted_rounds=sum(r['attempted_rounds'] for r in group),
            stage2_seconds=sum(times) if times else None,time_cells=len(times),
            no_generated_sequences=sum(r.get('stop_reason')=='no_generated_sequences' for r in group)))
    result['aggregates']=aggregates
    if continuations:
        result['notes'].append('Explicit HTTP-402 continuations preserve prior rounds and include prior + new costs and active time; original results remain immutable.')
    save(output/'results.json',result)
    fields=['design','method','status','coverage','tokens','requests','rounds','accepted_rounds','attempted_rounds','seconds','total_seconds','usage_complete','failure_kind','error','summary']
    buffer=io.StringIO();writer=csv.DictWriter(buffer,fieldnames=fields,extrasaction='ignore');writer.writeheader();writer.writerows(rows)
    (output/'results.csv').write_text(buffer.getvalue())
    text=['# Four-way comparison', '', f"Updated: {result['updated_utc']}; terminal {result['terminal_cells']}/64; completed {result['completed_cells']}/64.", '',
          'Mean coverage below is the unweighted mean of per-design last accepted scores, including failures; it is not a cross-design bin union.', '',
          '| Method | Completed | Failed | Mean coverage % | Reported tokens | Accepted rounds | Stage-2 hours (sum) | Incomplete usage cells |',
          '|---|---:|---:|---:|---:|---:|---:|---:|']
    for a in aggregates:
        coverage='—' if a['mean_last_accepted_coverage'] is None else f"{a['mean_last_accepted_coverage']:.2f}"
        hours='—' if a['stage2_seconds'] is None else f"{a['stage2_seconds']/3600:.2f}"
        text.append(f"| {a['method']} | {a['completed']} | {a['failed']} | {coverage} | {a['reported_tokens']} | {a['accepted_rounds']} | {hours} | {a['usage_incomplete_cells']} |")
    text += ['',
          '| Design | Method | Status | Coverage % | Tokens | Calls | Rounds | Stage-2 s |',
          '|---|---|---|---:|---:|---:|---:|---:|']
    def fmt(value,digits=2):return '—' if value is None else f'{value:.{digits}f}'
    for r in rows:
        tokens='—' if r['tokens'] is None else str(r['tokens'])+('†' if not r['usage_complete'] else '')
        text.append(f"| {r['design']} | {r['method']} | {r['status']} | {fmt(r['coverage'])} | {tokens} | {r['requests'] if r['requests'] is not None else '—'} | {r['rounds']} | {fmt(r['seconds'],1)} |")
    text+=['','## Interpretation','']+['- '+n for n in result['notes']]
    text+=['','## Recorded failures','']+[f"- {r['design']} / {r['method']}: {r['error']}" for r in rows if r['error']]
    (output/'results.md').write_text('\n'.join(text)+'\n')
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--ethmac',type=Path,required=True)
    p.add_argument('--continuations',type=Path,help='explicit method/design to cumulative-summary path mapping')
    p.add_argument('--output',type=Path,help='separate report directory, preserving original report')
    args=p.parse_args();result=export(args.root,args.ethmac,read(args.continuations) if args.continuations else None,args.output)
    print(json.dumps({k:result[k] for k in ('terminal_cells','completed_cells','denominator_mismatches')}))
