"""One direct-baseline design with a frozen Stage 1 and the shared coverage loop policy."""
import backend_imports
import argparse
from copy import deepcopy
import json
from pathlib import Path
import time

from coverage_flow import prepare, load_bundle, HavenSimulation
from directed_baselines import DirectedBackend, EXTRA_METHODS, POLICY
from environment_preflight import write_replay_manifest
from frozen_stage1 import load_fixed_setup, verify_fixed_setup, admit_shared_environment
from haven_snapshot import snapshot
from haven_shared import compact_feedback, coverage_progress, stop_reason, check_sequence_set
from isolated_replay import IsolatedSimulation
from run_records import save, fresh_directory, finish, framework_hashes, fingerprint, utc
from sequence_experiment import load_env_file
from sequence_framework import ROOT


def coverage_loop(bundle, directory, simulate, backend, *, rounds=3, runtime_repairs=1, restored=None, fixed_rounds=False):
    """Same 3 rounds / 0.1 point stall / 100% target as the fixed two-arm runner."""
    current=None;previous=None;best=None
    sequences=list(bundle['sequences']);frames=[]
    result={'status':'running','rounds':[],'method':backend.method}
    start=time.monotonic()
    try:
        check_sequence_set(sequences)
        if restored is None:
            current=simulate(directory/'baseline',sequences,[])
            result['baseline']=deepcopy(current);best=deepcopy(current)
            first_round=1
        else:
            current,previous,best=(deepcopy(restored[k]) for k in ('current','previous','best'))
            sequences,frames=deepcopy(restored['sequences']),deepcopy(restored['frames'])
            result['baseline']=deepcopy(restored['baseline'])
            result['rounds']=deepcopy(restored['rounds'])
            first_round=len(result['rounds'])+1
            if first_round != restored['failed_round']:
                raise ValueError('continuation must start exactly after accepted rounds')
        result['baseline_seconds']=time.monotonic()-start
        stage2=time.monotonic()
        reason=None if fixed_rounds else stop_reason(None,current,0,rounds)
        if not reason:
            for number in range(first_round,rounds+1):
                result['active_round']=number
                save(directory/'progress.json',result)
                feedback=compact_feedback(current,previous)
                feedback['modinfo']=current.get('modinfo')
                for repair in range(runtime_repairs+1):
                    rd=directory/(f'round-{number}'+(f'-repair-{repair}' if repair else ''))
                    save(rd/'feedback.json',feedback)
                    candidate = None
                    try:
                        candidate=backend.generate(rd,feedback,sequences,len(frames))
                        save(rd/'candidate.json',candidate)
                        if candidate.get('stop') or not candidate.get('sequences'):
                            if repair:raise ValueError('runtime repair produced no replacement candidate')
                            break
                        proposed=sequences+candidate['sequences'];new_frames=frames+candidate.get('frames',[])
                        check_sequence_set(proposed)
                        measured=simulate(rd/'simulation',proposed,new_frames)
                    except ValueError as error:
                        detail=getattr(error,'diagnostics',{})
                        failure={'error':str(error),'diagnostics':detail,'round':number,'repair':repair,
                                 'phase':'candidate_generation' if candidate is None else 'simulation'}
                        result.setdefault('rejected_candidates',[]).append(failure)
                        save(rd/'runtime-failure.json',failure)
                        # Only explicit, classified candidate defects are repairable.
                        # Provider, provenance, adapter and unclassified failures stop.
                        if detail.get('model_repair_allowed') is not True or detail.get('kind') not in (
                                'candidate_environment_violation','model_candidate_error') or repair==runtime_repairs:raise
                        context=(candidate or {}).get('repair_context',getattr(error,'repair_context',{}))
                        if not context:raise
                        feedback={**feedback,'runtime_failure':failure,'rejected_model_candidate':context,
                            'repair_instruction':'Repair the same intent using the measured failure, without changing shared infrastructure or deleting checks.'}
                    else:break
                if fixed_rounds and (candidate.get('stop') or not candidate.get('sequences')):
                    result['rounds'].append(dict(round=number,status='no_validated_sequences',coverage=deepcopy(current),
                        added_sequences=0,metadata=candidate.get('metadata',{})))
                    reason='round_budget';continue
                if candidate.get('stop'):
                    reason='model_stop';break
                if not candidate.get('sequences'):
                    reason='no_generated_sequences'
                    result['rounds'].append(dict(round=number,status='no_validated_sequences',coverage=current,
                        added_sequences=0,metadata=candidate.get('metadata',{})))
                    break
                delta=coverage_progress(current,measured)
                result['rounds'].append(dict(round=number,**delta,coverage=measured,
                    added_sequences=len(candidate['sequences']),metadata=candidate.get('metadata',{})))
                previous,current,sequences,frames=current,measured,proposed,new_frames
                if current['score']>best['score']:best=deepcopy(current)
                # Keep only measured/accepted model outputs as subsequent history.
                accepted={fingerprint(s) for s in sequences}
                backend.history=[h for h in backend.history if h['sequence_hashes'] and set(h['sequence_hashes']) <= accepted]
                reason=('round_budget' if number>=rounds else None) if fixed_rounds else stop_reason(previous,current,number,rounds)
                save(directory/'progress.json',result)
                if reason:break
        result.update(status='completed',stop_reason=reason or 'round_budget',sequence_count=len(sequences))
    except Exception as error:
        result.update(status='failed',error=str(error),failed_round=result.get('active_round'),
                      diagnostics=getattr(error,'diagnostics',{}),failure_kind=getattr(error,'failure_kind',None))
    finally:
        result.update(final=current,best=best,elapsed_seconds=time.monotonic()-start,
                      stage2_seconds=time.monotonic()-stage2 if 'stage2' in locals() else None)
        result.pop('active_round',None)
        save(directory/'progress.json',result)
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('fixed-setup','haven-root','out','encoded-witness-yosys'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--env-file',type=Path)
    parser.add_argument('--method',choices=EXTRA_METHODS,required=True)
    parser.add_argument('--fixed-rounds',action='store_true')
    from rvprobe_model_options import token_limit
    parser.add_argument('--max-tokens',type=token_limit)
    parser.add_argument('--reasoning-effort',choices=('low','high','max'))
    parser.add_argument('--response-file',type=Path,help='offline diagnostic only, never a benchmark result')
    parser.add_argument('--eda-shell',type=Path,default=ROOT/'experiments/eda-shell')
    parser.add_argument('--model',default='deepseek-v4-flash-vision-exp')
    parser.add_argument('--temperature',type=float,default=0.3)
    parser.add_argument('--timeout',type=int,default=600)
    parser.add_argument('--attempts',type=int,default=3)
    parser.add_argument('--rounds',type=int,default=3)
    parser.add_argument('--jg-time-limit',default='120s')
    parser.add_argument('--sampling-seed',type=int,default=20260906)
    parser.add_argument('--sampling-time-limit',default='30s')
    args=parser.parse_args()
    if args.model not in ('deepseek-v4-flash-vision-exp','deepseek-v4-flash'):
        parser.error('unsupported controlled-cohort model')
    import re
    if not re.fullmatch(r'[1-9][0-9]*s',args.jg_time_limit):parser.error('invalid JG time limit')
    out=fresh_directory(args.out.resolve());started=utc();began=time.monotonic()
    manifest=dict(policy=POLICY,method=args.method,model=args.model,diagnostic_only=bool(args.response_file),
        stage1_model_calls=0,framework=framework_hashes(ROOT),fixed_setup=str(args.fixed_setup),
        rounds=args.rounds,attempts=args.attempts,runtime_repairs=1,sequence_cap_per_intent=4,
        max_intents_per_round=4,timeout=args.timeout,jg_time_limit=args.jg_time_limit,
        boundary='independent-dut-v1',seed=args.sampling_seed,temperature=args.temperature,
        direct_stimulus_intent_satisfaction='not checked; observed coverage only',
        solver_backend='JG for SVA; VCS randomize for SV constraint; none for concrete stimulus',
        max_tokens=args.max_tokens,reasoning_effort=args.reasoning_effort,fixed_rounds=args.fixed_rounds)
    save(out/'manifest.json',manifest);summary={'status':'running','method':args.method}
    identity=None
    try:
        setup,identity=load_fixed_setup(args.fixed_setup);verify_fixed_setup(identity)
        stage=Path(setup['stage1']);ir=stage/'ir'
        task=json.loads((ir/'phase0_config.json').read_text());bp=json.loads((ir/'phase2b_blueprint.json').read_text())
        implementation=snapshot(Path(setup['haven_snapshot']),out/'implementation/haven')
        replay_path=write_replay_manifest(task,bp,out/'manifest-io',
            (Path(task['root'])/task['spec']).read_text(),boundary='independent-dut-v1')
        prepare(stage,implementation,replay_path,out/'shared')
        bundle,design,replay=load_bundle(out/'shared/bundle.json',implementation)
        save(out/'fixed-stage1.json',identity)
        save(out/'shared-admission.json',admit_shared_environment(bundle,identity))
        eda=json.loads((ROOT/'experiments/designs/haven_eda.json').read_text())
        eda['eda_env']={'shell':str(args.eda_shell.resolve())}
        simulate=IsolatedSimulation(HavenSimulation(bundle,design,eda,args.sampling_seed),out/'replay-cache')
        if args.env_file and not args.response_file:load_env_file(args.env_file)
        backend=DirectedBackend(args.method,bundle,design,replay,simulate,args)
        summary=coverage_loop(bundle,out/'experiment',simulate,backend,rounds=args.rounds,fixed_rounds=args.fixed_rounds)
    except Exception as error:
        summary.update(status='failed',error=str(error),failure_kind=getattr(error,'failure_kind',None))
    finally:
        if identity is not None:
            try:verify_fixed_setup(identity)
            except Exception as error:summary.update(status='failed',fixed_stage1_error=str(error))
        finish(out,summary,started,began,**manifest)
    print(json.dumps({k:summary.get(k) for k in ('status','error','tokens','stage2_seconds')}),flush=True)
    return int(summary['status']!='completed')


if __name__=='__main__':raise SystemExit(main())
