#!/usr/bin/env python3
"""Continue a coverage-floor RVProbe run from its verified accepted checkpoint.

The source run remains immutable.  Accepted simulations are hash-checked and
reused through the isolated replay cache; prior rounds and provider usage are
reported cumulatively but are never requested or charged again.
"""
import backend_imports
import argparse
from copy import deepcopy
import json
import os
from pathlib import Path
import re
import shutil
from types import SimpleNamespace
import time

import sequence_experiment as generation
import rvprobe_model_options
from continue_direct_402 import cumulative_costs
from coverage_flow import Backends, CONTRACT, HavenSimulation, load_haven, paired_loop
from cycle_replay import digest, load_config
from frozen_stage1 import verify_stage1
from isolated_replay import IsolatedSimulation, independent_batches
from run_records import Records, begin, finish, fingerprint, framework_hashes, save, totals
from experiment_storage import archive_tree
from sequence_framework import ROOT


ALLOWED_TRANSITION_PATHS = {
    'experiments/continue_rvprobe_quality.py',
    'experiments/coverage_flow.py',
    'experiments/sequence_experiment.py',
    'rvprobe-skill.md',
}


def verify_artifacts(mapping):
    if not isinstance(mapping,dict) or not mapping:
        raise ValueError('accepted measurement has no artifact hashes')
    for raw, expected in mapping.items():
        path=Path(raw)
        if not path.is_file() or digest(path)!=expected:
            raise ValueError(f'accepted artifact changed or disappeared: {path}')


def load_archived_bundle(flow, haven_root):
    """Validate the original fingerprint, then relocate only its replay path."""
    path=flow/'shared/bundle.json'
    bundle=json.loads(path.read_text())
    recorded=bundle.pop('fingerprint')
    if fingerprint(bundle)!=recorded or bundle.get('contract')!=CONTRACT:
        raise ValueError('source shared bundle changed')
    bundle['fingerprint']=recorded
    replay_path=flow/'manifest/replay.json'
    design,replay=load_config(replay_path)
    if (design.record()!=bundle['design'] or replay!=bundle['replay'] or
            digest(replay_path)!=bundle['replay_sha256']):
        raise ValueError('archived DUT or replay contract changed')
    load_haven(haven_root,bundle['haven_sha256'])
    original=bundle['replay_config']
    bundle['replay_config']=str(replay_path.resolve())
    return bundle,design,replay,dict(original=original,archived=bundle['replay_config'],
                                     sha256=bundle['replay_sha256'])


def source_ltl(round_directory, candidate):
    text=(candidate.get('repair_context') or {}).get('ltl')
    if not isinstance(text,str) or not text.strip():
        raise ValueError(f'{round_directory.name} has no accepted LTL source')
    paths=[p for p in (round_directory/'generation').glob('attempt-*/sources/model.ltl')
           if p.read_text()==text]
    if not paths:
        raise ValueError(f'{round_directory.name} accepted LTL does not match its model artifact')
    def attempt(path):
        match=re.fullmatch(r'attempt-(\d+)',path.parents[1].name)
        return int(match.group(1)) if match else -1
    path=max(paths,key=attempt)
    return {'round':round_directory.name,'source':text,'sha256':digest(path),
            'artifact':str(path)}


def restore_checkpoint(source, bundle, design):
    outer=json.loads((source/'summary.json').read_text())
    manifest=json.loads((source/'manifest.json').read_text())
    if (manifest.get('arms')!=['rvprobe'] or outer.get('arms',{}).keys()!={'rvprobe'} or
            outer.get('status')!='failed'):
        raise ValueError('source must be one completed RVProbe-only paired run')
    arm=outer['arms']['rvprobe']
    if (arm.get('status')!='failed' or arm.get('failure_kind')!='coverage_floor_unmet' or
            arm.get('coverage_floor_met') is not False):
        raise ValueError('source did not stop solely because its coverage floor was unmet')
    if outer.get('baseline')!=arm.get('baseline'):
        raise ValueError('source arm baseline differs from the shared baseline')
    if manifest.get('bundle')!=bundle['fingerprint']:
        raise ValueError('source manifest bundle fingerprint changed')
    verify_artifacts(outer['baseline']['artifact_sha256'])
    sequences=list(bundle['sequences'])
    frames=[]
    candidates=[]
    links=[]
    rounds=arm.get('rounds')
    if (not isinstance(rounds,list) or
            [row.get('round') for row in rounds]!=list(range(1,len(rounds)+1))):
        raise ValueError('source accepted rounds are not contiguous')
    for number,row in enumerate(rounds,1):
        rd=source/'rvprobe'/f'round-{number}'
        candidate=json.loads((rd/'candidate.json').read_text())
        if len(candidate.get('sequences',[]))!=row.get('added_sequences'):
            raise ValueError(f'round {number} accepted sequence count changed')
        if row.get('status')=='no_validated_sequences':
            raise ValueError('continuation currently requires measured accepted rounds')
        verify_artifacts(row['coverage']['artifact_sha256'])
        batches=json.loads((rd/'simulation/batches.json').read_text())
        for measured in batches.get('runs',[]):
            verify_artifacts(measured['artifact_sha256'])
        sequences.extend(candidate['sequences'])
        frames.extend(candidate.get('frames',[]))
        candidates.append({'sequences':list(candidate['sequences']),
                           'metadata':deepcopy(candidate.get('metadata',{})),
                           'ltl':source_ltl(rd,candidate)})
        links.append({'round':number,'directory':str(rd),
                      'candidate_sha256':digest(rd/'candidate.json'),
                      'coverage_sha256':digest(rd/'simulation/coverage.json')})
    batches=independent_batches(bundle,design,sequences,frames)
    if len(batches)!=len(sequences):
        raise ValueError('restored isolated sequence partition changed')
    if arm.get('sequence_count')!=len(sequences) or arm.get('witness_frames')!=len(frames):
        raise ValueError('source checkpoint sequence/frame totals changed')
    current=arm['final']
    if current!=(rounds[-1]['coverage'] if rounds else outer['baseline']):
        raise ValueError('source final coverage is not its last accepted measurement')
    previous=(rounds[-2]['coverage'] if len(rounds)>1 else outer['baseline'] if rounds else None)
    restored=dict(baseline=deepcopy(outer['baseline']),current=deepcopy(current),
        previous=deepcopy(previous),best=deepcopy(arm['best']),rounds=deepcopy(rounds),
        sequences=sequences,frames=frames)
    for key in ('intent_outcomes','all_intents_satisfied','rejected_candidates'):
        if key in arm:restored[key]=deepcopy(arm[key])
    return outer,manifest,arm,candidates,restored,batches,links


def options_from_manifest(manifest, env_file, eda_shell):
    rv=manifest['rvprobe_generation_options']
    sampling=manifest['sampling']
    values=dict(model=manifest['model'],temperature=manifest['temperature'],
        timeout=manifest['timeout'],attempts=manifest['attempts'],
        request_retries=manifest['request_retries'],jg_time_limit=manifest['jg_time_limit'],
        runtime_repairs=manifest['runtime_repairs'],seed=manifest['seed'],
        sampling_seed=sampling['seed'],sampling_time_limit=sampling['time_limit'],
        sequences_per_intent=sampling['sequences_per_intent'],eda_shell=eda_shell,
        env_file=env_file,encoded_witness_yosys=None,
        encoded_witness_time_limit=(manifest.get('native_witness_selection') or {}).get(
            'auxiliary_time_limit','120s'),continued_generation=None,resume=False,
        rvprobe_feedback_mode=rv['feedback_mode'],rvprobe_fixed_rounds=rv['fixed_rounds'],
        rvprobe_dialogue_policy=rv['dialogue_policy'],
        rvprobe_reasoning_effort=rv['reasoning_effort'],
        rvprobe_max_tokens=rv['max_tokens'],
        rvprobe_request_timeout=rv['request_timeout_seconds'],
        rvprobe_evidence_steps=rv['evidence_steps'],rvprobe_evidence_tools=rv['evidence_tools'],
        rvprobe_retrieval_max_tokens=rv['retrieval_max_tokens'],
        rvprobe_retrieval_reasoning_effort=rv['retrieval_reasoning_effort'],
        rvprobe_intent_batch_limit=rv['intent_batch_limit'],
        rvprobe_adaptive_quality_floor=rv['adaptive_quality_floor'])
    options=SimpleNamespace(**values)
    if rvprobe_model_options.record(options)!=rv:
        raise ValueError('could not reconstruct the recorded RVProbe generation options')
    return options


def restore_cache(simulate, source_cache, batches):
    simulate.cache.mkdir(parents=True,exist_ok=False)
    restored=[]
    simulator=simulate.simulator
    for sources,frames in batches:
        key=fingerprint(dict(bundle=simulator.bundle['fingerprint'],seed=simulator.seed,
                             config=simulator.config,sources=sources,frames=frames))
        old=source_cache/key
        coverage=old/'coverage.json'
        if not coverage.is_file():
            raise ValueError(f'verified replay cache entry is missing: {key}')
        result=json.loads(coverage.read_text())
        verify_artifacts(result['artifact_sha256'])
        target=simulate.cache/key
        if target.exists() or target.is_symlink():
            raise ValueError(f'duplicate restored replay cache key: {key}')
        target.symlink_to(old,target_is_directory=True)
        simulate.completed[key]=result
        restored.append({'key':key,'source':str(old),'coverage_sha256':digest(coverage)})
    return restored


def combine_summary(prior,incremental,source,source_arm,token_cap,manifest,
                    extra_prior=None):
    extra_prior = extra_prior or []
    prior_costs = deepcopy(source_arm['costs'])
    prior_elapsed = source_arm.get('elapsed_seconds') or 0
    for row in extra_prior:
        prior_costs = cumulative_costs(prior_costs,row['costs'])
        prior_elapsed += row.get('elapsed_seconds') or 0
    result=deepcopy(incremental)
    new_arm=result['arms']['rvprobe']
    incremental_arm=deepcopy(new_arm['costs'])
    combined_arm=cumulative_costs(prior_costs,incremental_arm)
    new_arm.update(prior_costs=prior_costs,incremental_costs=incremental_arm,
                   costs=combined_arm,
                   prior_elapsed_seconds=prior_elapsed,
                   incremental_elapsed_seconds=new_arm.get('elapsed_seconds'),
                   elapsed_seconds=prior_elapsed+(new_arm.get('elapsed_seconds') or 0))
    combined=prior_costs
    combined=cumulative_costs(combined,incremental['costs'])
    result.update(costs=combined,tokens=combined['usage_reported']['total_tokens'],
        prior_costs=prior_costs,incremental_costs=incremental['costs'],
        prior_elapsed_seconds=prior_elapsed,
        incremental_elapsed_seconds=incremental.get('elapsed_seconds'),
        elapsed_seconds=prior_elapsed+(incremental.get('elapsed_seconds') or 0),
        continuation_source=str(source/'summary.json'),
        continuation_source_sha256=digest(source/'summary.json'),
        continuation_manifest=str(source/'manifest.json'),
        continuation_manifest_sha256=digest(source/'manifest.json'),
        framework_transition=manifest['framework_transition'],
        cumulative_token_cap=token_cap,
        cumulative_token_cap_met=(result_token:=combined['usage_reported']['total_tokens']) is not None
                                 and result_token<=token_cap,
        time_policy='sum active source and continuation wall times; no downtime or duplicate replay')
    result['prior_runs']=[{'summary':str(source/'summary.json'),'role':'accepted-source',
                           'costs':source_arm['costs']}]+[
        {'summary':row['summary'],'role':row.get('role','charged-intermediate'),
         'costs':row['costs']} for row in extra_prior]
    return result


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,required=True,
                        help='source paired directory containing summary.json')
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--env-file',type=Path,required=True)
    parser.add_argument('--eda-config',type=Path,default=ROOT/'experiments/designs/haven_eda.json')
    parser.add_argument('--eda-shell',type=Path,default=ROOT/'experiments/eda-shell')
    parser.add_argument('--additional-rounds',type=int,default=2)
    parser.add_argument('--cumulative-token-cap',type=int,required=True)
    parser.add_argument('--reuse-generation',type=Path,
                        help='complete generation directory from an infrastructure-only failed retry; no model call')
    parser.add_argument('--charged-source',type=Path,
                        help='prior continuation summary whose provider cost must be included')
    parser.add_argument('--work-dir',type=Path,
                        help='scratch directory; defaults to an explicit fresh /dev/shm directory')
    args=parser.parse_args(argv)
    if args.additional_rounds<1 or args.cumulative_token_cap<1:
        parser.error('additional rounds and cumulative token cap must be positive')
    if not shutil.which('bwrap'):
        parser.error('bubblewrap is required; run inside nix develop')
    source=args.source.resolve();flow=source.parent
    archive=args.out.resolve()
    if archive.exists() or archive.is_symlink():
        parser.error(f'output archive already exists: {archive}')
    work=(args.work_dir.resolve() if args.work_dir else
          Path('/dev/shm')/(archive.name+'-work-'+str(os.getpid())))
    if work.exists() or work.is_symlink():
        parser.error(f'scratch directory already exists: {work}')
    if not (source/'summary.json').is_file() or not (flow/'fixed-stage1.json').is_file():
        parser.error('source must be an archived paired run directory')
    generation.require_provider_credentials(args.env_file)
    fixed=json.loads((flow/'fixed-stage1.json').read_text());verify_stage1(fixed)
    haven_root=flow/'implementation/haven'
    bundle,design,replay,relocation=load_archived_bundle(flow,haven_root)
    old,old_manifest,old_arm,candidates,restored,batches,links=restore_checkpoint(
        source,bundle,design)
    if old_manifest['model']!='deepseek-v4-flash-vision-exp':
        raise ValueError('source model is not the fixed DeepSeek model')
    if old_arm['costs']['token_accounting_complete'] is not True:
        raise ValueError('source token accounting is incomplete')
    extra_prior=[]
    if args.charged_source:
        charged=Path(args.charged_source).resolve()
        charged_summary=json.loads((charged/'summary.json').read_text())
        charged_manifest=json.loads((charged/'manifest.json').read_text())
        charged_arm=charged_summary.get('arms',{}).get('rvprobe',{})
        if (charged_manifest.get('bundle')!=bundle['fingerprint'] or
                charged_manifest.get('model')!='deepseek-v4-flash-vision-exp' or
                charged_arm.get('costs',{}).get('token_accounting_complete') is not True):
            raise ValueError('charged intermediate run does not match the source contract')
        if args.reuse_generation:
            expected=(charged/'rvprobe'/'round-5'/'generation').resolve()
            if args.reuse_generation.resolve()!=expected:
                raise ValueError('reused generation must come from the charged round-5 directory')
        extra_prior.append({'summary':str(charged/'summary.json'),'role':'charged-intermediate',
                            'costs':charged_arm['costs'],'elapsed_seconds':charged_summary.get('elapsed_seconds')})
    prior_costs=deepcopy(old_arm['costs'])
    for row in extra_prior:
        prior_costs=cumulative_costs(prior_costs,row['costs'])
    prior_tokens=prior_costs['usage_reported']['total_tokens']
    if prior_tokens is None or prior_tokens>=args.cumulative_token_cap:
        raise ValueError('prior runs already exhaust the cumulative token budget')
    if digest(args.eda_config.resolve())!=old_manifest['eda_sha256']:
        raise ValueError('EDA configuration changed since the source run')
    if digest(args.eda_shell.resolve())!=old_manifest['eda_shell_sha256']:
        raise ValueError('EDA shell changed since the source run')
    current_framework=framework_hashes(ROOT)
    changed={key for key in set(old_manifest['source_sha256'])|set(current_framework)
             if old_manifest['source_sha256'].get(key)!=current_framework.get(key)}
    if changed-ALLOWED_TRANSITION_PATHS:
        raise ValueError('unreviewed framework changes prevent cache continuation: '+
                         ', '.join(sorted(changed-ALLOWED_TRANSITION_PATHS)))
    total_rounds=len(restored['rounds'])+args.additional_rounds
    quality_floor=old_arm['coverage_floor']
    options=options_from_manifest(old_manifest,args.env_file.resolve(),args.eda_shell.resolve())
    options.reuse_generations={5:args.reuse_generation.resolve()} if args.reuse_generation else {}
    config=json.loads(args.eda_config.read_text())
    if set(config)-{'eda_tools','eda_env','simulation'}:
        raise ValueError('EDA config contains non-EDA keys')
    config.setdefault('eda_env',{})['shell']=str(args.eda_shell.resolve())
    transition={'from':fingerprint(old_manifest['source_sha256']),
                'to':fingerprint(current_framework),'changed_paths':sorted(changed),
                'allowed_paths':sorted(ALLOWED_TRANSITION_PATHS)}
    run_manifest=dict(contract=CONTRACT,kind='rvprobe-quality-continuation-v1',
        bundle=bundle['fingerprint'],model=options.model,arms=['rvprobe'],
        source=str(source),source_summary_sha256=digest(source/'summary.json'),
        source_manifest_sha256=digest(source/'manifest.json'),source_rounds=len(restored['rounds']),
        total_rounds=total_rounds,additional_rounds=args.additional_rounds,
        coverage_floor=quality_floor,prior_tokens=prior_tokens,
        cumulative_token_cap=args.cumulative_token_cap,
        replay_config_relocation=relocation,framework_transition=transition,
        source_sha256=current_framework,rvprobe_generation_options=rvprobe_model_options.record(options),
        sampling=old_manifest['sampling'],seed=options.seed,
        runtime_repairs=options.runtime_repairs,temperature=options.temperature,
        attempts=options.attempts,request_retries=options.request_retries,
        timeout=options.timeout,jg_time_limit=options.jg_time_limit,
        checkpoint_policy='verified-accepted-rounds-and-isolated-cache-v1')
    if args.reuse_generation:
        run_manifest['reuse_generation']=str(args.reuse_generation.resolve())
        run_manifest['reuse_generation_policy']='complete-paid-model-response; native replay only'
    if extra_prior:
        run_manifest['charged_intermediate']=extra_prior
    root=work
    root.parent.mkdir(parents=True,exist_ok=True)
    started=begin(root,run_manifest);began=time.monotonic()
    summary={'status':'failed'}
    try:
        os.environ['RVPROBE_STORAGE_WORK_ROOT']=str(work)
        os.environ['RVPROBE_STORAGE_ARCHIVE_ROOT']=str(archive)
        save(root/'fixed-stage1.json',fixed)
        (root/'baseline').symlink_to(source/'baseline',target_is_directory=True)
        (root/'rvprobe').mkdir()
        for row in links:
            (root/'rvprobe'/f"round-{row['round']}").symlink_to(
                Path(row['directory']),target_is_directory=True)
        save(root/'checkpoint.json',dict(source=str(source),rounds=links,
             source_summary_sha256=digest(source/'summary.json'),
             fixed_stage1_sha256=digest(flow/'fixed-stage1.json'),
             sequences=len(restored['sequences']),frames=len(restored['frames'])))
        base_simulator=HavenSimulation(bundle,design,config,options.seed)
        simulate=IsolatedSimulation(base_simulator,root/'replay-cache')
        cache=restore_cache(simulate,source/'replay-cache',batches)
        save(root/'restored-cache.json',dict(entries=cache,count=len(cache),
             source=str(source/'replay-cache'),policy='symlink-verified-immutable-cache-v1'))
        backends=Backends(bundle,design,replay,options)
        backends.rvprobe_candidates=candidates
        backends.native_witness_simulator=simulate
        def generate(arm,rd,feedback,existing,ordinal):
            return backends(arm,rd,feedback,existing,ordinal)
        with Records(root).phase('continuation-session'):
            summary=paired_loop(bundle,root,simulate,generate,rounds=total_rounds,
                min_gain=old_manifest['min_gain'],target=old_manifest['target'],arms=('rvprobe',),
                runtime_repairs=options.runtime_repairs,quality_floor=quality_floor,restored=restored)
        new_costs=summary['arms']['rvprobe']['costs']
        new_tokens=new_costs['usage_reported']['total_tokens']
        if new_costs['token_accounting_complete'] is not True or new_tokens is None:
            summary['status']='failed';summary['arms']['rvprobe'].update(
                status='failed',failure_kind='token_accounting_incomplete',
                error='continuation provider usage is incomplete; token target cannot be claimed')
        elif prior_tokens+new_tokens>args.cumulative_token_cap:
            summary['status']='failed';summary['arms']['rvprobe'].update(
                status='failed',failure_kind='token_budget_exceeded',
                error=f'cumulative tokens {prior_tokens+new_tokens} exceed cap {args.cumulative_token_cap}')
        verify_stage1(fixed)
    except Exception as error:
        summary.update(status='failed',error=str(error))
    finally:
        try:
            verify_stage1(fixed)
        except Exception as error:
            summary.update(status='failed',fixed_stage1_error=str(error))
        try:
            finish(root,summary,started,began,session_phase='continuation-session',**run_manifest)
            completed=json.loads((root/'summary.json').read_text())
            if 'arms' in completed and 'rvprobe' in completed['arms']:
                cumulative=combine_summary(old,completed,source,old_arm,args.cumulative_token_cap,
                                           json.loads((root/'manifest.json').read_text()),extra_prior)
                save(root/'cumulative-summary.json',cumulative)
        except Exception as error:
            summary.update(status='failed',accounting_error=str(error))
            # Keep an auditable fallback even if final accounting itself fails.
            try: save(root/'continuation-error.json',summary)
            except Exception: pass
        # Archive the complete run only after all accounting files are closed.
        try:
            archive.parent.mkdir(parents=True,exist_ok=True)
            archive_tree(root,archive)
            # Rewrite the two top-level absolute scratch paths for the archived handoff.
            for name in ('summary.json','cumulative-summary.json','comparison.json','progress.json'):
                path=archive/name
                if path.is_file():
                    text=path.read_text().replace(str(work),str(archive))
                    path.write_text(text)
        except Exception as error:
            summary.update(status='failed',archive_error=str(error))
            try: save(archive/'continuation-error.json',summary)
            except Exception: pass
        os.environ.pop('RVPROBE_STORAGE_WORK_ROOT',None)
        os.environ.pop('RVPROBE_STORAGE_ARCHIVE_ROOT',None)
    output_path=archive/'cumulative-summary.json'
    if not output_path.is_file():
        output_path=archive/'summary.json'
    output=json.loads(output_path.read_text()) if output_path.is_file() else summary
    arm=output.get('arms',{}).get('rvprobe',{})
    print(json.dumps({'status':output.get('status'),'failure_kind':arm.get('failure_kind'),
        'coverage':(arm.get('best') or {}).get('score'),'tokens':output.get('tokens'),
        'rounds':len(arm.get('rounds',[])),'summary':str(archive/'cumulative-summary.json')},
        ensure_ascii=False),flush=True)
    return int(output.get('status')!='completed')


if __name__=='__main__':
    raise SystemExit(main())
