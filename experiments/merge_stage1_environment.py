"""Package existing fixed Stage-1s verbatim; no model, generation, or repair."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys

from design_inventory import DESIGNS
from frozen_stage1 import load_fixed_setup, verify_fixed_setup
from haven_snapshot import snapshot
from run_records import fresh_directory, save, utc


def check_contract(record, out):
    # Separate imports per design: each record may reference a distinct snapshot.
    code = ('import json,sys; from pathlib import Path; '
            'from frozen_stage1 import load_fixed_setup; '
            'from environment_preflight import write_replay_manifest; '
            'from coverage_flow import prepare; '
            'r,o=map(Path,sys.argv[1:]); s,_=load_fixed_setup(r); '
            'stage=Path(s["stage1"]); '
            't=json.loads((stage/"ir/phase0_config.json").read_text()); '
            'b=json.loads((stage/"ir/phase2b_blueprint.json").read_text()); '
            'm=write_replay_manifest(t,b,o/"manifest",(Path(t["root"])/t["spec"]).read_text(),'
            'boundary="independent-dut-v1"); '
            'prepare(stage,Path(s["haven_snapshot"]),m,o/"shared")')
    out.mkdir(parents=True, exist_ok=False)
    with (out/'prepare.log').open('x') as log:
        result = subprocess.run([sys.executable, '-c', code, str(record), str(out)],
                                cwd=Path(__file__).parent, stdout=log, stderr=-2, timeout=180)
    if result.returncode:
        raise ValueError('fixed environment fails current Stage-2 contract: ' + str(out/'prepare.log'))


def merge_maps(paths):
    merged = {}
    for path in paths:
        path = Path(path).resolve()
        for name, value in json.loads(path.read_text()).items():
            record = Path(value)
            merged[name] = str(record.resolve() if record.is_absolute()
                               else (path.parent / record).resolve())
    return merged


def package(paths, out, designs=DESIGNS):
    sources = merge_maps(paths)
    missing = set(designs) - sources.keys()
    if missing:
        raise ValueError('missing fixed Stage-1: ' + ', '.join(sorted(missing)))
    fixed = {name: load_fixed_setup(sources[name]) for name in designs}
    out = Path(out).resolve()
    fresh_directory(out)
    implementations, mapping, identities = {}, {}, {}
    for name, (setup, identity) in fixed.items():
        verify_fixed_setup(identity)
        source = Path(setup['stage1']).resolve()
        stage = out / name / 'stage1'
        for directory in ('ir', 'final'):
            shutil.copytree(source / directory, stage / directory)
        if (source / 'manual-diagnostic.json').exists():
            shutil.copy2(source / 'manual-diagnostic.json', stage / 'manual-diagnostic.json')
        haven = str(Path(setup['haven_snapshot']).resolve())
        if haven not in implementations:
            implementations[haven] = snapshot(haven, out / 'implementations' / str(len(implementations)))
        record = out / name / 'stage1-costs.json'
        save(out / name / 'source-record.json', setup)
        save(record, {**setup, 'stage1': str(stage),
                      'haven_snapshot': str(implementations[haven]),
                      'fixed_stage1': None, 'original_record': identity['record'],
                      'packaged_utc': utc(), 'stage1_model_calls': 0})
        _, packaged = load_fixed_setup(record)
        # Rebased stage paths are the only difference; every artifact must match.
        expected = {str(stage / Path(p).relative_to(source)) if Path(p).is_relative_to(source)
                    else p: sha for p, sha in identity['sha256'].items()}
        if packaged['sha256'] != expected or packaged['haven_sha256'] != identity['haven_sha256']:
            raise ValueError('environment package changed source bytes: ' + name)
        verify_fixed_setup(identity)
        mapping[name] = str(record)
        identities[name] = {'source': identity, 'packaged': packaged}
        check_contract(record, out / name / 'preflight')
    save(out / 'manifest.json', dict(status='completed', created_utc=utc(),
         role='fixed-stage1-experiment-environment', stage1_model_calls=0,
         policy='Shared verbatim by both Stage-2 arms; source maps applied in order, last wins',
         external_dependencies='RTL/spec paths remain unchanged and are SHA256-checked in each identity',
         source_maps=[str(Path(p).resolve()) for p in paths], designs=identities))
    # Publish the usable entry point only after every design passed validation.
    save(out / 'stage1-map.json', mapping)
    return mapping


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-maps', nargs='+', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    mapping = package(args.source_maps, args.out)
    print(json.dumps({'designs': len(mapping), 'stage1_map': str(args.out / 'stage1-map.json')}))


if __name__ == '__main__':
    main()
