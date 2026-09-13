"""Run saved failed goals through the isolated encoder and ORIGINAL native LTL.

No provider requests, Stage-1 mutation, or completed-pair relabeling. Every
encoding, timeout and native rejection is independently recorded.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

from frozen_stage1 import verify_stage1
from process_runner import run
from run_records import framework_hashes, save, utc

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for key in ('jobs', 'source-batch', 'out', 'local-work', 'yosys', 'haven-root'):
        p.add_argument('--'+key, type=Path, required=True)
    p.add_argument('--boundary', choices=('independent-dut-v1', 'frozen-source'),
                   default='independent-dut-v1')
    args = p.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    args.local_work.mkdir(parents=True, exist_ok=False)
    began = time.monotonic()
    record = dict(diagnostic_only=True, remote_llm_requests=0, remote_llm_tokens=0,
        status='running', started_utc=utc(), framework=framework_hashes(ROOT), jobs=[], boundary=args.boundary)
    def command(script, argv, log, timeout):
        with log.open('x') as stream:
            run([sys.executable, str(ROOT/'experiments'/script), *map(str,argv)],
                stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=timeout)
    for group in json.loads(args.jobs.read_text()):
        flow = args.source_batch/group['design']/'flow'
        fixed = json.loads((flow/'fixed-stage1.json').read_text())
        source = flow/'paired/rvprobe'/group['round']/'generation'/f'attempt-{group["attempt"]}'/'solve'
        for label in group['labels']:
            index = len(record['jobs'])
            directory = out/f'{index:02d}-{group["design"]}'
            directory.mkdir()
            local = args.local_work/f'{index:02d}-{group["design"]}'
            local.mkdir()
            started = time.monotonic()
            item = dict(design=group['design'], label=label, status='encoding',
                started_utc=utc(), source_solve=str(source), directory=str(directory),
                local_directory=str(local))
            record['jobs'].append(item)
            save(out/'progress.json', record)
            common = ['--source-solve', source, '--replay-config', flow/'manifest/replay.json',
                      '--label', label, '--boundary', args.boundary]
            try:
                verify_stage1(fixed)
                sampling = []
                for key in ('trace_cycles', 'trace_preference'):
                    if key in group:
                        sampling += ['--'+key.replace('_', '-'), group[key]]
                if group.get('noncontending_tristates'):
                    sampling += ['--noncontending-tristates']
                command('encoded_witness_probe.py', [*common, '--out', directory/'encoded',
                    '--yosys', args.yosys, '--eda-shell', ROOT/'experiments/eda-shell',
                    '--project', local/'jgproject', *sampling], directory/'encode.log', 1200)
                enc = json.loads((directory/'encoded/summary.json').read_text())
                item['encoded_status'] = enc['status']
                item['encoding_elapsed_seconds'] = enc['elapsed_seconds']
                if enc['status'] != 'covered':
                    item['status'] = 'no_candidate'
                else:
                    item['status'] = 'native_replay'
                    save(out/'progress.json', record)
                    command('replay_encoded_witness.py', [*common, '--encoded', directory/'encoded',
                        '--stage1', fixed['stage1'], '--haven-root', args.haven_root,
                        '--out', local/'native'], directory/'replay.log', 1800)
                    item['status'] = json.loads((local/'native/summary.json').read_text())['status']
            except Exception as error:
                item.update(status='failed', error=str(error))
            finally:
                if (local/'native/summary.json').exists():
                    native = json.loads((local/'native/summary.json').read_text())
                    save(directory/'native-summary.json', native)
                    item.update(native_status=native['status'], native_error=native.get('error'),
                        native_elapsed_seconds=native['elapsed_seconds'])
                try:
                    verify_stage1(fixed)
                except Exception as error:
                    item.update(status='failed', fixed_stage1_error=str(error))
                item.update(finished_utc=utc(), elapsed_seconds=time.monotonic()-started)
                save(out/'progress.json', record)
                print(json.dumps({k:v for k,v in item.items() if k in ('design','label','status','native_status','native_error','encoded_status')}), flush=True)
    record.update(status='passed' if all(j['status']=='passed' for j in record['jobs']) else 'finished_with_failures',
        finished_utc=utc(), elapsed_seconds=time.monotonic()-began)
    save(out/'summary.json', record)
    save(out/'progress.json', record)
    return int(record['status'] != 'passed')


if __name__ == '__main__':
    raise SystemExit(main())
