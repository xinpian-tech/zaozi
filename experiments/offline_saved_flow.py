"""Recompile one unchanged provider response through the current RVProbe flow.

Diagnostic only: permits a new shared adapter, never fabricates a provider reply,
rebuilds Stage-1, edits model LTL, or counts this as a completed paired benchmark.
The normal native witness selection and coverage merge remain enabled.
"""
import argparse
import json
from pathlib import Path
import sys
import time
from unittest.mock import patch

import coverage_flow as paired
from cycle_replay import digest
from frozen_stage1 import stage1_identity, verify_stage1
from manual_flow import ManualWorkers, worker_entry
from run_records import save, utc


def saved_response(directory):
    directory = Path(directory).resolve()
    summary = json.loads((directory/'summary.json').read_text())
    attempt = directory/f"attempt-{summary['attempts']}"
    provider = json.loads((attempt/'provider.json').read_text())
    if provider.get('response_status') != 'complete':
        raise ValueError('offline diagnostic requires a complete saved provider response')
    manifest = json.loads((directory/'manifest.json').read_text())
    if provider.get('requested_model') != manifest.get('model') or summary.get('model') != manifest.get('model'):
        raise ValueError('saved response model provenance differs')
    response = attempt/'response.txt'
    parsed = paired.generation.parse_response(response.read_text())
    if 'ltl' not in parsed:
        raise ValueError('saved response must contain the original LTL fragment')
    if parsed['ltl'] != (attempt/'sources/model.ltl').read_text():
        raise ValueError('saved model LTL differs from its provider response')
    return dict(response=str(response), response_sha256=digest(response),
                ut_sha256=digest(attempt/'sources/ModelUT.scala'), provider=provider,
                original_design=manifest['design'], prior_costs=summary.get('costs'),
                prior_elapsed_seconds=summary.get('elapsed_seconds'))


def verify_design(original, current):
    for key in ('version', 'top', 'ports', 'clock', 'reset', 'sequence', 'context', 'parameters'):
        if original.get(key) != current.get(key):
            raise ValueError('saved DUT identity differs: '+key)
    for key in ('sources', 'include_files'):
        if sorted(row['sha256'] for row in original[key]) != sorted(row['sha256'] for row in current[key]):
            raise ValueError('saved DUT content differs: '+key)
        for row in original[key]:
            if digest(Path(row['path'])) != row['sha256']:
                raise ValueError('historical DUT source changed: '+row['path'])


class SavedWorker:
    def __init__(self, provenance, dispatcher):
        self.provenance, self.dispatcher, self.count = provenance, dispatcher, 0

    def __call__(self, command, **kwargs):
        entry = worker_entry(command)
        if entry != paired.generation.main or self.count:
            raise ValueError('offline saved flow permits one generation worker and no repair')
        response = Path(self.provenance['response'])
        if digest(response) != self.provenance['response_sha256']:
            raise ValueError('saved provider response changed')
        self.count += 1
        return self.dispatcher([*command, '--response-file', str(response)], **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('generation', 'stage1', 'haven-root', 'out'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--jg-time-limit', default='120s')
    parser.add_argument('--encoded-witness-yosys', type=Path)
    args = parser.parse_args()
    provenance = saved_response(args.generation)
    fixed = stage1_identity(args.stage1, shared_environment=True)
    out = args.out.resolve(); out.mkdir(parents=True, exist_ok=False)
    began = time.monotonic()
    record = dict(status='running', diagnostic_only=True, formal_experiment=False,
                  remote_llm_requests=0, new_provider_tokens=0, started_utc=utc(),
                  provenance=provenance, fixed_stage1=fixed,
                  policy='unchanged-saved-provider-ut-current-framework-v1')
    save(out/'diagnostic.json', record)
    try:
        from haven_snapshot import snapshot
        haven = snapshot(args.haven_root, out/'implementation/haven')
        paired.load_haven(haven)
        from offline_validation import no_model_calls
        from environment_preflight import write_replay_manifest
        stage = args.stage1.resolve()
        task = json.loads((stage/'ir/phase0_config.json').read_text())
        blueprint = json.loads((stage/'ir/phase2b_blueprint.json').read_text())
        with no_model_calls():
            config = write_replay_manifest(task, blueprint, out/'manifest',
                (Path(task['root'])/task['spec']).read_text(), boundary='independent-dut-v1')
            paired.prepare(stage, haven, config, out/'shared')
            _, design, _ = paired.load_bundle(out/'shared/bundle.json', haven)
            # Wrapper paths may change when regenerating the trusted environment;
            # original RTL/spec/IO must still be the same design.
            verify_design(provenance['original_design'], design.record())
            save(out/'fixed-stage1.json', fixed)
            dispatcher = SavedWorker(provenance, ManualWorkers(out/'workers', haven, wait_seconds=0))
            root = Path(__file__).resolve().parent
            extra = ['--encoded-witness-yosys', str(args.encoded_witness_yosys)] if args.encoded_witness_yosys else []
            with patch.object(paired, 'run_process', dispatcher):
                code = paired.main(['run', '--bundle', str(out/'shared/bundle.json'),
                    '--haven-root', str(haven), '--out', str(out/'paired'), '--arm', 'rvprobe',
                    '--model', provenance['provider']['requested_model'],
                    '--fixed-stage1-identity', str(out/'fixed-stage1.json'),
                    '--rounds', '1', '--runtime-repairs', '0', '--attempts', '1',
                    '--request-retries', '1', '--sequences-per-intent', '4',
                    '--jg-time-limit', args.jg_time_limit, '--isolate-sequences',
                    '--eda-shell', str(root/'eda-shell'), '--eda-config', str(root/'designs/haven_eda.json'), *extra])
            record.update(status='passed' if code == 0 else 'failed', generation_workers=dispatcher.count)
    except Exception as error:
        record.update(status='failed', error=str(error))
    finally:
        try:
            verify_stage1(fixed)
            if digest(Path(provenance['response'])) != provenance['response_sha256']:
                raise ValueError('saved response changed during diagnostic')
        except Exception as error:
            record.update(status='failed', provenance_error=str(error))
        record.update(finished_utc=utc(), elapsed_seconds=time.monotonic()-began)
        save(out/'diagnostic.json', record)
    print(json.dumps({k:v for k,v in record.items() if k not in ('provenance','fixed_stage1')}))
    return int(record['status'] != 'passed')


if __name__ == '__main__':
    raise SystemExit(main())
