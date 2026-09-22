#!/usr/bin/env python3
"""Run sequences against one shared environment with concrete response checking."""
import backend_imports
import argparse
import json
import re
from pathlib import Path
import sys
import time
import rvprobe_model_options

from environment_preflight import write_replay_manifest
from rvprobe.backend.process import run
from run_records import save, utc
from haven_snapshot import snapshot
from environment_policy import derive_policy, require_supported
from frozen_stage1 import stage1_identity, verify_stage1

ROOT = Path(__file__).resolve().parent.parent


def validate_scope(blueprint):
    policy = derive_policy(blueprint)
    require_supported(policy)
    return policy


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('stage1-run', 'haven-root', 'env-file', 'out'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--eda-shell', type=Path, default=ROOT/'experiments/eda-shell')
    parser.add_argument('--arm', choices=('both','haven','rvprobe'), default='both')
    parser.add_argument('--model',default='deepseek-v4-flash-vision-exp')
    parser.add_argument('--timeout',type=int,default=600)
    parser.add_argument('--haven-max-tokens',type=rvprobe_model_options.token_limit)
    parser.add_argument('--haven-reasoning-effort',choices=('low','high','max'))
    parser.add_argument('--continue-generation',type=Path)
    parser.add_argument('--encoded-witness-yosys',type=Path)
    parser.add_argument('--encoded-witness-time-limit',default='120s')
    parser.add_argument('--shared-stage1-environment',action='store_true')
    parser.add_argument('--boundary',choices=['independent-dut-v1'],default='independent-dut-v1')
    parser.add_argument('--rounds',type=int,default=3,
                        help='number of RVProbe coverage rounds (HAVEN remains unchanged unless selected)')
    parser.add_argument('--sequences-per-intent',type=int,choices=range(1,9),default=4,
                        help='RVProbe witness target per Gen; does not alter HAVEN DSL generation')
    rvprobe_model_options.add_options(parser)
    args = parser.parse_args()
    if args.rounds < 1:
        parser.error('rounds must be positive')
    if args.arm=='haven' and args.sequences_per_intent!=4:
        parser.error('witness-count experiments are RVProbe-only; HAVEN remains unchanged')
    if not re.fullmatch(r'[1-9][0-9]*s', args.encoded_witness_time_limit):
        parser.error('invalid encoded witness time limit')
    stage = args.stage1_run.resolve()
    fixed = stage1_identity(stage,args.shared_stage1_environment)
    ir = stage/'ir'
    task = json.loads((ir/'phase0_config.json').read_text())
    bp = json.loads((ir/'phase2b_blueprint.json').read_text())
    if not json.loads((ir/'phase5_compile_check_result.json').read_text()).get('compile_passed'):
        raise ValueError('shared Stage-1 did not pass compilation')
    args.out = args.out.resolve()
    args.out.mkdir(parents=True,exist_ok=False)
    record = {'status':'preparing','started_utc':utc(),'scope':'shared-environment-conformance-v1','arm':args.arm,
              'stage1_run':str(stage),'shared_setup_cost_record':str(stage.parents[1]/'stage1-costs.json'),
              'model':rvprobe_model_options.model_for(args,args.arm),
              'models':{arm:rvprobe_model_options.model_for(args,arm) for arm in
                        (('haven','rvprobe') if args.arm=='both' else (args.arm,))},
              'rounds':args.rounds,'sequences_per_intent':args.sequences_per_intent,
              'rvprobe_intent_batch_limit':args.rvprobe_intent_batch_limit}
    record.update(shared_setup_mode='frozen',stage1_model_calls=0,fixed_stage1=fixed)
    if args.arm == 'haven':
        record['paired_status'] = 'incomplete_haven_arm_only'
    began = time.monotonic()
    save(args.out/'progress.json',record)
    save(args.out/'fixed-stage1.json',fixed)
    def command(argv, name):
        with (args.out/name).open('x') as log:
            result = run([sys.executable,str(ROOT/'experiments/coverage_flow.py'),*map(str,argv)],
                         cwd=ROOT,stdout=log,stderr=-2,timeout=12*3600)
        if result.returncode:
            raise ValueError(f'{name} failed with exit code {result.returncode}; inspect saved log')
    try:
        record['environment_policy'] = derive_policy(bp,args.boundary)
        record['boundary'] = args.boundary or 'shared-environment-conformance-v1'
        save(args.out/'progress.json',record)
        if args.arm != 'haven':
            require_supported(record['environment_policy'])
        args.haven_root = snapshot(args.haven_root,args.out/'implementation/haven')
        record['haven_snapshot'] = str(args.haven_root)
        replay = write_replay_manifest(task,bp,args.out/'manifest',
                                      (Path(task['root'])/task['spec']).read_text(),boundary=args.boundary)
        command(['prepare','--stage1-run',stage,'--haven-root',args.haven_root,
                 '--replay-config',replay,'--out',args.out/'shared'],'prepare.log')
        verify_stage1(fixed)
        record['status'] = 'running_'+args.arm
        save(args.out/'progress.json',record)
        extra = ['--continue-generation',args.continue_generation] if args.continue_generation else []
        extra += rvprobe_model_options.cli(args)
        extra += ['--timeout',str(args.timeout)]
        if args.haven_max_tokens is not None:
            extra += ['--haven-max-tokens',str(args.haven_max_tokens)]
        if args.haven_reasoning_effort is not None:
            extra += ['--haven-reasoning-effort',args.haven_reasoning_effort]
        if args.encoded_witness_yosys:
            extra += ['--encoded-witness-yosys',args.encoded_witness_yosys,
                      '--encoded-witness-time-limit',args.encoded_witness_time_limit]
        if args.shared_stage1_environment:
            extra += ['--fixed-stage1-identity',args.out/'fixed-stage1.json']
        command(['run','--bundle',args.out/'shared/bundle.json','--haven-root',args.haven_root,
                 '--env-file',args.env_file,'--eda-shell',args.eda_shell,
                 '--eda-config',ROOT/'experiments/designs/haven_eda.json','--arm',args.arm,
                 '--model',record['model'],'--rounds',str(args.rounds),'--seed','20260906',
                 '--sequences-per-intent',str(args.sequences_per_intent),'--isolate-sequences',
                 '--out',args.out/'paired',*extra],'paired.log')
        result = json.loads((args.out/'paired/summary.json').read_text())
        record.update(status=result['status'],summary=str(args.out/'paired/summary.json'))
    except Exception as error:
        record.update(status='failed',error=str(error))
        raise
    finally:
        try:
            verify_stage1(fixed)
        except Exception as error:
            record.update(status='failed',fixed_stage1_error=str(error))
        record.update(finished_utc=utc(),elapsed_seconds=time.monotonic()-began)
        save(args.out/'progress.json',record)
    if record['status']=='failed':
        raise ValueError(record.get('fixed_stage1_error',record.get('error','experiment failed')))


if __name__ == '__main__':
    main()
