"""RQ1-matched historical-prefix stimulus sweep; no model calls.

Keep every historical native-valid witness in its original order. Measure the
1..4 prefixes by real VDB merges, verify N=4 against RQ1, then extend only pools
that originally reached four. New failures stop that property, never erase the
historical pool. Historical and new costs are kept separate, never imputed.
"""
import backend_imports
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from contextlib import contextmanager
from copy import deepcopy
import csv
import importlib.metadata
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time

from continue_rvprobe_quality import load_archived_bundle, restore_cache, verify_artifacts
from coverage_flow import HavenSimulation
from cycle_replay import digest, witness_frames
from encoded_candidate_search import candidates as encoded_candidates
from haven_design_batch import archive_design
from haven_shared import render_witness_sequence
from isolated_replay import IsolatedSimulation, independent_batches
from offline_validation import no_model_calls
from run_records import fingerprint, fresh_directory, save, utc
from sequence_framework import ROOT
from sweep_frozen_stimuli import eligible_generations, solve_pool
from witness_sampling import frozen_inputs
from rvprobe.backend.replay import attach
from rvprobe.backend.runtime import require_receipt

POLICY = 'rq1-verified-historical-four-plus-extension-v1'


def sources(selection, rq1):
    snapshot = json.loads(selection.read_text())
    if digest(rq1) != snapshot['rq1_sha256']:
        raise ValueError('RQ1 CSV differs from the selected-run provenance snapshot')
    with rq1.open(newline='') as stream:
        expected = {r['design']: r for r in csv.DictReader(stream) if r['method'] == 'rvprobe'}
    picked = {r['design']: r for r in snapshot['selected_sir_runs']}
    if len(expected) != 16 or set(expected) != set(picked):
        raise ValueError('source selection must match all 16 RQ1 designs')
    result = {}
    for name, row in expected.items():
        selected = picked[name]
        source = Path(selected['source'])
        if digest(source) != selected['sha256']:
            raise ValueError(f'RQ1 source summary changed: {name}')
        summary = json.loads(source.read_text())
        arm = summary['arms']['rvprobe']
        if arm['status'] != 'completed' or abs(arm['final']['score'] - float(row['coverage_percent'])) > 1e-9:
            raise ValueError(f'RQ1 selected coverage/completion does not match {name}')
        result[name] = dict(source=str(source), sha256=selected['sha256'],
                            expected_coverage=float(row['coverage_percent']))
    return result


def instrument_encoded_tcl(script):
    """Add wall-clock observations, without changing commands or solver options."""
    lines, proves, replots = [], 0, 0
    for line in script.splitlines():
        if line.startswith('prove -property ') or line.startswith('set replot_status [visualize -replot '):
            proves += line.startswith('prove -property ')
            replots += line.startswith('set replot_status ')
            lines += ['set rvp_measure [time {', line, '}]',
                      'puts "RQP_SOLVE_US [lindex $rvp_measure 0]"']
        else:
            lines.append(line)
    if proves != 1 or replots > 1:
        raise ValueError('unexpected auxiliary Tcl structure; refuse unmeasured solver run')
    return '\n'.join(lines) + '\n'


@contextmanager
def measured_encoding(reuse=None):
    """Instrument only generated Tcl in this worker, not the archived backend."""
    from rvprobe.backend import encoding, candidates as candidate_backend
    original = encoding.run
    original_solve = candidate_backend.solve

    def solve_and_archive(*args):
        result = original_solve(*args)
        options = args[-1]
        from experiment_storage import configured_roots
        roots = configured_roots()
        if roots:
            work, archive = roots
            # Encoding has closed JG before returning. Preserve all evidence,
            # including rejected candidates, and release only verified copies.
            for directory in (options.out,options.project):
                directory = directory.absolute()
                if directory.is_dir() and not directory.is_symlink():
                    if directory == work or not directory.is_relative_to(work):
                        raise ValueError('closed solver directory is outside the configured work root')
                    archive_design(directory,archive/directory.relative_to(work),
                        dict(status='completed',scope='closed-solver-io-only',
                             native_acceptance_implied=False),True)
        return result

    def measured(command, *args, **kwargs):
        if len(command) == 3 and command[1] == '-c':
            inner = shlex.split(command[2])
            if inner and inner[0] == 'jg' and '-tcl' in inner:
                script = Path(inner[inner.index('-tcl') + 1])
                before = digest(script)
                script.write_text(instrument_encoded_tcl(script.read_text()))
                save(script.with_name('timing-instrumentation.json'),
                     dict(original_sha256=before, instrumented_sha256=digest(script), semantics_changed=False))
        return original(command, *args, **kwargs)

    encoding.run = measured
    candidate_backend.solve = solve_and_archive
    if reuse is not None:
        def cached_solve(design, config, job, goal, environment, options):
            old = Path(reuse)/options.out.name
            summary_path = old/'summary.json'
            if not summary_path.is_file():
                return solve_and_archive(design, config, job, goal, environment, options)
            identity = json.loads((old/'identity.json').read_text())
            wanted = dict(source_job=job['fingerprint'], original_goal=goal,
                trace_cycles=options.trace_cycles, trace_preference=options.trace_preference,
                trace_seed=options.trace_seed, engine_mode=options.engine_mode,
                solve_time_limit=options.jg_time_limit,
                noncontending_tristates=options.noncontending_tristates)
            if any(identity.get(k) != v for k,v in wanted.items()):
                raise ValueError('cached encoded solve differs from frozen inputs/options')
            for name, sha in identity['implementation_sha256'].items():
                if digest(Path(encoding.__file__).with_name(name)) != sha:
                    raise ValueError('cached encoded solver implementation changed')
            summary = json.loads(summary_path.read_text())
            if summary['source_job'] != job['fingerprint'] or summary['label'] != goal['label']:
                raise ValueError('cached encoded summary differs from frozen property')
            if summary['status'] == 'covered' and not (old/'witness.vcd').is_file():
                raise ValueError('cached encoded witness is missing')
            options.out.symlink_to(old.resolve(), target_is_directory=True)
            return summary
        candidate_backend.solve = cached_solve
    try:
        yield
    finally:
        encoding.run = original
        candidate_backend.solve = original_solve


def encoded_time(directory):
    total, complete = 0.0, True
    for script in sorted(directory.glob('candidate-*/solve.tcl')):
        log = script.with_name('jg.log')
        values = re.findall(r'^RQP_SOLVE_US ([0-9.]+)$', log.read_text(), re.M) if log.is_file() else []
        complete &= bool(values)
        total += sum(float(x) / 1_000_000 for x in values)
    return total if complete else None


def checkpoint(pair, bundle, design, arm):
    """Validate all historical frames and their association with round/property."""
    sequences, frames, round_ids, candidates = list(bundle['sequences']), [], [], {}
    for row in arm['rounds']:
        number = row['round']
        path = pair / 'rvprobe' / f'round-{number}' / 'candidate.json'
        candidate = json.loads(path.read_text())
        if len(candidate['sequences']) != row['added_sequences']:
            raise ValueError(f'accepted sequence count changed in round {number}')
        sequences.extend(candidate['sequences'])
        frames.extend(candidate['frames'])
        round_ids.extend([number] * len(candidate['sequences']))
        candidates[number] = candidate
    if len(sequences) != arm['sequence_count'] or len(frames) != arm['witness_frames']:
        raise ValueError('historical accepted checkpoint totals changed')
    batches = independent_batches(bundle, design, sequences, frames)
    accepted = {}
    for number, (code, rows) in zip(round_ids, batches[len(bundle['sequences']):], strict=True):
        if not rows or 'ltl' not in rows[0]:
            raise ValueError('historical sequence is not a native-checked LTL witness')
        label = rows[0]['ltl']['label']
        names = re.findall(r'\bclass\s+(\w+)\s+extends\b', code[0])
        accepted.setdefault((number, label), []).append(dict(source=code[0], name=names[0], frames=rows))
    return batches, accepted, candidates


def historical_properties(flow, accepted, candidates):
    result = []
    for number, path, generation in eligible_generations(flow):
        if number not in candidates:
            continue
        solve = Path(generation['sources']).parent / 'solve'
        _, _, job, goals = frozen_inputs(solve, flow / 'manifest/replay.json')
        meta = {m['label']: m for m in candidates[number]['metadata']['sampling']}
        for goal in goals:
            label = goal['label']
            items = accepted.pop((number, label), [])
            passed = [r for r in (meta.get(label, {}).get('native_selection') or {}).get('attempts', [])
                      if r['status'] == 'passed']
            if len(passed) != len(items) or len(items) > 4:
                raise ValueError(f'historical pool provenance mismatch: round {number} {label}')
            fingerprints = [p['input_fingerprint'] for p in passed]
            if len(set(fingerprints)) != len(fingerprints):
                raise ValueError('historical pool contains duplicate stimuli')
            for item, provenance in zip(items, passed, strict=True):
                item.update(fingerprint=provenance['input_fingerprint'], historical=True,
                            new_solver_prefix_seconds=0.0)
            route = 'encoded' if any('/encoded/' in p.get('witness', '') for p in passed) else 'soft'
            prop = dict(round=number, label=label, original_status=goal['status'],
                        original_solve_seconds=goal.get('ms', 0) / 1000,
                        ltl_sha256=digest(Path(generation['sources']) / 'model.ltl'),
                        source_summary=str(path), source_solve=str(solve),
                        historical_count=len(items), extension_route=route,
                        accepted=[{k:v for k,v in i.items() if k not in ('frames','source')} for i in items],
                        rejected=[], status='pending', new_solver_seconds=0.0)
            result.append((prop, items, job, goal))
    if accepted:
        raise ValueError('historical accepted stimuli have no matching frozen property')
    return result


def measure(simulate, directory, bundle, design, pools, cap):
    sequences, frames = list(bundle['sequences']), []
    for items in pools:
        for item in items[:cap]:
            sequences.append(render_witness_sequence(design, item['frames'], item['name'], len(frames)))
            frames.extend(deepcopy(item['frames']))
    began = time.monotonic()
    coverage = simulate(directory, sequences, frames)
    return dict(count=cap, actual_stimuli=len(sequences)-len(bundle['sequences']),
                coverage={k:coverage[k] for k in ('percent','score','bins')},
                coverage_merge_seconds=time.monotonic()-began)


def extra_time(prop, cap):
    if cap <= 4 or prop['historical_count'] < 4:
        return 0.0
    if len(prop['accepted']) >= cap:
        return prop['accepted'][cap-1]['new_solver_prefix_seconds']
    return prop['new_solver_seconds']


def extend(prop, items, job, goal, simulate, design, replay, manifest, directory, index, checkpoint_save,
           reuse=None):
    if len(items) < 4:
        prop['status'] = 'retained-historical-shortfall'
        return
    prop['status'] = 'extending'
    checkpoint_save()
    print(json.dumps(dict(label=prop['label'], round=prop['round'], phase='extension', route=prop['extension_route'])), flush=True)
    prior = {i['fingerprint'] for i in items}
    began = time.monotonic()

    def accept(row, spent, ordinal):
        if row['inputFingerprint'] in prior:
            return
        prior.add(row['inputFingerprint'])
        rows = witness_frames(design, replay, row, 10_000_000 + index*256 + ordinal)
        attach(rows, goal)
        name = f'rqp_r{prop["round"]}_{prop["label"]}_{ordinal}'
        code = render_witness_sequence(design, rows, name, 0)
        started = time.monotonic()
        try:
            cache, result = simulate.measure_one([code], rows)
            require_receipt(rows[0]['ltl'], result['replay'])
        except ValueError as error:
            detail = getattr(error, 'diagnostics', {})
            if detail.get('kind') != 'formal_replay_semantics_mismatch':
                raise
            prop['rejected'].append(dict(fingerprint=row['inputFingerprint'], error=str(error), diagnostics=detail))
            prop['stop_reason'] = 'native-replay-rejected'
            return False
        metadata = dict(name=name, fingerprint=row['inputFingerprint'], historical=False,
                        new_solver_prefix_seconds=spent, replay_seconds=time.monotonic()-started,
                        cache=str(cache), witness=row['witnessFile'])
        items.append(dict(**metadata, frames=rows, source=code))
        prop['accepted'].append(metadata)
        checkpoint_save()
        return True

    if prop['extension_route'] == 'encoded':
        native = manifest['native_witness_selection']
        backend = encoded_candidates(Path(prop['source_solve']), Path(simulate.simulator.bundle['replay_config']),
            prop['label'], directory, Path(native['auxiliary_yosys']), ROOT/'experiments/eda-shell',
            16, manifest['sampling']['seed'], time_limit=native['auxiliary_time_limit'])
        try:
            with measured_encoding(reuse):
                for ordinal, row in enumerate(backend):
                    if accept(row, encoded_time(directory), ordinal) is False or len(items) == 8:
                        break
        finally:
            backend.close()
        prop['new_solver_seconds'] = encoded_time(directory)
        prop['sampling'] = json.loads((directory/'search.json').read_text())
    else:
        if reuse is not None and (reuse/'pool.json').is_file():
            from sweep_frozen_stimuli import timed_script
            from witness_sampling import import_sample, select_cover
            sv = reuse/Path(job['sv']).name
            clock_names = {c['port'] for c in job.get('clocks', [])}
            drives = [p for p in job['abi']['ports'] if p['role']=='Drive' and p['name'] not in clock_names]
            expected = timed_script(job, goal, sv, reuse, drives, 8, manifest['sampling']['seed'],
                                    manifest['sampling']['time_limit'], manifest['jg_time_limit'])
            if (sv.read_text() != select_cover(Path(job['sv']).read_text(),job['labels'],goal['label']) or
                    (reuse/'sample.tcl').read_text() != expected):
                raise ValueError('cached soft sampling differs from frozen source/options')
            pool = json.loads((reuse/'pool.json').read_text())
            for row in pool['pool']:
                imported = import_sample(Path(row['witnessFile']), goal['label'], design, bool(job.get('clocks')))
                if imported['inputFingerprint'] != row['inputFingerprint'] or imported['cycles'] != goal['cycles']:
                    raise ValueError('cached soft witness changed')
                if digest(Path(row['configFile'])) != row['configSha256']:
                    raise ValueError('cached soft witness configuration changed')
            directory.parent.mkdir(parents=True, exist_ok=True)
            directory.symlink_to(reuse.resolve(), target_is_directory=True)
        else:
            pool = solve_pool(job, goal, design, replay, directory, 8, manifest['sampling']['seed'],
                              manifest['sampling']['time_limit'], ROOT/'experiments/eda-shell',
                              prove_limit=manifest['jg_time_limit'])
        prop['sampling'] = {k:v for k,v in pool.items() if k != 'pool'}
        prop['new_solver_seconds'] = pool['solver_seconds']
        for ordinal, row in enumerate(pool['pool']):
            if accept(row, row['solver_prefix_seconds'], ordinal) is False or len(items) == 8:
                break
    prop.update(status='complete' if len(items) == 8 else 'extension-shortfall',
                extension_wall_seconds=time.monotonic()-began)
    checkpoint_save()
    print(json.dumps(dict(label=prop['label'], actual=len(items), status=prop['status'])), flush=True)


def recover_properties(jobs, prior, simulate, design):
    """Recover measured prefixes; never infer acceptance from solver success."""
    old = {(p['round'],p['label']):p for p in prior['properties']}
    for prop,items,_,_ in jobs:
        previous = old.pop((prop['round'],prop['label']))
        for key in ('ltl_sha256','historical_count','extension_route','source_summary','source_solve'):
            if prop[key] != previous[key]:
                raise ValueError('resume property provenance changed: '+key)
        if previous['accepted'][:len(items)] != prop['accepted']:
            raise ValueError('resume changed the historical stimulus prefix')
        for meta in previous['accepted'][len(items):]:
            cache = Path(meta['cache'])
            result = json.loads((cache/'coverage.json').read_text())
            verify_artifacts(result['artifact_sha256'])
            rows = json.loads((cache/'schedule.json').read_text())
            require_receipt(rows[0]['ltl'], result['replay'])
            code = render_witness_sequence(design, rows, meta['name'], 0)
            inputs = json.loads((cache/'inputs.json').read_text())
            simulator = simulate.simulator
            key = fingerprint(dict(bundle=simulator.bundle['fingerprint'],seed=simulator.seed,
                                   config=simulator.config,sources=[code],frames=rows))
            if cache.name != key or inputs['sequences'] != [fingerprint(code)]:
                raise ValueError('resume replay cache does not match accepted stimulus')
            (simulate.cache/key).symlink_to(cache.resolve(),target_is_directory=True)
            simulate.completed[key] = result
            items.append(dict(**meta,frames=rows,source=code))
        prop.update(deepcopy(previous))
        if prop['status'] == 'extension-failed' and 'SFCOR' in prop.get('error',''):
            prop['infrastructure_attempt'] = {k:prop.pop(k) for k in ('error','traceback') if k in prop}
            prop['status'] = 'pending'
    if old:
        raise ValueError('resume contains unexpected properties')


def worker(args, selected):
    archive = args.out / args.design
    if archive.exists():
        raise FileExistsError(archive)
    work = Path(tempfile.mkdtemp(prefix='rvp-rq1-count-'+args.design+'-', dir='/dev/shm'))
    os.environ['RVPROBE_STORAGE_WORK_ROOT'] = str(work)
    os.environ['RVPROBE_STORAGE_ARCHIVE_ROOT'] = str(archive)
    began = time.monotonic()
    summary = dict(status='running', design=args.design, policy=POLICY, started_utc=utc(),
                   source=selected, properties=[], cells=[], new_llm_calls=0, new_llm_tokens=0,
                   scratch=str(work), python=sys.version, python_executable=sys.executable,
                   python_packages={d.metadata['Name']:d.version for d in importlib.metadata.distributions()})

    def checkpoint_save():
        save(work/'progress.json', summary)

    checkpoint_save()
    try:
        prior = None
        if getattr(args,'resume_from',None):
            prior = json.loads(args.resume_from.read_text())
            if prior['source'] != selected or prior['design'] != args.design or prior['policy'] != POLICY:
                raise ValueError('resume source/selection/policy differs')
            summary['recovery'] = dict(source=str(args.resume_from.resolve()),
                source_sha256=digest(args.resume_from), scratch=prior['scratch'],
                note='completed solver candidates and native-valid replays reused; interrupted incomplete work is separate overhead')
        pair = Path(selected['source']).parent
        flow = pair.parent
        outer = json.loads((pair/'summary.json').read_text())
        arm = outer['arms']['rvprobe']
        manifest = json.loads((pair/'manifest.json').read_text())
        if manifest['sampling']['sequences_per_intent'] != 4:
            raise ValueError('RQ1 source does not use the four-stimulus baseline')
        bundle, design, replay, relocation = load_archived_bundle(flow, flow/'implementation/haven')
        config = json.loads((ROOT/'experiments/designs/haven_eda.json').read_text())
        config['eda_env'] = {'shell':str(ROOT/'experiments/eda-shell')}
        simulate = IsolatedSimulation(HavenSimulation(bundle, design, config, manifest['seed']), work/'replay-cache')
        batches, historical, candidates = checkpoint(pair, bundle, design, arm)
        verify_artifacts(arm['final']['artifact_sha256'])
        with no_model_calls():
            restored = restore_cache(simulate, pair/'replay-cache', batches)
            save(work/'restored-cache.json', restored)
            jobs = historical_properties(flow, historical, candidates)
            if prior:
                recover_properties(jobs,prior,simulate,design)
            summary['properties'] = [p for p,_,_,_ in jobs]
            pools = [items for _,items,_,_ in jobs]
            baseline = simulate(work/'baseline', bundle['sequences'], [])
            summary['baseline'] = {k:baseline[k] for k in ('score','percent','bins')}
            summary['historical_phases'] = arm['costs']['phases']
            summary['historical_goal_solve_seconds'] = sum(p['original_solve_seconds'] for p in summary['properties'])
            save(work/'manifest.json', dict(policy=POLICY, source=selected, source_manifest_sha256=digest(pair/'manifest.json'),
                source_settings=manifest, relocation=relocation, model_calls_allowed=False,
                runtime_sha256={str(p.relative_to(ROOT)):digest(p) for p in (
                    Path(__file__).resolve(),ROOT/'experiments/sweep_frozen_stimuli.py',
                    ROOT/'experiments/experiment_storage.py',ROOT/'experiments/prune_archived_caches.py',
                    ROOT/'flake.nix',ROOT/'flake.lock')},
                timing_policy='historical timing retained separately; new cumulative JG prove/replot wall time only',
                shortfall_policy='no new work for historical pools below four; stop new search on failure'))
            reference = measure(simulate, work/'reference-four', bundle, design, pools, 4)
            if reference['coverage']['bins'] != arm['final']['bins'] or abs(reference['coverage']['score']-selected['expected_coverage']) > 1e-9:
                raise ValueError('N=4 does not reproduce RQ1; extension not started')
            summary['reference_check'] = dict(passed=True, coverage=reference['coverage'],
                                              merge_seconds=reference['coverage_merge_seconds'])
            checkpoint_save()
            print(json.dumps(dict(design=args.design, phase='reference-four-verified', score=reference['coverage']['score'])), flush=True)
            for cap in range(1,5):
                cell = reference if cap == 4 else measure(simulate, work/f'count-{cap}', bundle, design, pools, cap)
                cell.update(properties=len(jobs),properties_reaching_cap=sum(len(p)>=cap for p in pools),
                            new_solver_seconds=0.0,new_solver_time_complete=True,historical_prefix_reused=True)
                summary['cells'].append(cell)
            checkpoint_save()
            for index, (prop, items, job, goal) in enumerate(jobs):
                if prior and prop['status'] not in ('pending','extending'):
                    continue
                try:
                    reuse = Path(prior['scratch'])/'sampling'/f'r{prop["round"]}_{prop["label"]}' if prior else None
                    if reuse is not None and not reuse.exists():
                        reuse = None
                    extend(prop, items, job, goal, simulate, design, replay, manifest,
                           work/'sampling'/f'r{prop["round"]}_{prop["label"]}', index, checkpoint_save,reuse=reuse)
                except Exception as error:
                    import traceback
                    prop.update(status='extension-failed', error=str(error), traceback=traceback.format_exc(),
                                new_solver_seconds=None)
                    checkpoint_save()
                    print(json.dumps(dict(design=args.design, label=prop['label'], error=str(error))), flush=True)
            for cap in range(5,9):
                cell = measure(simulate, work/f'count-{cap}', bundle, design, pools, cap)
                times = [extra_time(p, cap) for p in summary['properties']]
                cell.update(properties=len(jobs), properties_reaching_cap=sum(len(p)>=cap for p in pools),
                            new_solver_seconds=sum(t for t in times if t is not None),
                            new_solver_time_complete=all(t is not None for t in times), historical_prefix_reused=True)
                summary['cells'].append(cell)
                checkpoint_save()
                print(json.dumps(dict(design=args.design, count=cap, score=cell['coverage']['score'],
                                      actual=cell['actual_stimuli'])), flush=True)
            summary['status'] = 'completed'
            save(work/'accepted-pools.json', pools)
    except Exception as error:
        import traceback
        summary.update(status='failed', error=str(error), traceback=traceback.format_exc())
        print(summary['traceback'], flush=True)
    summary.update(finished_utc=utc(), elapsed_seconds=time.monotonic()-began)
    save(work/'summary.json', summary)
    checkpoint_save()
    archive_design(work, archive, dict(status=summary['status']), True)
    return int(summary['status'] != 'completed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--selection', type=Path, required=True)
    parser.add_argument('--rq1', type=Path, required=True)
    parser.add_argument('--out', type=Path)
    parser.add_argument('--design')
    parser.add_argument('--resume-from',type=Path,help='a matching progress/summary JSON; preserve all validated prefixes')
    parser.add_argument('--designs', nargs='+')
    parser.add_argument('--jobs', type=int, default=3)
    parser.add_argument('--audit-only', action='store_true')
    args = parser.parse_args()
    selected = sources(args.selection, args.rq1)
    if args.audit_only:
        print(json.dumps(dict(designs=selected, mean=sum(r['expected_coverage'] for r in selected.values())/16), ensure_ascii=False))
        return 0
    if args.out is None or not 1 <= args.jobs <= 3:
        parser.error('--out and 1..3 jobs required')
    args.out = args.out.absolute()
    if args.design:
        return worker(args, selected[args.design])
    names = args.designs or list(selected)
    if not set(names) <= selected.keys():
        parser.error('unknown design')
    fresh_directory(args.out)
    record = dict(status='running', policy=POLICY, started_utc=utc(),jobs=args.jobs,
        selection=str(args.selection.resolve()), selection_sha256=digest(args.selection),
        rq1=str(args.rq1.resolve()), rq1_sha256=digest(args.rq1), counts=list(range(1,9)),
        designs={n:dict(status='pending', **selected[n]) for n in names}, new_llm_calls=0,new_llm_tokens=0)
    began = time.monotonic()
    save(args.out/'sources.json', selected)
    save(args.out/'progress.json', record)

    def invoke(name):
        command = [sys.executable, str(Path(__file__).resolve()), '--selection',str(args.selection.resolve()),
                   '--rq1',str(args.rq1.resolve()),'--out',str(args.out),'--design',name]
        with (args.out/f'{name}.log').open('x') as stream:
            result = subprocess.run(command,stdout=stream,stderr=subprocess.STDOUT)
        path = args.out/name/'summary.json'
        if not path.is_file():
            return dict(status='failed',returncode=result.returncode,error='missing worker summary')
        measured = json.loads(path.read_text())
        return {k:measured[k] for k in ('status','elapsed_seconds','reference_check','error') if k in measured}

    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        pending = {pool.submit(invoke,n):n for n in names}
        for future in as_completed(pending):
            name = pending[future]
            try:
                record['designs'][name].update(future.result())
            except Exception as error:
                record['designs'][name].update(status='failed',error=str(error))
            save(args.out/'progress.json',record)
            print(json.dumps(dict(design=name,**record['designs'][name])),flush=True)
    record.update(status='completed' if all(r['status']=='completed' for r in record['designs'].values())
                  else 'completed_with_failures',finished_utc=utc(),elapsed_seconds=time.monotonic()-began)
    save(args.out/'summary.json',record)
    save(args.out/'progress.json',record)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
