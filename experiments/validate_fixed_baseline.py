"""Run the exact common Stage-1 baseline with model calls disabled."""
import argparse
import json
from pathlib import Path
import time

from frozen_stage1 import load_fixed_setup, verify_fixed_setup
from run_records import fresh_directory, save, utc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--record', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    setup, identity = load_fixed_setup(args.record)
    out = fresh_directory(args.out.resolve())
    stage = Path(setup['stage1']); haven = Path(setup['haven_snapshot'])
    root = Path(__file__).resolve().parent
    from coverage_flow import prepare, load_bundle, HavenSimulation
    from environment_preflight import write_replay_manifest
    from isolated_replay import IsolatedSimulation
    task = json.loads((stage/'ir/phase0_config.json').read_text())
    bp = json.loads((stage/'ir/phase2b_blueprint.json').read_text())
    result = dict(status='failed', diagnostic_only=True, model_calls=0,
                  started_utc=utc(), fixed_stage1=identity)
    began = time.monotonic()
    try:
        replay = write_replay_manifest(task,bp,out/'manifest',
                    (Path(task['root'])/task['spec']).read_text(),boundary='independent-dut-v1')
        prepare(stage,haven,replay,out/'shared')
        bundle, design, _ = load_bundle(out/'shared/bundle.json',haven)
        from offline_validation import no_model_calls
        config = json.loads((root/'designs/haven_eda.json').read_text())
        config.setdefault('eda_env', {})['shell'] = str(root/'eda-shell')
        config.setdefault('simulation', {})['model_repairs'] = False
        with no_model_calls():
            simulation = IsolatedSimulation(HavenSimulation(bundle,design,config,20260906),out/'replay-cache')
            coverage = simulation(out/'baseline',list(bundle['sequences']),[])
        result.update(status='completed',coverage=coverage,sequence_count=len(bundle['sequences']))
    except Exception as error:
        result['error'] = str(error)
        raise
    finally:
        try:
            verify_fixed_setup(identity)
        except Exception as error:
            result.update(status='failed',fixed_stage1_error=str(error))
        result.update(finished_utc=utc(),elapsed_seconds=time.monotonic()-began)
        save(out/'summary.json',result)
    if result['status'] != 'completed':
        raise ValueError('fixed baseline validation failed')
    print(json.dumps({k:result[k] for k in ('status','sequence_count','model_calls','elapsed_seconds')}))


if __name__ == '__main__':
    main()
