#!/usr/bin/env python3
"""Controlled ALU framework regression: replay unchanged saved model responses, no LLM calls."""
import json
from pathlib import Path
from types import SimpleNamespace
import time

from coverage_flow import load_bundle, HavenSimulation, paired_loop
from sequence_framework import ROOT, parse_response, write_sources
from sequence_experiment import harness, DEFAULT_EDA_SHELL
from witness_sampling import expand_goals
from cycle_replay import witness_frames, digest
from haven_shared import render_witness_sequence
from run_records import save, utc, framework_hashes


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--previous', type=Path, required=True)
    parser.add_argument('--haven-root', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--reuse-solve', type=Path, help='reuse a completed first-round solve from this diagnostic')
    args = parser.parse_args()
    previous, out = args.previous.resolve(), args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    bundle, design, replay = load_bundle(previous / 'shared/bundle.json', args.haven_root)
    responses = [previous / 'paired/rvprobe' / f'round-{n}' / 'generation' / attempt / 'response.txt'
                 for n, attempt in [(1, 'attempt-2'), (2, 'attempt-1')]]
    save(out / 'manifest.json', {'scope': 'fixed-response framework regression; not a fresh LLM closed loop',
         'bundle': bundle['fingerprint'], 'sources': framework_hashes(ROOT),
         'responses': {str(p): digest(p) for p in responses}, 'new_llm_tokens': 0})
    config = json.loads((ROOT / 'experiments/designs/haven_eda.json').read_text())
    config['eda_env'] = {'shell': str(DEFAULT_EDA_SHELL)}
    sampling = SimpleNamespace(sequences_per_intent=4, sampling_seed=20260906,
                               sampling_time_limit='30s', eda_shell=DEFAULT_EDA_SHELL, resume=False)

    def generate(arm, rd, feedback, existing, ordinal):
        number = int(rd.name.split('-')[-1])
        response = parse_response(responses[number - 1].read_text())
        if args.reuse_solve and number == 1:
            original = args.reuse_solve.resolve() / 'rvprobe/round-1'
            sources = original / 'sources'
            from sequence_framework import check_saved_sources
            check_saved_sources(sources, design, response)
            result = json.loads((original / 'harness-report.json').read_text())
            log = f'Reused solve from {original}'
        else:
            sources = write_sources(rd / 'sources', design, response)
            result, log = harness(sources, rd / 'solve', DEFAULT_EDA_SHELL)
        save(rd / 'harness-report.json', result)
        (rd / 'harness.log').write_text(log)
        if not result['ok']:
            raise ValueError(f'UT harness failed: {result}')
        result['sources'] = str(sources)
        goals = expand_goals(result, Path(bundle['replay_config']), rd / 'sampling', sampling)
        sequences, frames = [], []
        for goal in goals:
            for index, row in enumerate(goal.get('sequences', [goal] if goal['status'] == 'generated' else [])):
                new = witness_frames(design, replay, row, ordinal + len(frames))
                label = f"rvp_{rd.name.replace('-', '_')}_{goal['label']}_{index}"
                sequences.append(render_witness_sequence(design, new, label, ordinal + len(frames)))
                frames += new
        return {'sequences': sequences, 'frames': frames, 'metadata': {'saved_response': str(responses[number - 1])}}

    started, began = utc(), time.monotonic()
    try:
        summary = paired_loop(bundle, out, HavenSimulation(bundle, design, config, 20260906), generate,
                              rounds=2, arms=('rvprobe',))
    except Exception as error:
        summary = {'status': 'failed', 'error': str(error)}
    summary.update(started_utc=started, finished_utc=utc(), elapsed_seconds=time.monotonic()-began,
                   new_llm_tokens=0, scope='fixed-response framework regression')
    save(out / 'summary.json', summary)
    save(out / 'progress.json', summary)


if __name__ == '__main__':
    main()
