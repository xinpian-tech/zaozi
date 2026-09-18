"""Offline CLI adapter for the RVProbe known-state backend; no solver implementation."""
import argparse
import re
from pathlib import Path
from copy import deepcopy
import backend_imports
from rvprobe.backend.encoding import EncodingOptions, solve
from witness_sampling import frozen_inputs
from environment_contract import independent_environment, reset_sequence as frozen_reset_sequence
from environment_contract import formal_assumptions

def diagnostic_config(config, source_path, boundary):
    """Select a diagnostic boundary explicitly; never mutate the frozen job."""
    result = deepcopy(config)
    result['design'] = str((source_path.parent / config['design']).resolve())
    if boundary == 'independent-dut-v1':
        result['environment'] = independent_environment(config['environment'])
    elif boundary != 'frozen-source':
        raise ValueError('unsupported diagnostic boundary')
    return result


def main():
    parser = argparse.ArgumentParser()
    for key in ('source-solve', 'replay-config', 'out', 'yosys', 'eda-shell'):
        parser.add_argument('--'+key, type=Path, required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--project', type=Path)
    parser.add_argument('--jg-time-limit', default='120s')
    parser.add_argument('--engine-mode',choices=('auto','Mp','Ht','B','G2'),default='auto',
                        help='diagnostic engine selection; original LTL and native acceptance unchanged')
    parser.add_argument('--trace-cycles', type=int, help='diagnostic formal trace horizon, not native padding')
    parser.add_argument('--trace-preference', choices=('zero', 'ones'),
                        help='uniform soft input preference; cannot weaken the goal or environment')
    parser.add_argument('--trace-seed', type=int, help='bounded deterministic per-pin soft preferences')
    parser.add_argument('--avoid-stimulus',type=Path,action='append',default=[],
                        help='diagnostic hard diversity subset; prior imported input JSON, never changed LTL')
    parser.add_argument('--noncontending-tristates', action='store_true',
                        help='diagnostic subset only: automatically forbid simultaneous bus drivers')
    parser.add_argument('--boundary', choices=('independent-dut-v1', 'frozen-source'),
                        default='independent-dut-v1')
    args = parser.parse_args()
    if not re.fullmatch(r'[1-9][0-9]*s', args.jg_time_limit):
        parser.error('invalid JG time limit')
    if args.trace_cycles is not None and not 1 <= args.trace_cycles <= 10000:
        parser.error('trace horizon must be between 1 and 10000')
    if args.avoid_stimulus and (not args.trace_cycles or args.trace_seed is None):
        parser.error('distinct input sampling requires an explicit horizon and seed')
    design, config, job, goals = frozen_inputs(args.source_solve, args.replay_config)
    config = diagnostic_config(config, args.replay_config, args.boundary)
    if frozen_reset_sequence(design, config) != job['resetSequence']:
        raise ValueError('diagnostic boundary changed the frozen reset sequence')
    goal = next(g for g in goals if g['label'] == args.label)
    options = EncodingOptions(**{key: getattr(args, key) for key in EncodingOptions.__dataclass_fields__})
    solve(design, config, job, goal, formal_assumptions(design, config['environment']), options)


if __name__ == '__main__':
    main()
