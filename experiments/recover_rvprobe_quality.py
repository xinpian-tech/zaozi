"""Finish an interrupted quality continuation using already-paid LTL only.

The interrupted run is immutable. Accepted simulations are verified and reused;
missing model results, automatic model repairs and new model calls fail closed.
Both original and interrupted provider usage remain in the cumulative report.
"""
import backend_imports
import argparse
from copy import deepcopy
import json
import os
from pathlib import Path
import tempfile
import time

from continue_rvprobe_quality import (
    combine_summary, load_archived_bundle, options_from_manifest,
    restore_cache, restore_checkpoint, verify_artifacts,
)
from coverage_flow import HavenSimulation, paired_loop
from cycle_replay import digest
from experiment_storage import archive_tree
from frozen_stage1 import verify_stage1
from isolated_replay import IsolatedSimulation, independent_batches
from offline_validation import no_model_calls
from replay_saved_candidate import load_saved_simulation
from run_records import Records, begin, finish, fingerprint, framework_hashes, save, totals
from sequence_framework import ROOT


def read(path):
    return json.loads(Path(path).read_text())


def checked_manifest(path):
    record = read(path)
    identity = {k: v for k, v in record.items() if k not in ('fingerprint', 'started_utc')}
    if fingerprint(identity) != record.get('fingerprint'):
        raise ValueError('interrupted manifest fingerprint changed')
    if record.get('kind') != 'rvprobe-quality-continuation-v1':
        raise ValueError('expected an interrupted quality continuation')
    return record


def paid_generation(directory, model, design):
    result = read(directory/'summary.json')
    if (result.get('status') not in ('generated', 'completed') or result.get('model') != model
            or result.get('result', {}).get('status') not in ('generated', 'partial')
            or result.get('design') != design.record()):
        raise ValueError('saved generation is incomplete or targets another DUT/model')
    costs = totals(directory)
    if (not costs['token_accounting_complete'] or costs['requests'] < 1
            or costs['usage_reported'] != result.get('costs', {}).get('usage_reported')):
        raise ValueError('saved generation has inconsistent provider accounting')
    source = Path(result['sources'])/'model.ltl'
    if not source.is_file() or not source.read_text().strip():
        raise ValueError('saved generation has no LTL source')
    return result, dict(source=str(directory), summary_sha256=digest(directory/'summary.json'),
                       ltl_sha256=digest(source), remote_llm_requests=0)


def restore_interrupted(work, manifest, bundle, design, restored):
    progress = read(work/'progress.json')
    arm = progress.get('arms', {}).get('rvprobe', {})
    rows = arm.get('rounds', [])
    prefix = len(restored['rounds'])
    if (progress.get('status') != 'running' or progress.get('bundle') != bundle['fingerprint']
            or rows[:prefix] != restored['rounds']
            or [row.get('round') for row in rows] != list(range(1, len(rows)+1))
            or arm.get('active_round') != len(rows)+1
            or len(rows) >= manifest['total_rounds']):
        raise ValueError('interrupted accepted checkpoint is inconsistent')
    for row in rows[prefix:]:
        rd = work/'rvprobe'/f"round-{row['round']}"
        candidate = read(rd/'candidate.json')
        if (len(candidate.get('sequences', [])) != row.get('added_sequences')
                or candidate.get('metadata') != row.get('metadata')
                or row['coverage'].get('replay', {}).get('passed') is not True):
            raise ValueError('accepted candidate differs from measured checkpoint')
        verify_artifacts(row['coverage']['artifact_sha256'])
        restored['sequences'].extend(candidate['sequences'])
        restored['frames'].extend(candidate.get('frames', []))
        batches = independent_batches(bundle, design, restored['sequences'], restored['frames'])
        measured = read(rd/'simulation/batches.json')
        if len(batches) != len(measured['runs']) or len(batches) != len(measured['databases']):
            raise ValueError('accepted replay partition changed')
        for batch, database, result in zip(batches, measured['databases'], measured['runs']):
            verify_artifacts(result['artifact_sha256'])
            saved = load_saved_simulation(Path(database).parent)
            if batch != (saved['sequences'], saved['frames']):
                raise ValueError('candidate differs from the stimuli actually simulated')
    restored.update(rounds=deepcopy(rows), current=deepcopy(rows[-1]['coverage']),
                    previous=deepcopy(rows[-2]['coverage'] if len(rows)>1 else restored['baseline']),
                    best=deepcopy(max([restored['baseline']]+[r['coverage'] for r in rows],
                                      key=lambda coverage: coverage['score'])))
    for key in ('intent_outcomes', 'all_intents_satisfied', 'rejected_candidates'):
        if key in arm:
            restored[key] = deepcopy(arm[key])
    return independent_batches(bundle, design, restored['sequences'], restored['frames'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--interrupted', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    interrupted, archive = args.interrupted.resolve(), args.out.resolve()
    if archive.exists():
        parser.error('output must be a new archive directory')
    manifest = checked_manifest(interrupted/'manifest.json')
    source = Path(manifest['source'])
    for name in ('summary', 'manifest'):
        if digest(source/f'{name}.json') != manifest[f'source_{name}_sha256']:
            raise ValueError('original source run changed')
    current_framework = framework_hashes(ROOT)
    changed = {p for p in set(current_framework)|set(manifest['source_sha256'])
               if current_framework.get(p) != manifest['source_sha256'].get(p)}
    if changed - {'experiments/recover_rvprobe_quality.py'}:
        raise ValueError('unreviewed framework changes: '+', '.join(sorted(changed)))
    fixed = read(interrupted/'fixed-stage1.json')
    verify_stage1(fixed)
    bundle, design, replay, _ = load_archived_bundle(source.parent, source.parent/'implementation/haven')
    if bundle['fingerprint'] != manifest['bundle']:
        raise ValueError('interrupted bundle changed')
    old, old_manifest, old_arm, _, restored, _, _ = restore_checkpoint(source, bundle, design)
    batches = restore_interrupted(interrupted, manifest, bundle, design, restored)
    eda_config, eda_shell = ROOT/'experiments/designs/haven_eda.json', ROOT/'experiments/eda-shell'
    if (digest(eda_config) != old_manifest['eda_sha256']
            or digest(eda_shell) != old_manifest['eda_shell_sha256']):
        raise ValueError('EDA configuration changed')
    options = options_from_manifest(old_manifest, None, eda_shell)
    saved_generations = {}
    for number in range(len(restored['rounds'])+1, manifest['total_rounds']+1):
        saved_generations[number] = paid_generation(
            interrupted/'rvprobe'/f'round-{number}'/'generation', manifest['model'], design)
    extra_prior = deepcopy(manifest.get('charged_intermediate', []))
    for row in extra_prior:
        if read(row['summary'])['arms']['rvprobe']['costs'] != row['costs']:
            raise ValueError('previous charged run accounting changed')
    interrupted_costs = totals(interrupted/'rvprobe')
    if not interrupted_costs['token_accounting_complete']:
        raise ValueError('interrupted provider accounting is incomplete')
    extra_prior.append(dict(summary=str(interrupted/'progress.json'), role='interrupted-continuation',
                            costs=interrupted_costs, elapsed_seconds=None))
    config = read(eda_config)
    config.setdefault('eda_env', {})['shell'] = str(eda_shell)
    work = Path(tempfile.mkdtemp(prefix=archive.name+'-work-', dir='/dev/shm'))
    run_manifest = dict(kind='offline-quality-continuation-recovery-v1', model_requests_allowed=False,
        interrupted=str(interrupted), interrupted_manifest_sha256=digest(interrupted/'manifest.json'),
        source=str(source), source_sha256=current_framework, bundle=bundle['fingerprint'],
        framework_transition=dict(changed_paths=sorted(changed)),
        restored_rounds=len(restored['rounds']), total_rounds=manifest['total_rounds'],
        cumulative_token_cap=manifest['cumulative_token_cap'], coverage_floor=manifest['coverage_floor'])
    started = begin(work, run_manifest, resume=True)
    began = time.monotonic()
    summary = dict(status='failed')
    try:
        os.environ['RVPROBE_STORAGE_WORK_ROOT'] = str(work)
        os.environ['RVPROBE_STORAGE_ARCHIVE_ROOT'] = str(archive)
        save(work/'fixed-stage1.json', fixed)
        (work/'baseline').symlink_to(source/'baseline', target_is_directory=True)
        (work/'rvprobe').mkdir()
        for row in restored['rounds']:
            (work/'rvprobe'/f"round-{row['round']}").symlink_to(
                interrupted/'rvprobe'/f"round-{row['round']}", target_is_directory=True)
        simulate = IsolatedSimulation(HavenSimulation(bundle, design, config, options.seed), work/'replay-cache')
        cache = restore_cache(simulate, interrupted/'replay-cache', batches)
        save(work/'restored-cache.json', dict(count=len(cache), entries=cache))

        def generate(arm, directory, feedback, existing, ordinal):
            if arm != 'rvprobe' or directory.name != f"round-{len(restored['rounds'])+1}":
                raise ValueError('offline recovery forbids new rounds and automatic model repairs')
            number = int(directory.name.split('-')[1])
            result, provenance = saved_generations.pop(number)
            save(directory/'reused-generation.json', provenance)
            from witness_backend_adapter import generate as generate_sequences
            produced = generate_sequences(result, Path(bundle['replay_config']), design,
                replay, simulate, directory, options, ordinal)
            return dict(sequences=produced['sequences'], frames=produced['frames'],
                        metadata=produced['metadata'], repair_context=dict(
                            ltl=(Path(result['sources'])/'model.ltl').read_text()))

        with no_model_calls(), Records(work).phase('continuation-session'):
            summary = paired_loop(bundle, work, simulate, generate, rounds=manifest['total_rounds'],
                min_gain=old_manifest['min_gain'], target=old_manifest['target'], arms=('rvprobe',),
                runtime_repairs=0, quality_floor=manifest['coverage_floor'], restored=restored)
        verify_stage1(fixed)
    except Exception as error:
        summary.update(status='failed', error=str(error))
    finally:
        finish(work, summary, started, began, session_phase='continuation-session', **run_manifest)
        completed = read(work/'summary.json')
        if 'rvprobe' in completed.get('arms', {}):
            cumulative = combine_summary(old, completed, source, old_arm,
                manifest['cumulative_token_cap'], run_manifest, extra_prior)
            # The killed process has no reliable end time. Never present the
            # known subtotal as a complete wall-clock measurement.
            cumulative.update(elapsed_seconds_complete=False,
                time_policy='known active-session subtotal; interrupted session duration unknown',
                recovery_model_requests=completed['costs']['requests'])
            arm = cumulative['arms']['rvprobe']
            failures = []
            if not arm.get('coverage_floor_met', False):
                failures.append('coverage_floor_unmet')
            if not cumulative['cumulative_token_cap_met']:
                failures.append('token_budget_exceeded')
            cumulative['unmet_targets'] = failures
            if failures:
                cumulative['status'] = arm['status'] = 'failed'
            save(work/'cumulative-summary.json', cumulative)
        archive_tree(work, archive)
        os.environ.pop('RVPROBE_STORAGE_WORK_ROOT', None)
        os.environ.pop('RVPROBE_STORAGE_ARCHIVE_ROOT', None)
    final = read(archive/'cumulative-summary.json') if (archive/'cumulative-summary.json').is_file() else completed
    arm = final.get('arms', {}).get('rvprobe', {})
    print(json.dumps(dict(status=final['status'], coverage=arm.get('best', {}).get('score'),
        tokens=final.get('tokens'), unmet_targets=final.get('unmet_targets'),
        recovery_model_requests=final.get('recovery_model_requests'), archive=str(archive)), ensure_ascii=False))
    return int(final['status'] != 'completed')


if __name__ == '__main__':
    raise SystemExit(main())
