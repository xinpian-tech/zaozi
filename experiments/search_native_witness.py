"""Offline diagnostic: search a frozen cover for concretely replayable witnesses.

Never calls a model, edits the UT/RTL, or accepts partial goal success. Formal
covers with unconstrained initial state or floating buses need not be replayable.
Keep every rejected attempt and validate the exact original Cover on live IO.
"""
import argparse
import time
from pathlib import Path

from run_records import save, utc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('source-solve', 'replay-config', 'stage1', 'haven-root', 'out'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--pool-size', type=int, default=16)
    parser.add_argument('--target', type=int, default=4)
    parser.add_argument('--seed', type=int, default=20260906)
    parser.add_argument('--horizon-multiplier', type=int, choices=[1,2,4], default=1,
                        help='diagnostic bounded concretization horizon; never changes the original LTL')
    args = parser.parse_args()
    if not 1 <= args.target <= args.pool_size <= 256:
        parser.error('require 1 <= target <= pool-size <= 256')
    from coverage_flow import load_haven, prepare, HavenSimulation
    load_haven(args.haven_root)
    from offline_validation import no_model_calls
    from witness_sampling import frozen_inputs, sample_goal
    from cycle_replay import witness_frames
    from haven_shared import render_witness_sequence
    from ltl_replay import attach
    import json
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    start = time.monotonic()
    record = dict(diagnostic_only=True, remote_llm_requests=0, started_utc=utc(),
                  status='running', target=args.target, pool_size=args.pool_size,
                  source_solve=str(args.source_solve.resolve()), label=args.label,
                  horizon_multiplier=args.horizon_multiplier,
                  attempts=[], accepted=[])
    save(out/'progress.json', record)
    try:
        with no_model_calls():
            design, config, job, goals = frozen_inputs(args.source_solve, args.replay_config)
            goal = next(g for g in goals if g['label'] == args.label)
            bundle = prepare(args.stage1, args.haven_root, args.replay_config, out/'shared')
            eda = json.loads((Path(__file__).parent/'designs/haven_eda.json').read_text())
            shell = Path(__file__).resolve().parent/'eda-shell'
            eda['eda_env'] = {'shell':str(shell)}
            simulate = HavenSimulation(bundle, design, eda, args.seed)
            pool = sample_goal(job, goal, design, config, out/'sampling',
                               args.pool_size, args.seed, '30s', shell,
                               horizon=goal['cycles'] * args.horizon_multiplier)
            record['formal_candidates'] = len(pool)
            for index, candidate in enumerate(pool):
                rows = witness_frames(design, config, candidate, 0)
                attach(rows, goal)
                sequence = render_witness_sequence(design, rows, f'rvp_search_{index}', 0)
                attempt = dict(index=index, witness=candidate, status='running')
                record['attempts'].append(attempt)
                save(out/'progress.json', record)
                try:
                    result = simulate(out/f'sequence-{index}', [sequence], rows)
                except ValueError as error:
                    detail = getattr(error, 'diagnostics', {})
                    attempt.update(status='rejected', error=str(error), diagnostics=detail)
                    # Only a fully executed but unsatisfied original Cover is
                    # eligible for another solver witness. Never mask transport,
                    # compilation, timeout, storage, or unclassified failures.
                    if detail.get('kind') != 'formal_replay_semantics_mismatch':
                        raise
                else:
                    attempt.update(status='passed', coverage=result)
                    record['accepted'].append(index)
                save(out/'progress.json', record)
                if len(record['accepted']) == args.target:
                    break
            record['status'] = 'passed' if len(record['accepted']) == args.target else 'exhausted'
    except Exception as error:
        record.update(status='failed', error=str(error))
    record.update(finished_utc=utc(), elapsed_seconds=time.monotonic()-start)
    save(out/'summary.json', record)
    print(json.dumps({k:v for k,v in record.items() if k != 'attempts'}))
    return int(record['status'] != 'passed')


if __name__ == '__main__':
    raise SystemExit(main())
