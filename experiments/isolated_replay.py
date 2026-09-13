"""Fresh simulation processes for independent sequences, with real VDB union.

Every sequence, including the common Stage-1 baseline, starts from
the RTL power-on state, for both HAVEN and RVProbe. Reset alone is not assumed
to clear memories. No DUT state is assigned by this replay adapter.
"""
from collections import OrderedDict
from pathlib import Path
import re
import shlex

from haven_shared import render_witness_sequence, coverage_score, METRICS
from run_records import save, fingerprint, Records
from urg_score import parse, score, _MODULE_SPLIT

POLICY = 'fresh-process-per-sequence-v2'
DIAGNOSTIC_POLICY = 'all-candidate-sequences-before-model-repair-v1'


def diagnostic_excerpt(text):
    """Retain compiler/runtime errors, not copyright banners and zero summaries."""
    lines=text.splitlines(); selected=[]
    for index,line in enumerate(lines):
        if re.match(r'\s*Error-\[',line):
            selected.extend(lines[index:index+7])
        elif re.match(r'\s*UVM_(?:ERROR|FATAL)\b',line) and not re.search(r':\s*0\s*$',line):
            selected.append(line)
    return '\n'.join(dict.fromkeys(selected))[:6000]


class CandidateBatchFailure(ValueError):
    """All measured candidate defects in one repair; no partial acceptance."""
    def __init__(self, failures):
        groups = OrderedDict()
        for row in failures:
            key = (row['label'],row['error'])
            if key not in groups:
                groups[key] = {**row,'sequence_indices':[]}
                groups[key].pop('sequence_index')
            groups[key]['sequence_indices'].append(row['sequence_index'])
        grouped = list(groups.values())
        super().__init__(f'{len(failures)} candidate sequences failed: '+
                         '; '.join(f"{r['label']}: {r['error']}" for r in grouped))
        self.diagnostics = dict(kind='candidate_batch_failure',model_repair_allowed=True,
            policy=DIAGNOSTIC_POLICY,failed_sequences=len(failures),failures=grouped,
            action='Repair all listed failures together; the entire candidate remains rejected. Do not remove checks or weaken the intended behavior.')


def independent_batches(bundle, design, sequences, frames):
    baseline = list(bundle['sequences'])
    if list(sequences[:len(baseline)]) != baseline:
        raise ValueError('shared baseline was altered')
    groups = OrderedDict()
    for row in frames:
        segment = row['segment']
        if segment in groups and segment != next(reversed(groups)):
            raise ValueError('non-contiguous witness segment')
        groups.setdefault(segment, []).append(row)
    pending = iter(groups.values())
    ordinal = 0
    batches = [([source], []) for source in baseline]
    for source in sequences[len(baseline):]:
        if re.search(r'\brvp_raw\s*=\s*1\s*;', source):
            rows = next(pending, None)
            if rows is None:
                raise ValueError('raw sequence has no witness frames')
            names = re.findall(r'\bclass\s+(\w+)\s+extends\b', source)
            if len(names) != 1 or source != render_witness_sequence(design, rows, names[0], ordinal):
                raise ValueError('raw sequence differs from recorded witness frames')
            source = render_witness_sequence(design, rows, names[0], 0)
            ordinal += len(rows)
            batches.append(([source], rows))
        else:
            batches.append(([source], []))
    if next(pending, None) is not None:
        raise ValueError('witness frames have no sequence')
    return batches


class IsolatedSimulation:
    """Adapter around HavenSimulation; cached verified runs are merged, not summed."""
    def __init__(self, simulator, cache):
        self.simulator = simulator
        self.cache = Path(cache)
        self.completed = {}
        self.rejected = {}

    def measure_one(self, sources, rows):
        """One immutable cache entry, also used by native witness selection."""
        simulator = self.simulator
        key = fingerprint(dict(bundle=simulator.bundle['fingerprint'], seed=simulator.seed,
                               config=simulator.config, sources=sources, frames=rows))
        target = self.cache / key
        if key in self.rejected:
            raise self.rejected[key]
        if key not in self.completed:
            try:
                self.completed[key] = simulator(target, sources, rows)
            except ValueError as error:
                self.rejected[key] = error
                raise
        result = self.completed[key]
        from cycle_replay import digest
        if any(digest(Path(p)) != sha for p, sha in result['artifact_sha256'].items()):
            raise ValueError('cached replay artifacts changed')
        return target, result

    def evaluate(self, batches):
        simulator = self.simulator
        databases, results, failures = [], [], []
        for index, (sources, rows) in enumerate(batches):
            try:
                target, result = self.measure_one(sources, rows)
            except ValueError as error:
                detail = getattr(error,'diagnostics',{})
                # Broken baseline, infrastructure and unknown exceptions still
                # stop immediately. Never spend model calls on these failures.
                if (index < len(simulator.bundle['sequences']) or
                        detail.get('model_repair_allowed') is not True):
                    raise
                label = next((r['ltl']['label'] for r in rows if 'ltl' in r), None)
                if label is None:
                    names = re.findall(r'\bclass\s+(\w+)\s+extends\b',sources[0])
                    label = names[0] if names else 'sequence-'+str(index+1)
                failures.append(dict(sequence_index=index+1,label=label,error=str(error),
                                     log=detail.get('log'),kind=detail.get('kind'),
                                     measured_excerpt=diagnostic_excerpt(detail.get('tail',''))))
                continue
            databases.append(target / 'simv.vdb')
            results.append(result)
        if failures:
            raise CandidateBatchFailure(failures)
        return databases, results

    def __call__(self, directory, sequences, frames):
        simulator = self.simulator
        coverage_module = getattr(simulator,'coverage_module',simulator.design.top)
        batches = independent_batches(simulator.bundle, simulator.design, sequences, frames)
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=False)
        try:
            databases, results = self.evaluate(batches)
        except CandidateBatchFailure as error:
            save(directory/'failures.json',error.diagnostics)
            raise
        from cycle_replay import digest
        from haven.eda import run_eda_command
        from haven.eda.urg_utils import parse_urg_output
        save(directory / 'batches.json', dict(policy=POLICY,diagnostic_policy=DIAGNOSTIC_POLICY,
                                              databases=list(map(str, databases)), runs=results))
        with Records(directory).phase('urg-merge'):
            report_name = f'{simulator.design.top}_urgReport'
            command = ('urg -dir ' + ' '.join(shlex.quote(str(p)) for p in databases)
                       + ' -metric line+cond+tgl+fsm+branch -format text -report ' + shlex.quote(report_name))
            merged = run_eda_command('vcs', command, cwd=str(directory),
                                     eda_env=simulator.config.get('eda_env'))
            (directory / 'merge.log').write_text(merged.stdout + merged.stderr)
            if merged.returncode:
                raise ValueError('isolated coverage merge failed')
            modinfo = directory / report_name / 'modinfo.txt'
            percent, total, bins = score(parse(modinfo), [coverage_module], METRICS)
            if {k:v[0] for k,v in bins.items()} != {k:v[0] for k,v in results[0]['bins'].items()}:
                raise ValueError('merged DUT coverage denominator changed')
            if coverage_score(bins) != (percent, total):
                raise ValueError('inconsistent merged coverage score')
            parts = _MODULE_SPLIT.split(modinfo.read_text())
            section = next(body for name, body in zip(parts[1::2], parts[2::2]) if name == coverage_module)
            gaps = parse_urg_output(section)['uncovered']
            for gap in gaps:
                gap['module'] = coverage_module
            for metric, value in percent.items():
                if value < 100 and not any(g.get('type') == metric for g in gaps):
                    gaps.append(dict(type=metric, module=coverage_module, summary_only=True,
                                     covered=bins[metric][1], total=bins[metric][0], report_section=section))
        result = dict(modules=[coverage_module], percent=percent, score=total, bins=bins,
                      uncovered=gaps, modinfo=str(modinfo), replay=dict(passed=True,
                      witness_cycles=sum(r['replay']['witness_cycles'] for r in results)),
                      policy=POLICY, functional_correctness_proven=False,
                      artifact_sha256={str(p):digest(p) for p in [modinfo, directory/'batches.json', directory/'merge.log']})
        proofs = [r['replay']['ltl'] for r in results if r['replay'].get('ltl')]
        if proofs:
            result['replay']['native_ltl_checks'] = proofs
            result['replay']['native_ltl_sequences'] = len(proofs)
        save(directory / 'coverage.json', result)
        return result
