"""Explicit-user continuation of a direct arm rejected at its first HTTP call.

Reuse verified accepted artifacts, never request/charge those rounds again.
Original run is immutable. A new directory records incremental costs and a
separate cumulative report preserves previously unknown provider usage.
"""
import backend_imports
import argparse
from copy import deepcopy
import json
from pathlib import Path
from types import SimpleNamespace
import time

from coverage_flow import load_bundle, HavenSimulation
from directed_baselines import DirectedBackend
from directed_experiment import coverage_loop
from frozen_stage1 import verify_stage1
from isolated_replay import IsolatedSimulation
from run_records import save, fresh_directory, finish, fingerprint, framework_hashes, utc
from cycle_replay import digest
from sequence_experiment import load_env_file
from sequence_framework import ROOT


def restore(source, out):
    summary=json.loads((source/'summary.json').read_text())
    manifest=json.loads((source/'manifest.json').read_text())
    if summary['status']!='failed' or summary.get('error')!='HTTP Error 402: Payment Required':
        raise ValueError('only an explicit HTTP 402 interruption can use this entry point')
    failed=summary['failed_round']
    if failed!=len(summary['rounds'])+1:raise ValueError('non-contiguous accepted rounds')
    interrupted=source/'experiment'/f'round-{failed}'/'generation'
    attempts=list(interrupted.glob('attempt-*'))
    if len(attempts)!=1:raise ValueError('continuation requires untouched first source attempt')
    ad=attempts[0]
    events=[json.loads(s) for s in (ad/'events.jsonl').read_text().splitlines()]
    terminal=[e for e in events if e.get('status')=='failed']
    if (len(terminal)!=1 or terminal[0].get('error_code')!=402 or
        terminal[0].get('tool_step')!=0 or (ad/'response.txt').exists() or
        (ad/'dialogue.json').exists() or list(ad.glob('task-tool-*.json'))):
        raise ValueError('prior request may contain reusable output or ambiguous billing; refusing blind replay')
    identity=json.loads((source/'fixed-stage1.json').read_text());verify_stage1(identity)
    bundle,design,config=load_bundle(source/'shared/bundle.json',source/'implementation/haven')
    sequences=list(bundle['sequences']);frames=[];history=[]
    experiment=out/'experiment';experiment.mkdir()
    for number,row in enumerate(summary['rounds'],1):
        if row['round']!=number or row.get('status')=='no_validated_sequences':
            raise ValueError('unsupported accepted round checkpoint')
        choices=[p for p in (source/'experiment').glob(f'round-{number}*')
                 if (p/'candidate.json').is_file() and (p/'simulation/batches.json').is_file()]
        selected=[p for p in choices if str(p/'simulation') in str(row['coverage']['modinfo']) or
                  (p/'simulation').resolve()==Path(row['coverage']['modinfo']).parent.parent.resolve()]
        if len(selected)!=1:raise ValueError('cannot identify exact accepted round candidate')
        rd=selected[0];candidate=json.loads((rd/'candidate.json').read_text())
        for path,sha in row['coverage']['artifact_sha256'].items():
            if digest(Path(path))!=sha:raise ValueError('accepted coverage artifact changed')
        if len(candidate['sequences'])!=row['added_sequences']:raise ValueError('accepted count mismatch')
        # Check the actual compiled sequences/schedule that underpin the union.
        batches=json.loads((rd/'simulation/batches.json').read_text())
        for measured in batches['runs']:
            for path,sha in measured['artifact_sha256'].items():
                if digest(Path(path))!=sha:raise ValueError('measured artifact changed')
        sequences+=candidate['sequences'];frames+=candidate.get('frames',[])
        history.append({'round':rd.name,'intents':candidate['repair_context']['intents'],
                        'sequence_hashes':[fingerprint(s) for s in candidate['sequences']]})
        (experiment/f'round-{number}').symlink_to(rd,target_is_directory=True)
    from isolated_replay import independent_batches
    independent_batches(bundle,design,sequences,frames)
    for path,sha in summary['baseline']['artifact_sha256'].items():
        if digest(Path(path))!=sha:raise ValueError('baseline artifact changed')
    restored=dict(current=summary['final'],previous=(summary['rounds'][-2]['coverage'] if len(summary['rounds'])>1 else
        summary['baseline'] if summary['rounds'] else None),best=summary['best'],
        baseline=summary['baseline'],rounds=summary['rounds'],sequences=sequences,frames=frames,failed_round=failed)
    return summary,manifest,bundle,design,config,history,restored


def cumulative_costs(prior,new):
    result=deepcopy(new)
    for key in ('requests','requests_without_usage'):
        result[key]=prior.get(key,0)+new.get(key,0)
    result['token_accounting_complete']=bool(prior.get('token_accounting_complete') and new.get('token_accounting_complete'))
    result['usage_reported']={key:sum(v for v in (prior['usage_reported'].get(key),new['usage_reported'].get(key)) if v is not None)
        if any(v is not None for v in (prior['usage_reported'].get(key),new['usage_reported'].get(key))) else None
        for key in ('prompt_tokens','completion_tokens','total_tokens')}
    result['reported_models']=sorted(set(prior.get('reported_models',[])+new.get('reported_models',[])))
    for key in set(prior.get('usage_breakdown',{}))|set(new.get('usage_breakdown',{})):
        a=prior.get('usage_breakdown',{}).get(key,{});b=new.get('usage_breakdown',{}).get(key,{})
        values=[x.get('reported_tokens') for x in (a,b)]
        result.setdefault('usage_breakdown',{})[key]={
            'reported_tokens':sum(x for x in values if x is not None) if any(x is not None for x in values) else None,
            'requests_with_usage':sum(x.get('requests_with_usage',0) for x in (a,b)),
            'requests_without_usage':sum(x.get('requests_without_usage',0) for x in (a,b)),
            'complete':bool(a.get('complete') and b.get('complete'))}
    result['phases']={}  # stage timings remain separately auditable; do not combine nested phases
    result['note']='Cumulative prior + continuation; missing usage remains unknown; per-run phase timing retained separately.'
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('source','out','env-file','yosys'):p.add_argument('--'+name,type=Path,required=True)
    args=p.parse_args();source=args.source.resolve();out=fresh_directory(args.out.resolve())
    started=utc();began=time.monotonic();summary={'status':'failed'};old=None
    try:
        old,manifest,bundle,design,config,history,restored=restore(source,out)
        options=SimpleNamespace(model=manifest['model'],temperature=manifest['temperature'],timeout=manifest['timeout'],
            attempts=manifest['attempts'],jg_time_limit=manifest['jg_time_limit'],sampling_seed=manifest['seed'],
            sampling_time_limit='30s',encoded_witness_yosys=args.yosys,
            eda_shell=ROOT/'experiments/eda-shell',response_file=None)
        if options.model!='deepseek-v4-flash-vision-exp':raise ValueError('unexpected prior model')
        save(out/'manifest.json',dict(prior=str(source),prior_summary_sha256=digest(source/'summary.json'),
            method=manifest['method'],model=options.model,framework=framework_hashes(ROOT),
            continuation_policy='explicit-user-resume-first-request-402-v1',preserved_rounds=len(old['rounds'])))
        eda=json.loads((ROOT/'experiments/designs/haven_eda.json').read_text());eda['eda_env']={'shell':str(options.eda_shell)}
        simulate=IsolatedSimulation(HavenSimulation(bundle,design,eda,options.sampling_seed),out/'replay-cache')
        simulate.cache.mkdir()
        for cache in (source/'replay-cache').iterdir():
            if not (cache/'coverage.json').exists():continue
            result=json.loads((cache/'coverage.json').read_text())
            if any(digest(Path(p))!=sha for p,sha in result['artifact_sha256'].items()):raise ValueError('cached simulation changed')
            (simulate.cache/cache.name).symlink_to(cache,target_is_directory=True)
            simulate.completed[cache.name]=result
        load_env_file(args.env_file)
        backend=DirectedBackend(manifest['method'],bundle,design,config,simulate,options);backend.history=history
        # Ensure the first resumed request is byte-identical to the denied task.
        original_generate=backend.generate
        def generate(rd,feedback,existing,ordinal):
            if rd.name==f"round-{restored['failed_round']}":
                options.expected_first_payload=source/'experiment'/rd.name/'generation/attempt-1/payload-0.json'
            else:options.expected_first_payload=None
            return original_generate(rd,feedback,existing,ordinal)
        backend.generate=generate
        summary=coverage_loop(bundle,out/'experiment',simulate,backend,rounds=manifest['rounds'],restored=restored)
        verify_stage1(json.loads((source/'fixed-stage1.json').read_text()))
    except Exception as error:
        summary.update(status='failed',error=str(error))
    finally:
        finish(out,summary,started,began,continuation_source=str(source))
        if old:
            cumulative=deepcopy(summary)
            cumulative.update(incremental_costs=summary['costs'],prior_costs=old['costs'],
                costs=cumulative_costs(old['costs'],summary['costs']),
                stage2_seconds=(old.get('stage2_seconds') or 0)+(summary.get('stage2_seconds') or 0),
                elapsed_seconds=old['elapsed_seconds']+summary['elapsed_seconds'],prior_summary=str(source/'summary.json'),
                continuation_summary=str(out/'summary.json'),time_policy='sum active run wall times; excludes payment downtime')
            cumulative['tokens']=cumulative['costs']['usage_reported']['total_tokens']
            save(out/'cumulative-summary.json',cumulative)
    print(json.dumps({k:summary.get(k) for k in ('status','error','tokens')}),flush=True)
    return int(summary['status']!='completed')


if __name__=='__main__':raise SystemExit(main())
