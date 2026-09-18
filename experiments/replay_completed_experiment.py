"""Repeat a completed run's accepted stimuli; require identical coverage bin counts.

Zero model calls, unchanged LTL/IO schedules, fresh simulation processes and cache.
This is a reproducibility diagnostic, not another paid experiment or a claim
that independently sampled model outputs have identical performance.
"""
import argparse
import json
from pathlib import Path
import re
import time

import backend_imports
from coverage_flow import HavenSimulation, load_bundle
from cycle_replay import digest
from isolated_replay import IsolatedSimulation, independent_batches
from replay_saved_candidate import load_saved_simulation
from run_records import fingerprint, framework_hashes, save, utc

ROOT = Path(__file__).resolve().parents[1]


def check_artifacts(coverage):
    hashes = coverage.get('artifact_sha256')
    if not hashes or any(digest(Path(path)) != sha for path, sha in hashes.items()):
        raise ValueError('source accepted coverage artifacts changed or are missing')


def accepted_candidate(pair, summary):
    arm = summary['arms']['rvprobe']
    if summary['status'] != 'completed' or arm['status'] != 'completed':
        raise ValueError('reproducibility reference must be a completed run')
    sequences, frames, sources = [], [], {}
    for row in arm['rounds']:
        if not row['added_sequences']:
            continue
        number = row['round']
        matches = []
        for path in (pair/'rvprobe').glob(f'round-{number}*/candidate.json'):
            if not re.fullmatch(f'round-{number}(?:-repair-[0-9]+)?', path.parent.name):
                continue
            candidate = json.loads(path.read_text())
            if (len(candidate.get('sequences', [])) == row['added_sequences'] and
                    candidate.get('metadata', {}) == row.get('metadata', {})):
                matches.append((path, candidate))
        if len(matches) != 1:
            raise ValueError('missing or ambiguous accepted candidate for round '+str(number))
        path, candidate = matches[0]
        sequences.extend(candidate['sequences']); frames.extend(candidate['frames'])
        sources[str(path)] = digest(path)
    if not sequences:
        raise ValueError('baseline-only reference is not a stimulus reproducibility check')
    return dict(sequences=sequences, frames=frames), sources


def verify_batch_sources(bundle, design, candidate, reference):
    check_artifacts(reference)
    paths = [Path(p) for p in reference['artifact_sha256'] if Path(p).name == 'batches.json']
    if len(paths) != 1:
        raise ValueError('reference must use fresh-process-per-sequence coverage')
    saved = json.loads(paths[0].read_text())
    batches = independent_batches(bundle, design, bundle['sequences']+candidate['sequences'], candidate['frames'])
    if len(batches) != len(saved['databases']) or len(batches) != len(saved['runs']):
        raise ValueError('accepted candidate count differs from measured reference')
    for batch, database, result in zip(batches, saved['databases'], saved['runs']):
        check_artifacts(result)
        source = load_saved_simulation(Path(database).parent)
        if batch != (source['sequences'], source['frames']):
            raise ValueError('candidate IO, LTL schedule or sequence differs from measured reference')
    return len(batches)


def compare(reference, observed):
    if (fingerprint(reference['bins']) != fingerprint(observed['bins']) or
            reference['percent'] != observed['percent'] or reference['score'] != observed['score']):
        raise ValueError('identical accepted stimuli produced different coverage bins')
    for row in (reference, observed):
        if row.get('replay', {}).get('passed') is not True:
            raise ValueError('missing successful native replay evidence')
    expected = reference['replay'].get('native_ltl_sequences', 0)
    if expected <= 0 or observed['replay'].get('native_ltl_sequences') != expected:
        raise ValueError('native LTL sequence acceptance count changed or is absent')
    return dict(exact_bins=True, exact_percent=True, native_ltl_sequences=expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('pair', 'bundle', 'haven-root', 'out'):
        parser.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args()
    pair, out = args.pair.resolve(), args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    began = time.monotonic()
    record = dict(status='running', started_utc=utc(), diagnostic_only=True,
        new_llm_tokens=0, model_requests_allowed=False, source_pair=str(pair),
        framework=framework_hashes(ROOT))
    save(out/'progress.json', record)
    try:
        summary = json.loads((pair/'summary.json').read_text())
        manifest = json.loads((pair/'manifest.json').read_text())
        bundle, design, _ = load_bundle(args.bundle, args.haven_root)
        candidate, sources = accepted_candidate(pair, summary)
        reference = summary['arms']['rvprobe']['final']
        record['source_sequences'] = verify_batch_sources(bundle, design, candidate, reference)
        record.update(source_candidates=sources, source_summary_sha256=digest(pair/'summary.json'),
                      seed=manifest['seed'], reference=reference)
        save(out/'candidate.json', candidate)
        save(out/'progress.json', record)
        config = json.loads((ROOT/'experiments/designs/haven_eda.json').read_text())
        config['eda_env'] = {'shell':str(ROOT/'experiments/eda-shell')}
        from offline_validation import no_model_calls
        with no_model_calls():
            simulation = HavenSimulation(bundle, design, config, manifest['seed'])
            result = IsolatedSimulation(simulation, out/'replay-cache')(
                out/'coverage', bundle['sequences']+candidate['sequences'], candidate['frames'])
        record['comparison'] = compare(reference, result)
        if (digest(pair/'summary.json') != record['source_summary_sha256'] or
                any(digest(Path(p)) != sha for p, sha in sources.items())):
            raise ValueError('source run changed during reproducibility check')
        record.update(status='passed', coverage=result)
    except Exception as error:
        record.update(status='failed', error=str(error))
    record.update(finished_utc=utc(), elapsed_seconds=time.monotonic()-began)
    save(out/'summary.json', record); save(out/'progress.json', record)
    return int(record['status'] != 'passed')


if __name__ == '__main__': raise SystemExit(main())
