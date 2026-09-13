"""Opt-in bounded candidate fallback; only original native Cover can accept it.

This is not an alternative proof backend. No diagnostic unreachable result is
used to classify the original intent. Original RTL, UT and reset remain frozen.
"""
import json
from pathlib import Path
import subprocess
import sys

from process_runner import run
from run_records import save, utc
from cycle_replay import digest
from witness_sampling import frozen_inputs, import_sample


def candidates(source, replay, label, directory, yosys, eda_shell, count, seed):
    design, config, job, goals = frozen_inputs(source, replay)
    if config.get('environment', {}).get('boundary') != 'independent-dut-v1':
        raise ValueError('encoded candidates require the explicit independent DUT boundary')
    goal = next(g for g in goals if g['label'] == label)
    directory.mkdir(parents=True, exist_ok=False)
    record = dict(policy='encoded-native-checked-v1', source_job=job['fingerprint'],
                  original_goal_proven=False, attempts=[], remote_llm_requests=0,
                  diversity_policy='bounded-single-cell-on-duplicate-v1')
    script = Path(__file__).with_name('encoded_witness_probe.py')
    record['implementation_sha256'] = {p.name:digest(p) for p in (
        Path(__file__), script, Path(__file__).with_name('encoded_initialization.py'),
        Path(__file__).with_name('encoded_past.py'))}
    # Establish the auxiliary model's own proved length first. A two-state
    # witness may rely on initially arbitrary state and be too short for a
    # known-state candidate. This is new solving, not padding the old trace.
    horizon = None
    prior_inputs = {}
    distinct_mode = False
    for index in range(count):
        item = dict(index=index, started_utc=utc(), status='running')
        record['attempts'].append(item)
        save(directory/'search.json', record)
        out = directory/f'candidate-{index}'
        command = [sys.executable, str(script), '--source-solve', str(source),
            '--replay-config', str(replay), '--label', label, '--out', str(out),
            '--project', str(directory/f'project-{index}'), '--yosys', str(yosys),
            '--eda-shell', str(eda_shell), '--boundary', 'frozen-source',
            '--noncontending-tristates']
        if index:
            command += ['--trace-cycles', str(horizon), '--trace-seed', str(seed+index)]
            if distinct_mode:
                for path in prior_inputs.values():
                    command += ['--avoid-stimulus', path]
        try:
            with (directory/f'candidate-{index}.log').open('x') as log:
                run(command, stdout=log, stderr=subprocess.STDOUT, timeout=1200, check=True)
            summary = json.loads((out/'summary.json').read_text())
            item['status'] = summary['status']
            if summary['status'] == 'no_distinct_candidate':
                # Failed subset search is not unreachability of the original
                # goal. Try another deterministic cell within the SAME budget.
                continue
            if summary['status'] != 'covered':
                # Uniform seed changes are only soft preferences, not a cure
                # for an unsupported or inconsistent auxiliary model.
                return
            sampled = import_sample(out/'witness.vcd', label, design, event_mode=True)
            if horizon is None:
                horizon = sampled['cycles']
                record.update(original_cycles=goal['cycles'], auxiliary_proved_cycles=horizon)
            row = {k:goal[k] for k in ('generationLabel','utModule','utSourceSha256','fingerprint','engine')}
            row.update(sampled)
            row.update(origin='encoded-native-candidate-v1', original_goal_proven=False,
                       auxiliary_encoding=str(out/'identity.json'))
            item['input_fingerprint'] = row['inputFingerprint']
            duplicate = row['inputFingerprint'] in prior_inputs
            item['duplicate'] = duplicate
            if duplicate:
                distinct_mode = True
            else:
                prior_inputs[row['inputFingerprint']] = str(out/'witness.json')
            yield row
        except Exception as error:
            item.update(status='failed', error=str(error))
            raise
        finally:
            item['finished_utc'] = utc()
            save(directory/'search.json',record)
