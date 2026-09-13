"""Replay unchanged saved candidate sequences without generating or repairing stimuli.

Diagnostic only: this is not a fresh paired closed-loop experiment.
"""
import argparse
import json
from pathlib import Path
import time

from coverage_flow import HavenSimulation, load_bundle
from cycle_replay import digest
from isolated_replay import IsolatedSimulation, independent_batches, POLICY
from run_records import fingerprint, framework_hashes, save, utc
from sequence_framework import ROOT


def load_saved_simulation(directory):
    """Recover immutable source sequences and rows from an interrupted replay."""
    directory = Path(directory)
    recorded = json.loads((directory/'inputs.json').read_text())
    hashes = recorded.get('sequences')
    if not isinstance(hashes, list) or not hashes:
        raise ValueError('saved simulation has no sequence identities')
    sequences = []
    for index, expected in enumerate(hashes, 1):
        source = directory/f'sequence_{index}.sv'
        code = source.read_text()
        if fingerprint(code) != expected:
            raise ValueError('saved simulation sequence hash differs')
        sequences.append(code)
    frames = json.loads((directory/'schedule.json').read_text())
    if not isinstance(frames, list):
        raise ValueError('saved simulation schedule must be an array')
    return {'sequences':sequences, 'frames':frames}


def normalize_saved_ordinals(candidate, design, *, already_isolated=False):
    """Validate a later round's global numbering, then rebase diagnostic labels.

    Only generated item names/ordinals change. Inputs, timing, checks, and LTL
    remain identical. The normal paired-loop validator still starts at zero.
    """
    from collections import OrderedDict
    from copy import deepcopy
    import re
    from haven_shared import render_witness_sequence
    if already_isolated:
        # A saved simulation has already rebased its local item ordinals even
        # though frame segment IDs still identify the original experiment.
        independent_batches({'sequences':[]}, design, candidate['sequences'], candidate['frames'])
        return candidate, 0
    frames=candidate['frames']
    start=frames[0]['segment'] if frames else 0
    if type(start) is not int or start < 0:
        raise ValueError('saved candidate has invalid initial witness ordinal')
    if not start: return candidate,0
    groups=OrderedDict()
    for row in frames:
        if row['segment'] in groups and row['segment']!=next(reversed(groups)):
            raise ValueError('non-contiguous saved witness segment')
        groups.setdefault(row['segment'],[]).append(row)
    pending=iter(groups.values()); ordinal=start
    fixed=deepcopy(candidate)
    for index,source in enumerate(candidate['sequences']):
        if not re.search(r'\brvp_raw\s*=\s*1\s*;',source): continue
        rows=next(pending,None)
        names=re.findall(r'\bclass\s+(\w+)\s+extends\b',source)
        if rows is None or len(names)!=1 or source!=render_witness_sequence(design,rows,names[0],ordinal):
            raise ValueError('saved raw sequence differs beyond its recorded global ordinal')
        fixed['sequences'][index]=render_witness_sequence(design,rows,names[0],ordinal-start)
        ordinal+=len(rows)
    if next(pending,None) is not None:
        raise ValueError('saved witness frames have no sequence')
    return fixed,start


def refresh_ltl_provenance(candidate, generation):
    """Rebind only the known class/emitted-top metadata bug, never LTL or IO."""
    from copy import deepcopy
    from ltl_replay import attach
    summary=json.loads((generation/'summary.json').read_text())
    goals={g['generationLabel']:g for g in summary['result']['goals']}
    fixed=deepcopy(candidate)
    count=0
    for row in fixed['frames']:
        if 'ltl' not in row: continue
        old=row['ltl']; goal=goals[old['label']]
        rebuilt=[{}]; attach(rebuilt,goal)
        new=rebuilt[0]['ltl']
        if ({k:v for k,v in old.items() if k!='top'} != {k:v for k,v in new.items() if k!='top'}
                or old['top'] not in (goal['utModule'],new['top'])):
            raise ValueError('saved candidate LTL provenance differs beyond emitted module name')
        row['ltl']=new
        count += old['top'] != new['top']
    return fixed,count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('bundle', 'haven-root', 'out', 'eda-config', 'eda-shell'):
        parser.add_argument('--'+name, type=Path, required=True)
    sources = parser.add_mutually_exclusive_group(required=True)
    sources.add_argument('--candidate', type=Path)
    sources.add_argument('--simulation-directory', type=Path,
                         help='replay recorded source sequences and schedule after an adapter repair')
    parser.add_argument('--sequence', type=int, help='one-based candidate index; omit to replay baseline plus all candidates')
    parser.add_argument('--seed', type=int, default=20260906)
    parser.add_argument('--generation',type=Path,
                        help='verify original solver artifacts and repair only stale lowered-module metadata')
    args = parser.parse_args()
    bundle, design, _ = load_bundle(args.bundle, args.haven_root)
    source = args.candidate or args.simulation_directory/'inputs.json'
    candidate = (json.loads(args.candidate.read_text()) if args.candidate else
                 load_saved_simulation(args.simulation_directory))
    rebound=0
    if args.generation:
        candidate,rebound=refresh_ltl_provenance(candidate,args.generation)
    candidate,initial_ordinal=normalize_saved_ordinals(candidate,design,
        already_isolated=args.simulation_directory is not None)
    sequences = bundle['sequences'] + candidate['sequences']
    frames = candidate['frames']
    if args.sequence is not None and not 1 <= args.sequence <= len(candidate['sequences']):
        raise ValueError('candidate sequence index out of bounds')
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    save(out/'manifest.json', dict(diagnostic_only=True, new_llm_tokens=0, model_requests_allowed=False,
        bundle_sha256=digest(args.bundle), candidate_sha256=digest(source),
        candidate=str(source.resolve()), bundle=str(args.bundle.resolve()),
        source_schedule_sha256=digest(args.simulation_directory/'schedule.json') if args.simulation_directory else None,
        source_kind='candidate' if args.candidate else 'saved-simulation',
        source_sha256=framework_hashes(ROOT), isolation=POLICY, sequence=args.sequence, seed=args.seed,
        generation=str(args.generation) if args.generation else None, rebound_module_metadata=rebound,
        rebased_initial_ordinal=initial_ordinal))
    if args.generation or initial_ordinal: save(out/'candidate.json',candidate)
    config = json.loads(args.eda_config.read_text())
    config['eda_env'] = {'shell': str(args.eda_shell.resolve())}
    simulation = HavenSimulation(bundle, design, config, args.seed)
    start = time.monotonic()
    record = dict(status='running', started_utc=utc(), new_llm_tokens=0,
                  model_requests_allowed=False, diagnostic_only=True)
    save(out/'progress.json', record)
    try:
        from offline_validation import no_model_calls
        with no_model_calls():
            if args.sequence is None:
                result = IsolatedSimulation(simulation, out/'replay-cache')(out/'coverage', sequences, frames)
            else:
                batches = independent_batches(bundle, design, sequences, frames)
                sources, rows = batches[len(bundle['sequences']) + args.sequence - 1]
                result = simulation(out/'sequence', sources, rows)
        record.update(status='passed', coverage=result)
    except Exception as error:
        record.update(status='failed', error=str(error), diagnostics=getattr(error,'diagnostics',{}))
    record.update(finished_utc=utc(), elapsed_seconds=time.monotonic()-start)
    save(out/'summary.json', record)
    save(out/'progress.json', record)
    print(json.dumps({key:value for key,value in record.items() if key != 'coverage'}))
    return int(record['status'] != 'passed')


if __name__ == '__main__':
    raise SystemExit(main())
