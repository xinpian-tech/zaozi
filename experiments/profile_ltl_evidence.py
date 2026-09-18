"""Compare saved first-attempt evidence to the current encoding, without model calls.

Exact characters only, not a prediction of provider tokens or model behavior.
Historical artifacts are read-only. Full spec and RTL text must remain intact.
"""
import argparse
import hashlib
import json
from pathlib import Path

from coverage_table import unpack
from ltl_source import parse, unwrap
from run_records import save
from sequence_framework import Design, Port
from task_context import TaskContext


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))


def profile(generation):
    generation = Path(generation)
    manifest = json.loads((generation/'manifest.json').read_text())
    record = manifest['design']
    for source in record['sources']:
        if hashlib.sha256(Path(source['path']).read_bytes()).hexdigest() != source['sha256']:
            raise ValueError('saved RTL changed')
    design = Design(record['top'], tuple(Path(p['path']) for p in record['sources']),
        tuple(Path(p) for p in record['include_dirs']), tuple(Port(**p) for p in record['ports']),
        record['clock'], record['reset']['port'], record['reset']['active_low'],
        record['sequence']['name'], record['sequence']['item_type'], record['context'],
        tuple(record['parameters'].items()))
    history = json.loads((generation.parent/'accepted-history.json').read_text())
    task = TaskContext(design, manifest['feedback'], history)
    old = json.loads((generation/'attempt-1/initial-evidence.json').read_text())
    new = task.initial_evidence()
    # Compare representations, never select or summarize gaps.
    if 'current_feedback' in new:
        full = new['current_feedback']['coverage']
        restored = {**full, **({'gaps':unpack(full['gaps'])} if 'gaps' in full else {})}
        if restored != json.loads(task.topics['coverage']):
            raise ValueError('coverage reconstruction differs')
    for key in ('previously_read_rtl','accepted_ltl','environment','baseline','rtl'):
        if old.get(key) != new.get(key):
            raise ValueError(f'non-coverage evidence changed: {key}')
    before, after = len(compact(old)), len(compact(new))
    response = (generation/'attempt-1/response.txt').read_bytes().decode('utf-8')
    try:
        parsed = parse(response)
        _, audit = unwrap(response)
        envelope = {'accepted_by_current_parser':True, **audit, 'goals':len(parsed.get('labels',[]))}
    except ValueError as error:
        envelope = {'accepted_by_current_parser':False, 'error':str(error)}
    return {'round':generation.parent.name, 'source':str(generation),
        'old_initial_evidence_characters':before, 'new_initial_evidence_characters':after,
        'reduction_percent':100*(before-after)/before,
        'coverage_reconstruction_checked':'current_feedback' in new, 'non_coverage_evidence_unchanged':True,
        'spec_characters_preserved':len(design.context), 'first_response':envelope}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--generation', type=Path, nargs='+', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists() or any(args.out.resolve().is_relative_to(p.resolve().parent) for p in args.generation):
        parser.error('output must be new and outside the historical round directories')
    report = {'scope':'read-only first-attempt evidence/response audit; no model, compiler or EDA calls',
              'new_model_requests':0, 'rows':[profile(p) for p in args.generation],
              'caveat':'Characters are not tokens; no claim of causal savings or hypothetical coverage.'}
    save(args.out, report)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
