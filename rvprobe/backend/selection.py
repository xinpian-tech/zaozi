"""Bounded, model-free concretization of a frozen formal cover.

JG's two-state witness may choose values for floating buses or uninitialized
storage. A solver hit is a candidate, not evidence that a concrete simulation
satisfies the cover. Accept only native-checked candidates, without changing the
goal or suppressing any checks. Never retry broken transport/infrastructure.
"""
import json
from pathlib import Path
from .records import Records, save, fingerprint

POLICY = 'native-validated-witness-selection-v2'


def unknown_output_bits(details):
    """Measured X/Z in formal-required output bits, not a guess from RTL names."""
    path = details.get('replay_checks')
    if not path or not Path(path).is_file():
        # Optional diagnostic absent: do not infer an initialization cause.
        # The failed native candidate remains rejected either way.
        return 0
    report = json.loads(Path(path).read_text())
    return sum((row['mask'] & ~row['known']).bit_count()
               for row in report.get('waveform_differences', [])
               if row.get('kind') == 'output')


def exhaustion_details(accepted, target, attempted, directory):
    """A bounded search shortfall is an outcome, not an infrastructure exception."""
    diagnostics = dict(kind='native_witness_search_exhausted', model_repair_allowed=False,
            accepted=accepted, target=target, attempted=attempted,
            selection=str(directory/'selection.json'),
            action='No complete native-valid witness set within the search budget. '
                   'Preserve the original UT and all attempts; this does not prove '
                   'unreachability or a shared Stage-1/transport defect.')
    auxiliary = directory/'encoded/search.json'
    if auxiliary.is_file():
        record = json.loads(auxiliary.read_text())
        diagnostics['auxiliary_search'] = dict(record=str(auxiliary),
                status=record.get('status'), stop_reason=record.get('stop_reason'),
                termination_reason=record.get('termination_reason'),
                solve_time_limit=record.get('solve_time_limit'),
                attempts=len(record.get('attempts', [])))
    return diagnostics


def search_budget(target):
    return min(256, max(target, 4 * target))


def pool_target(requested, available):
    """The sampling contract is *up to* requested distinct fixed-length inputs.

    Validate the entire offered pool (up to the cap), not an impossible number
    of extra solutions. A short offered pool is recorded, never filled with
    duplicate sequences or an unproved idle suffix.
    """
    if not 1 <= requested <= 256 or not 1 <= available <= 256:
        raise ValueError('invalid native witness pool size')
    return min(requested, available)


def select_witnesses(candidates, target, evaluate, replenish, directory, auxiliary=None):
    """evaluate returns the candidate payload after exact live-Cover validation.

replenish is lazy: no extra solver work when the initial candidates suffice.
All distinct attempted witnesses count toward the budget, including rejects.
Exhaustion returns only the validated subset with an explicit non-passing
record. Transport, solver process and unclassified errors still raise.
"""
    if not 1 <= target <= 256:
        raise ValueError('invalid native witness target')
    stage_budget = search_budget(target)
    budget = stage_budget * (2 if auxiliary else 1)
    record = dict(policy=POLICY, status='running', target=target, budget=budget,
                  auxiliary_candidate_policy='encoded-native-checked-v1' if auxiliary else None,
                  candidates_per_stage_budget=stage_budget,
                  attempts=[], accepted=0)
    selected, seen = [], set()
    save(directory/'selection.json', record)
    try:
        with Records(directory).phase('native-witness-selection'):
            batches = [(lambda: candidates, stage_budget), (lambda: replenish(stage_budget), stage_budget)]
            if auxiliary:
                batches.append((lambda: auxiliary(stage_budget), budget))
            needs_known_state = False
            for stage, (batch, ceiling) in enumerate(batches):
                if stage == 1 and needs_known_state:
                    continue
                for row in batch():
                    identity = row.get('inputFingerprint') or fingerprint(
                        json.loads(Path(row['stimulusFile']).read_text()))
                    if identity in seen:
                        continue
                    if len(seen) >= ceiling:
                        break
                    seen.add(identity)
                    item = dict(index=len(seen)-1, input_fingerprint=identity,
                                witness=row.get('witnessFile'), status='running')
                    record['attempts'].append(item)
                    save(directory/'selection.json', record)
                    try:
                        value = evaluate(row, item['index'])
                    except ValueError as error:
                        details = getattr(error, 'diagnostics', {})
                        item.update(status='rejected', error=str(error),
                                    kind=details.get('kind'), log=details.get('log'),
                                    replay_checks=details.get('replay_checks'))
                        if details.get('kind') != 'formal_replay_semantics_mismatch':
                            raise
                        item['unknown_output_bits'] = unknown_output_bits(details)
                        if auxiliary and stage < 2 and item['unknown_output_bits']:
                            # Changing 2-state input preferences cannot establish
                            # initialized storage. Solve with explicit X rails now.
                            needs_known_state = True
                            record['known_state_escalation'] = dict(attempt=item['index'],
                                reason='observed_unknown_required_output',
                                skipped_two_state_replenishment=True)
                    else:
                        item['status'] = 'passed'
                        selected.append(value)
                        record['accepted'] = len(selected)
                    save(directory/'selection.json', record)
                    if len(selected) == target:
                        record['status'] = 'passed'
                        save(directory/'selection.json', record)
                        return selected, record
                    if needs_known_state and stage == 1:
                        break
            record.update(status='exhausted', diagnostics=exhaustion_details(
                len(selected), target, len(seen), directory))
            save(directory/'selection.json', record)
            return selected, record
    except Exception as error:
        record.update(status='failed', error=str(error))
        save(directory/'selection.json', record)
        raise
