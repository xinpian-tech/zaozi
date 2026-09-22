"""Offline 1..8 stimulus-count sensitivity experiment on frozen model properties.

One fixed-seed maximum-size pool per property, independently replayed and merged
as nested prefixes. No model calls, temporal edits, timeout escalation or solver
fallback. Timings are measured prefix costs, not eight independent cold runs.
"""
import backend_imports
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from copy import deepcopy
import csv
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time

from continue_rvprobe_quality import load_archived_bundle, verify_artifacts
from coverage_flow import HavenSimulation
from cycle_replay import digest, witness_frames
from haven_design_batch import archive_design
from haven_shared import render_witness_sequence
from isolated_replay import IsolatedSimulation
from offline_validation import no_model_calls
from run_records import fingerprint, framework_hashes, fresh_directory, save, utc
from sequence_framework import ROOT
from witness_sampling import (frozen_inputs, import_sample, render_sampling,
                              select_cover, tcl_word, validate_sample_config)
from rvprobe.backend.process import run
from rvprobe.backend.replay import attach
from rvprobe.backend.runtime import require_receipt

POLICY = 'frozen-property-stimulus-prefix-1-to-8-v1'


def timed_script(job, goal, sv, out, drives, count, seed, limit, prove_limit=None):
    """Instrument the existing soft-preference policy without changing a cover.

    Export a freshly solved, fixed-length initial witness, then up to seven
    distinct resamples. Every replot, including duplicates, contributes time.
    The existing first non-covered replot exits JG: there is no retry/escalation.
    """
    text = render_sampling(job, goal['label'], sv, out, drives, goal['cycles'],
                           count, seed, limit)
    if prove_limit is not None:
        if not re.fullmatch(r'[1-9][0-9]*[smh]', prove_limit):
            raise ValueError('invalid initial proof budget')
        text = text.replace(f'set_prove_time_limit {limit}\n',
                            f'set_prove_time_limit {prove_limit}\n', 1)
    setup, text = text.split('prove -all\n', 1)
    # Jasper overrides Tcl's `clock` command with its design-clock command.
    # Use the native `time` primitive instead, as the production sampler does.
    text = ('set rvp_setup_timing [time {\n' + setup + '\n}]\n'
        'set rvp_setup_ms [expr {int([lindex $rvp_setup_timing 0] / 1000)}]\n'
        'set rvp_prove_timing [time {prove -all}]\n'
        'set rvp_solve_ms [expr {int([lindex $rvp_prove_timing 0] / 1000)}]\n'
        'puts "RVP_PROVE $rvp_solve_ms [expr {$rvp_setup_ms + $rvp_solve_ms}]"\n' + text)
    initial = [
        f'set timing [time {{set status [visualize -replot -force -silent -proof_time {limit} -window visualize:0]}}]',
        'set rvp_solve_ms [expr {$rvp_solve_ms + int([lindex $timing 0] / 1000)}]',
        'puts "RVP_INITIAL $status $rvp_solve_ms [expr {$rvp_setup_ms + $rvp_solve_ms}]"',
        'if {$status != "covered"} { error "initial fixed-horizon witness unavailable" }',
        'if {[visualize -get_type -window visualize:0] != "cover"} { error "not a cover trace" }',
        f'visualize -save -vcd {tcl_word(out / "initial.vcd")} -force -window visualize:0',
        f'visualize -save -config_only -window visualize:0 -force {tcl_word(out / "initial.config.tcl")}',
        'puts "RVP_ACCEPT initial 0 $rvp_solve_ms [expr {$rvp_setup_ms + $rvp_solve_ms}]"',
        'set signature [list]',
    ]
    initial.extend(f"lappend signature [visualize -get_value {p['name']} {{1:$}} -radix 2 -window visualize:0]"
                   for p in drives)
    initial.append('dict set seen $signature 1')
    text = text.replace('set seen [dict create]\n',
                        'set seen [dict create]\n' + '\n'.join(initial) + '\n', 1)
    text = re.sub(r'puts "JGSAMPLE_STATUS (\d+) \$status"', lambda m:
        'set rvp_solve_ms [expr {$rvp_solve_ms + int([lindex $timing 0] / 1000)}]\n'
        + m[0] + '\n' + f'puts "RVP_ATTEMPT {m[1]} $status $rvp_solve_ms [expr {{$rvp_setup_ms + $rvp_solve_ms}}]"', text)
    text = re.sub(r'puts "JGSAMPLE_ACCEPT (\d+) \$accepted \[expr \{\{?int\(\[lindex \$timing 0\] / 1000\)\}?\}\]"',
        lambda m: m[0] + '\n' + f'puts "RVP_ACCEPT {m[1]} $accepted $rvp_solve_ms [expr {{$rvp_setup_ms + $rvp_solve_ms}}]"', text)
    # Match defensively: missing timing instrumentation must not silently yield
    # a zero-cost or incomplete time curve.
    expected = max(0, count - 1) * 2
    if len(re.findall(r'puts "RVP_ACCEPT \d+', text)) != expected:
        raise ValueError('sampler timing instrumentation no longer matches production Tcl')
    return text


def solve_pool(job, goal, design, config, directory, count, seed, limit, shell, *, prove_limit=None):
    directory.mkdir(parents=True, exist_ok=False)
    for key, filename in [('resetSequence', 'reset.seq'), ('initialState', 'initial.state'),
                          ('resetSnapshotState', 'reset-snapshot.state')]:
        if job.get(key): (directory / filename).write_text(job[key])
    sv = directory / Path(job['sv']).name
    sv.write_text(select_cover(Path(job['sv']).read_text(), job['labels'], goal['label']))
    clock_names = {c['port'] for c in job.get('clocks', [])}
    drives = [p for p in job['abi']['ports'] if p['role'] == 'Drive' and p['name'] not in clock_names]
    script = directory / 'sample.tcl'
    script.write_text(timed_script(job, goal, sv, directory, drives, count, seed, limit, prove_limit))
    start = time.monotonic()
    failure = None
    environment = {k: v for k, v in os.environ.items() if k not in (
        'RVPROBE_LLM_API_KEY', 'RVPROBE_LLM_BASE_URL', 'OPENAI_API_KEY', 'OPENAI_BASE_URL')}
    try:
        with (directory / 'jg.log').open('w') as stream:
            run([str(shell), '-c', shlex.join(['jg', '-batch', '-tcl', str(script),
                '-proj', str(directory / 'project')])], cwd=directory, env=environment,
                stdout=stream, stderr=subprocess.STDOUT, check=True,
                timeout=300 + 2 * count * (int(limit[:-1]) + 5))
    except (subprocess.SubprocessError, OSError) as error:
        failure = str(error)
    elapsed = time.monotonic() - start
    log = (directory / 'jg.log').read_text()
    if 'JGDONE' not in log.splitlines() and failure is None:
        failure = 'JG stopped before JGDONE; see jg.log'
    rows, seen = [], set()
    accepts = re.findall(r'^RVP_ACCEPT (initial|\d+) \d+ (\d+) (\d+)$', log, re.M)
    attempts = re.findall(r'^RVP_ATTEMPT (\d+) (\S+) (\d+) (\d+)$', log, re.M)
    for ident, solve_ms, elapsed_ms in accepts:
        stem = 'initial' if ident == 'initial' else 'sample-' + ident
        try:
            conf = directory / (stem + '.config.tcl')
            validate_sample_config(conf, job['top'], goal['label'], goal['cycles'])
            row = import_sample(directory / (stem + '.vcd'), goal['label'], design, bool(job.get('clocks')))
            if row['cycles'] != goal['cycles']:
                raise ValueError('fresh witness length differs from frozen horizon')
            witness_frames(design, config, row, 0)
            if row['inputFingerprint'] in seen: continue
            seen.add(row['inputFingerprint'])
            row.update(solver_prefix_seconds=int(solve_ms) / 1000,
                       core_prefix_seconds=int(elapsed_ms) / 1000,
                       configFile=str(conf), configSha256=digest(conf))
            rows.append(row)
        except (ValueError, OSError) as error:
            failure = 'candidate import failed: ' + str(error)
            break
    total_solve = [int(x) / 1000 for x in re.findall(
        r'^RVP_(?:ACCEPT (?:initial|\d+) \d+|ATTEMPT \d+ \S+|INITIAL \S+) (\d+) \d+$', log, re.M)]
    prove = re.search(r'^RVP_PROVE (\d+) (\d+)$', log, re.M)
    core = re.findall(r'^RVP_(?:ACCEPT (?:initial|\d+) \d+|ATTEMPT \d+ \S+|INITIAL \S+|PROVE) \d+ (\d+)$', log, re.M)
    status = 'complete' if len(rows) == count else ('stopped' if failure else 'exhausted')
    result = dict(status=status, requested=count, actual=len(rows), error=failure,
        prove_seconds=int(prove[1]) / 1000 if prove else None,
        solver_seconds=max(total_solve) if total_solve else (int(prove[1]) / 1000 if prove else None),
        core_seconds=max(map(int, core)) / 1000 if core else None,
        jg_wall_seconds=elapsed, attempts=len(attempts), pool=rows,
        policy='first-noncovered-stops; no-fallback; no-timeout-escalation')
    save(directory / 'pool.json', result)
    return result


def eligible_generations(flow):
    for rd in sorted((flow / 'paired/rvprobe').glob('round-*'),
                     key=lambda p: int(p.name.split('-')[1]) if re.fullmatch(r'round-\d+', p.name) else 10000):
        if not re.fullmatch(r'round-\d+', rd.name): continue
        summary = rd / 'generation/summary.json'
        if not summary.is_file(): continue
        result = json.loads(summary.read_text())
        if result.get('sources') and result.get('result', {}).get('goals'):
            yield int(rd.name.split('-')[1]), summary, result


def curve_time(property_record, cap, field):
    accepted = property_record.get('accepted', [])
    if property_record.get('original_status') != 'generated': return 0.0
    if len(accepted) >= cap:
        return accepted[cap - 1][field]
    return property_record['sampling'].get('solver_seconds' if field == 'solver_prefix_seconds'
                                            else 'core_seconds')


def restore_baseline(simulate, pair):
    """Reuse only hash-verified fixed Stage-1 simulations, never model stimuli."""
    simulator = simulate.simulator
    restored = []
    for source in simulator.bundle['sequences']:
        key = fingerprint(dict(bundle=simulator.bundle['fingerprint'], seed=simulator.seed,
                               config=simulator.config, sources=[source], frames=[]))
        old = pair / 'replay-cache' / key
        if not (old / 'coverage.json').is_file(): continue
        result = json.loads((old / 'coverage.json').read_text())
        inputs = json.loads((old / 'inputs.json').read_text())
        if inputs['sequences'] != [fingerprint(source)] or json.loads((old / 'schedule.json').read_text()) != []:
            raise ValueError('historical baseline cache does not match fixed input sequence')
        verify_artifacts(result['artifact_sha256'])
        simulate.cache.mkdir(parents=True, exist_ok=True)
        (simulate.cache / key).symlink_to(old, target_is_directory=True)
        simulate.completed[key] = result
        restored.append(dict(key=key, source=str(old), coverage_sha256=digest(old / 'coverage.json')))
    return restored


def worker(args):
    archive = args.out / args.design
    if archive.exists(): raise FileExistsError(archive)
    work = Path(tempfile.mkdtemp(prefix='rvp-count-' + args.design + '-', dir='/dev/shm'))
    os.environ['RVPROBE_STORAGE_WORK_ROOT'] = str(work)
    os.environ['RVPROBE_STORAGE_ARCHIVE_ROOT'] = str(archive)
    start = time.monotonic()
    summary = dict(status='running', design=args.design, started_utc=utc(), properties=[], cells=[],
                   new_llm_calls=0, new_llm_tokens=0, source_batch=str(args.source_batch), scratch=str(work))
    save(work / 'progress.json', summary)
    try:
        flow = args.source_batch / args.design / 'flow'
        bundle, design, replay, relocation = load_archived_bundle(flow, flow / 'implementation/haven')
        manifest = json.loads((flow / 'paired/manifest.json').read_text())
        config = json.loads((ROOT / 'experiments/designs/haven_eda.json').read_text())
        config['eda_env'] = {'shell': str(ROOT / 'experiments/eda-shell')}
        simulate = IsolatedSimulation(HavenSimulation(bundle, design, config, manifest['seed']), work / 'replay-cache')
        save(work / 'manifest.json', dict(policy=POLICY, source_summary_sha256=digest(flow / 'paired/summary.json'),
            framework=framework_hashes(ROOT), bundle=bundle['fingerprint'], source_relocation=relocation,
            seed=manifest['sampling']['seed'], counts=list(range(1, args.count + 1)),
            sampling_time_limit=manifest['sampling']['time_limit'], source_model=manifest['model'],
            model_requests_allowed=False, max_properties=args.max_properties,
            replay_policy='unchanged-native-ltl-check; no-alternative-solver-or-repair',
            timing_policy='single-pool-nested-prefix; measured-cumulative-prove-plus-all-replots'))
        accepted_sets = []
        with no_model_calls():
            restored = restore_baseline(simulate, flow / 'paired')
            save(work / 'restored-baseline.json', restored)
            baseline = simulate(work / 'baseline', bundle['sequences'], [])
            summary['baseline'] = {k: baseline[k] for k in ('percent', 'score', 'bins')}
            index = 0
            for number, source_summary, generated in eligible_generations(flow):
                source = Path(generated['sources']).parent / 'solve'
                _, _, job, goals = frozen_inputs(source, flow / 'manifest/replay.json')
                for goal in goals:
                    if args.max_properties and index >= args.max_properties: break
                    prop = dict(round=number, label=goal['label'], original_status=goal['status'],
                                ltl_sha256=digest(Path(generated['sources']) / 'model.ltl'),
                                source_summary=str(source_summary), source_job_fingerprint=job['fingerprint'],
                                original_solve_seconds=goal.get('ms', 0) / 1000, accepted=[], rejected=[])
                    summary['properties'].append(prop)
                    index += 1
                    if goal['status'] != 'generated':
                        prop['status'] = 'skipped-original-' + goal['status']
                        save(work / 'progress.json', summary)
                        continue
                    directory = work / 'sampling' / f'r{number}_{goal["label"]}'
                    print(json.dumps(dict(design=args.design, property=index, label=goal['label'], phase='sampling')), flush=True)
                    pool = solve_pool(job, goal, design, replay, directory, args.count,
                        manifest['sampling']['seed'], manifest['sampling']['time_limit'], ROOT / 'experiments/eda-shell')
                    prop['sampling'] = {k: v for k, v in pool.items() if k != 'pool'}
                    accepted = []
                    for i, row in enumerate(pool['pool']):
                        rows = witness_frames(design, replay, row, index * 256 + i)
                        attach(rows, goal)
                        name = f'rvp_count_r{number}_{goal["label"]}_{i}'
                        code = render_witness_sequence(design, rows, name, 0)
                        replay_start = time.monotonic()
                        try:
                            cache, result = simulate.measure_one([code], rows)
                            require_receipt(rows[0]['ltl'], result['replay'])
                        except ValueError as error:
                            details = getattr(error, 'diagnostics', {})
                            prop['rejected'].append(dict(index=i, error=str(error), diagnostics=details,
                                measured_seconds=time.monotonic() - replay_start))
                            if details.get('kind') != 'formal_replay_semantics_mismatch': raise
                            continue
                        prop['accepted'].append(dict(index=i, fingerprint=row['inputFingerprint'],
                            solver_prefix_seconds=row['solver_prefix_seconds'], core_prefix_seconds=row['core_prefix_seconds'],
                            replay_seconds=time.monotonic() - replay_start, cache=str(cache)))
                        accepted.append(dict(name=name, frames=rows))
                    prop['status'] = 'complete' if len(accepted) == args.count else 'shortfall'
                    accepted_sets.append(accepted)
                    save(work / 'progress.json', summary)
                    print(json.dumps(dict(design=args.design, label=goal['label'], accepted=len(accepted),
                                          rejected=len(prop['rejected']), sampler=pool['status'])), flush=True)
                if args.max_properties and index >= args.max_properties: break
            save(work / 'accepted-pools.json', accepted_sets)
            for cap in range(1, args.count + 1):
                sequences, frames = list(bundle['sequences']), []
                for pool in accepted_sets:
                    for item in pool[:cap]:
                        sequences.append(render_witness_sequence(design, item['frames'], item['name'], len(frames)))
                        frames.extend(deepcopy(item['frames']))
                began = time.monotonic()
                coverage = simulate(work / f'count-{cap}', sequences, frames)
                solver = [curve_time(p, cap, 'solver_prefix_seconds') for p in summary['properties']]
                jg = [curve_time(p, cap, 'core_prefix_seconds') for p in summary['properties']]
                cell = dict(count=cap, actual_stimuli=len(sequences) - len(bundle['sequences']),
                    properties=len(summary['properties']), properties_reaching_cap=sum(
                        len(p['accepted']) >= cap for p in summary['properties']),
                    solver_seconds=sum(x for x in solver if x is not None),
                    solver_time_complete=all(x is not None for x in solver),
                    core_prefix_seconds=sum(x for x in jg if x is not None),
                    coverage_merge_seconds=time.monotonic() - began,
                    coverage={k: coverage[k] for k in ('percent', 'score', 'bins')})
                summary['cells'].append(cell)
                save(work / 'progress.json', summary)
                print(json.dumps(dict(design=args.design, count=cap, stimuli=cell['actual_stimuli'],
                                      score=coverage['score'], solver_seconds=cell['solver_seconds'])), flush=True)
            summary['status'] = 'completed'
    except Exception as error:
        import traceback
        summary.update(status='failed', error=str(error), traceback=traceback.format_exc())
        print(summary['traceback'], file=sys.stderr, flush=True)
    summary.update(finished_utc=utc(), elapsed_seconds=time.monotonic() - start)
    save(work / 'summary.json', summary)
    save(work / 'progress.json', summary)
    archive_design(work, archive, dict(status=summary['status']), True)
    return int(summary['status'] != 'completed')


def batch(args):
    fresh_directory(args.out)
    source = json.loads((args.source_batch / 'summary.json').read_text())
    designs = args.designs or list(source['designs'])
    if not set(designs) <= source['designs'].keys(): raise ValueError('unknown designs')
    record = dict(status='running', policy=POLICY, source_batch=str(args.source_batch),
                  source_summary_sha256=digest(args.source_batch / 'summary.json'),
                  started_utc=utc(), jobs=args.jobs, counts=list(range(1, args.count + 1)),
                  designs={n: {'status': 'pending'} for n in designs}, new_llm_calls=0, new_llm_tokens=0)
    save(args.out / 'progress.json', record)
    start = time.monotonic()

    def invoke(name):
        command = [sys.executable, str(Path(__file__).resolve()), '--source-batch', str(args.source_batch),
                   '--out', str(args.out), '--design', name, '--count', str(args.count)]
        if args.max_properties: command.extend(['--max-properties', str(args.max_properties)])
        with (args.out / (name + '.log')).open('w') as stream:
            result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT)
        path = args.out / name / 'summary.json'
        if not path.is_file(): return dict(status='failed', returncode=result.returncode, error='worker summary missing')
        saved = json.loads(path.read_text())
        return {k: saved[k] for k in ('status', 'elapsed_seconds', 'error') if k in saved}

    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        pending = {pool.submit(invoke, name): name for name in designs}
        for future in as_completed(pending):
            name = pending[future]
            try: record['designs'][name] = future.result()
            except Exception as error: record['designs'][name] = dict(status='failed', error=str(error))
            save(args.out / 'progress.json', record)
            print(json.dumps(dict(design=name, **record['designs'][name])), flush=True)
    record.update(status='completed' if all(x['status'] == 'completed' for x in record['designs'].values())
                  else 'completed_with_failures', finished_utc=utc(), elapsed_seconds=time.monotonic() - start)
    save(args.out / 'summary.json', record)
    save(args.out / 'progress.json', record)
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-batch', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--design')
    parser.add_argument('--designs', nargs='+')
    parser.add_argument('--jobs', type=int, default=3)
    parser.add_argument('--count', type=int, default=8)
    parser.add_argument('--max-properties', type=int, help='explicit diagnostic subset only')
    args = parser.parse_args()
    args.source_batch = args.source_batch.resolve(); args.out = args.out.absolute()
    if not 1 <= args.count <= 8 or not 1 <= args.jobs <= 3: parser.error('invalid experiment bounds')
    return worker(args) if args.design else batch(args)


if __name__ == '__main__': raise SystemExit(main())
