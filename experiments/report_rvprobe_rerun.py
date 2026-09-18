"""Report an explicit new RVProbe cohort beside immutable earlier results."""
import argparse
import csv
import io
import json
from pathlib import Path

from report_paired_batch import run_record,read
from run_records import save,utc


def export(root,reference):
    root=Path(root);reference=Path(reference)
    batch=read(root/'progress.json');prior=read(reference)
    expected=batch['rvprobe_generation_options'];rows=[]
    for design,state in batch['designs'].items():
        old=next(r for r in prior['rows'] if r['design']==design and r['method']=='rvprobe')
        run=run_record(root/design/'flow');arm=run['arms']['rvprobe']
        requests={}
        for p in (root/design/'flow/paired/rvprobe').rglob('events.jsonl'):
            for line in p.read_text().splitlines():
                try:event=json.loads(line)
                except json.JSONDecodeError:continue
                if event.get('phase')=='model-request':requests[event['id']]=event
        for event in requests.values():
            if (event.get('requested_max_tokens')!=expected['max_tokens'] or
                event.get('request_timeout_seconds')!=expected['request_timeout_seconds']):
                raise ValueError('request budget does not match new cohort configuration')
            effort=expected.get('reasoning_effort')
            if effort in ('low','high','max') and event.get('requested_reasoning_effort')!=effort:
                raise ValueError('request reasoning effort does not match new cohort configuration')
        completions=[e.get('usage',{}).get('completion_tokens') for e in requests.values()]
        completions=[v for v in completions if v is not None]
        current=arm['score']
        error=arm['error'] or state.get('error')
        failure_evidence=None
        generation_reports=[]
        for path in (root/design/'flow/paired/rvprobe').glob('round-*/generation/summary.json'):
            summary=read(path)
            if summary.get('status')=='failed':generation_reports.append((summary.get('finished_utc',''),path,summary))
        if generation_reports:
            _,path,summary=max(generation_reports,key=lambda item:item[0])
            error=summary.get('error') or error;failure_evidence=str(path)
            history=summary.get('history',[])
            if history:
                solve=path.parent/f"attempt-{history[-1]['attempt']}"/'solve/report.json'
                errors=[g.get('detail') for g in read(solve).get('goals',[]) if g.get('status')=='error']
                if errors:error=str(errors[0]);failure_evidence=str(solve)
        rows.append(dict(design=design,status=arm['status'] if arm['status']!='not_started' else state['status'],
            previous_status=old['status'],previous_coverage=old['coverage'],coverage=current,
            coverage_gain=current-old['coverage'] if current is not None and old['coverage'] is not None else None,
            previous_tokens=old['tokens'],tokens=arm['tokens'],usage_complete=arm['token_accounting_complete'],
            rounds=arm['accepted_rounds'],previous_rounds=old['rounds'],
            seconds=arm['elapsed_seconds'],previous_seconds=old['seconds'],
            requests=len(requests),max_observed_completion_tokens=max(completions,default=None),
            requests_above_64k=sum(v>65536 for v in completions),
            truncated_requests=sum(e.get('finish_reason')=='length' for e in requests.values()),
            error=error,failure_evidence=failure_evidence,summary=arm['source'],previous_summary=old['summary']))
    result=dict(updated_utc=utc(),batch_status=batch['status'],generation_options=expected,rows=rows,
        reference_report=str(reference),notes=[
            'New full runs, not continuation or replacement of earlier failures; all new costs retained.',
            'Both output budget and request timeout differ. New sampled model outputs also differ.',
            'A response below 64K does not prove that a gateway honors the higher limit.',
            'seconds is per-arm Stage-2 wall time; failed coverage is last accepted coverage; missing usage stays unknown.'])
    save(root/'budget-comparison.json',result)
    stream=io.StringIO();writer=csv.DictWriter(stream,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
    (root/'budget-comparison.csv').write_text(stream.getvalue())
    text=['# RVProbe larger-budget diagnostic','',f"Updated {result['updated_utc']}; batch {batch['status']}.",'',
        '| Design | Status | Old coverage % | New coverage % | Old tokens | New tokens | Rounds | Stage-2 s | Max completion tokens | Requests >64K |',
        '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|']
    def fmt(value,digits=2):return '—' if value is None else f'{value:.{digits}f}'
    for row in rows:
        text.append(f"| {row['design']} | {row['status']} | {fmt(row['previous_coverage'])} | {fmt(row['coverage'])} | {row['previous_tokens']} | {row['tokens']} | {row['rounds']} | {fmt(row['seconds'],1)} | {row['max_observed_completion_tokens']} | {row['requests_above_64k']} |")
    text+=['','## Notes','']+['- '+n for n in result['notes']]
    (root/'budget-comparison.md').write_text('\n'.join(text)+'\n')
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,required=True);parser.add_argument('--reference',type=Path,required=True)
    args=parser.parse_args();result=export(args.root,args.reference)
    print(json.dumps(result['rows']))
