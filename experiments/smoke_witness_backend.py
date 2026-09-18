"""Exercise the production backend adapter on saved goals, with no LLM calls.

Starts from the original two-state candidate, including native rejection and
known-state fallback when needed. Does not relabel the historical experiment.
"""
import argparse
import json
import re
from pathlib import Path
import time
from types import SimpleNamespace

from coverage_flow import load_haven, prepare, HavenSimulation
from frozen_stage1 import verify_stage1
from isolated_replay import IsolatedSimulation
from offline_validation import no_model_calls
from run_records import save, utc, framework_hashes
from witness_sampling import frozen_inputs
from witness_backend_adapter import generate
from cycle_replay import load_config
from sequence_framework import check_saved_sources, parse_response
import sequence_experiment as generation


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('source-batch','haven-root','yosys','out'):
        parser.add_argument('--'+name,type=Path,required=True)
    for name in ('design','round'):
        parser.add_argument('--'+name,required=True)
    parser.add_argument('--label',help='omit to replay the complete saved intent batch')
    parser.add_argument('--attempt',type=int,default=1)
    parser.add_argument('--rebuild-saved-response',action='store_true',
                        help='re-enter the full source assembly, sandbox compiler and solver using the saved LTL')
    parser.add_argument('--sequences-per-intent',type=int,default=1)
    parser.add_argument('--encoded-witness-time-limit',default='120s',
                        help='explicit auxiliary solve budget; recorded separately from historical results')
    parser.add_argument('--measure-coverage',action='store_true',
                        help='measure frozen baseline and union of only native-accepted sequences')
    args = parser.parse_args()
    if not re.fullmatch(r'[1-9][0-9]*s', args.encoded_witness_time_limit):
        parser.error('invalid encoded witness time limit')
    out = args.out.resolve()
    out.mkdir(parents=True,exist_ok=False)
    began = time.monotonic()
    root = Path(__file__).resolve().parents[1]
    record = dict(status='running',diagnostic_only=True,remote_llm_requests=0,
        remote_llm_tokens=0,started_utc=utc(),framework=framework_hashes(root),
        encoded_witness_time_limit=args.encoded_witness_time_limit)
    flow = args.source_batch/args.design/'flow'
    fixed = json.loads((flow/'fixed-stage1.json').read_text())
    try:
        with no_model_calls():
            verify_stage1(fixed)
            load_haven(args.haven_root)
            source = flow/'paired/rvprobe'/args.round/'generation'/f'attempt-{args.attempt}'/'solve'
            replay_path = flow/'manifest/replay.json'
            if args.rebuild_saved_response:
                saved = source.parent
                design, _ = load_config(replay_path)
                reply = saved/'response.txt'
                provider = json.loads((saved/'provider.json').read_text())
                if provider.get('response_status') != 'complete':
                    raise ValueError('offline rebuild requires a complete saved provider response')
                check_saved_sources(saved/'sources',design,parse_response(reply.read_text()))
                rd = source.parents[2]
                feedback = json.loads((rd/'feedback.json').read_text())
                record.update(saved_response=str(reply),historical_costs_reused=False,
                              rebuilt_source=True,requested_cap=args.sequences_per_intent)
                code = generation.main(['--design',str(replay_path.parent/'design.json'),
                    '--replay-config',str(replay_path),'--response-file',str(reply),
                    '--modinfo',feedback['modinfo'],'--feedback-file',str(rd/'feedback.json'),
                    '--history-file',str(rd/'accepted-history.json'),
                    '--skill-snapshot',str(rd.parent/'frozen-skill.json'),
                    '--out',str(out/'generation'),'--sequences-per-intent',str(args.sequences_per_intent),
                    '--jg-time-limit','120s'])
                compiled = json.loads((out/'generation/summary.json').read_text())
                if code:
                    raise ValueError('saved-response generation failed: '+str(compiled.get('last_error',compiled.get('error'))))
                source = Path(compiled['sources']).parent/'solve'
            design, replay, _, goals = frozen_inputs(source,replay_path)
            goals = [goal for goal in goals if goal['label']==args.label] if args.label else goals
            if not goals:
                raise ValueError('requested saved goal is absent')
            bundle = prepare(Path(fixed['stage1']),args.haven_root,replay_path,out/'shared')
            config = json.loads((root/'experiments/designs/haven_eda.json').read_text())
            config['eda_env'] = {'shell':str(root/'experiments/eda-shell')}
            simulator = IsolatedSimulation(HavenSimulation(bundle,design,config,20260906),out/'replay-cache')
            options = SimpleNamespace(sequences_per_intent=args.sequences_per_intent,sampling_seed=20260906,
                sampling_time_limit='30s',eda_shell=root/'experiments/eda-shell',
                encoded_witness_yosys=args.yosys,resume=False,
                encoded_witness_time_limit=args.encoded_witness_time_limit)
            produced = generate({'sources':str(source.parent/'sources'),'result':{'goals':goals}},
                replay_path,design,replay,simulator,out/'round-offline',options,0)
            save(out/'produced.json',produced)
            record.update(status=(produced['metadata']['status'] if produced['sequences'] else 'no_witness'),
                          backend=produced['metadata'],sequences=len(produced['sequences']),
                          all_intents_satisfied=produced['metadata']['all_intents_satisfied'])
            if args.measure_coverage:
                baseline=simulator(out/'baseline',bundle['sequences'],[])
                measured=simulator(out/'coverage',bundle['sequences']+produced['sequences'],produced['frames'])
                record.update(baseline=baseline,coverage=measured)
    except Exception as error:
        record.update(status='failed',error=str(error),diagnostics=getattr(error,'diagnostics',{}))
    finally:
        try:
            verify_stage1(fixed)
        except Exception as error:
            record.update(status='failed',fixed_stage1_error=str(error))
        record.update(elapsed_seconds=time.monotonic()-began,finished_utc=utc())
        save(out/'summary.json',record)
    print(json.dumps({key:record[key] for key in ('status','remote_llm_requests','elapsed_seconds')}))
    return int(record['status'] not in ('passed','completed_with_shortfalls'))


if __name__ == '__main__':
    raise SystemExit(main())
