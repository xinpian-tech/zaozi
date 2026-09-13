#!/usr/bin/env python3
"""Run the first four shared-bench pairs, reusing completed Stage-1 artifacts."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

from run_records import save, utc
from frozen_stage1 import load_fixed_setup, verify_fixed_setup

ROOT = Path(__file__).resolve().parent.parent
# Transport metadata only: no generated stimulus, predicates or formal assumptions.
REQUESTS = {'alu': {'start': 1}, 'aes': {'init': 1, 'next': 1},
            'sha3': {'in_ready': 1, 'is_last': 1},
            'axil_ram': {'s_axil_awvalid': 1, 's_axil_wvalid': 1, 's_axil_arvalid': 1}}


def manifests(stage, out, name):
    config = json.loads((stage / 'ir/phase0_config.json').read_text())
    base = Path(config['root'])
    sources = [str((base / p).resolve()) for p in config['rtl_files']]
    top = config['module_name']
    ast = out / 'ports.tree.json'
    with (out / 'port-elaboration.log').open('w') as log:
        subprocess.run(['verilator', '--json-only', '-Wno-fatal', '--top-module', top,
                        '--json-only-output', str(ast), '--Mdir', str(out / 'obj_dir'),
                        '-I' + str(base / 'rtl'), *sources],
                       stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
    tree = json.loads(ast.read_text())
    def nodes(value):
        if isinstance(value, dict):
            yield value
            for child in value.values():
                yield from nodes(child)
        elif isinstance(value, list):
            for child in value:
                yield from nodes(child)
    types = {x['addr']: x for x in nodes(tree) if x.get('type') == 'BASICDTYPE'}
    module = next(x for x in tree['modulesp'] if x.get('name') == top)
    clock, reset = config['clock']['port'], config['reset']['name']
    ports = []
    for var in module['stmtsp']:
        if not var.get('isPrimaryIO'):
            continue
        dtype = types.get(var['dtypep'])
        if dtype is None:
            raise ValueError('Unsupported non-scalar/packed IO type')
        left, right = map(int, dtype.get('range', '0:0').split(':'))
        width = abs(left - right) + 1
        port = var['name']
        ports.append({'name': port, 'direction': var['direction'].lower(), 'width': width,
                      'kind': 'clock' if port == clock else 'bool' if width == 1 else 'bits'})
    spec = base / config['spec']
    save(out / 'design.json', {'version': 1, 'top': top, 'sources': sources,
         'include_dirs': [str(base / 'rtl')], 'ports': ports, 'clock': clock,
         'reset': {'port': reset, 'active_low': config['reset']['level'] == 'low'},
         'sequence': {'name': 'rvprobe_llm_seq', 'item_type': top + '_seq_item'},
         'context': spec.read_text()})
    save(out / 'replay.json', {'version': 1, 'contract': 'cycle-replay-v1',
         'design': 'design.json', 'reset_cycles': 2, 'drain_cycles': 16,
         'idle': {p['name']: 0 for p in ports if p['direction'] == 'input'
                  and p['name'] not in (clock, reset)}, 'request': REQUESTS[name],
         'baseline': {'mode': 'reset-only', 'idle_cycles': 1}})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ('haven-root', 'stage1-root', 'out', 'env-file'):
        parser.add_argument('--' + key, type=Path, required=True)
    args = parser.parse_args()
    fixed = {name:load_fixed_setup(args.stage1_root/name/'stage1-costs.json') for name in REQUESTS}
    args.out.mkdir(parents=True, exist_ok=False)
    summary = {'status': 'running', 'started_utc': utc(),
               'shared_setup_mode':'frozen','stage1_model_calls':0,
               'fixed_stage1':{name:value[1] for name,value in fixed.items()},
               'designs': {name: {'status': 'queued'} for name in REQUESTS}}
    began = time.monotonic()
    common = ['--haven-root', str(args.haven_root), '--env-file', str(args.env_file),
              '--eda-shell', str(ROOT / 'experiments/eda-shell')]

    def command(argv, log):
        with log.open('x') as stream:
            subprocess.run([sys.executable, *map(str, argv)], cwd=ROOT, stdout=stream,
                           stderr=subprocess.STDOUT, check=True)

    for name, record in summary['designs'].items():
        folder = args.out / name
        folder.mkdir()
        record.update(status='preparing', started_utc=utc())
        save(args.out / 'progress.json', summary)
        try:
            stage1, identity = fixed[name]
            verify_fixed_setup(identity)
            record['stage1'] = stage1
            if stage1['status'] != 'stage1_ready':
                raise ValueError('Stage-1 failed; inspect its cost record')
            stage = Path(stage1['stage1'])
            manifests(stage, folder, name)
            command([ROOT / 'experiments/coverage_flow.py', 'prepare', '--stage1-run', stage,
                     '--haven-root', args.haven_root, '--replay-config', folder / 'replay.json',
                     '--out', folder / 'shared'], folder / 'prepare.log')
            record['status'] = 'running_pair'
            save(args.out / 'progress.json', summary)
            command([ROOT / 'experiments/coverage_flow.py', 'run', *common,
                     '--bundle', folder / 'shared/bundle.json', '--arm', 'both',
                     '--eda-config', ROOT / 'experiments/designs/haven_eda.json',
                     '--model', 'deepseek-v4-flash-vision-exp', '--rounds', '3',
                     '--seed', '20260906', '--sequences-per-intent', '4',
                     '--out', folder / 'paired'], folder / 'paired.log')
            result = json.loads((folder / 'paired/summary.json').read_text())
            record.update(status=result['status'], paired_summary=str(folder / 'paired/summary.json'))
        except Exception as error:
            record.update(status='failed', error=str(error))
        try:
            verify_fixed_setup(fixed[name][1])
        except Exception as error:
            record.update(status='failed',fixed_stage1_error=str(error))
        record['finished_utc'] = utc()
        save(args.out / 'progress.json', summary)
    summary.update(status='completed' if all(r['status'] == 'completed' for r in summary['designs'].values())
                   else 'finished_with_failures', finished_utc=utc(), elapsed_seconds=time.monotonic()-began)
    save(args.out / 'progress.json', summary)
    save(args.out / 'summary.json', summary)


if __name__ == '__main__':
    main()
