"""Bounded, model-free concretization of a frozen formal cover.

JG's two-state witness may choose values for floating buses or uninitialized
storage. A solver hit is a candidate, not evidence that a concrete simulation
satisfies the cover. Accept only native-checked candidates, without changing the
goal or suppressing any checks. Never retry broken transport/infrastructure.
"""
import json
from pathlib import Path
from run_records import Records, save, fingerprint
from replay_failures import ReplayInfrastructureFailure

POLICY = 'native-validated-witness-selection-v1'


class NativeWitnessExhaustion(ReplayInfrastructureFailure):
    """A bounded concretization failure is not proof of a broken shared adapter."""
    def __init__(self, accepted, target, attempted, directory):
        super().__init__(f'native witness search exhausted: {accepted}/{target} validated '
                         f'from {attempted} distinct candidates; frozen LTL remains unchanged')
        self.diagnostics = dict(kind='native_witness_search_exhausted', model_repair_allowed=False,
            accepted=accepted, target=target, attempted=attempted,
            selection=str(directory/'selection.json'),
            action='No complete native-valid witness set within the search budget. '
                   'Preserve the original UT and all attempts; this does not prove '
                   'unreachability or a shared Stage-1/transport defect.')


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
            for batch, ceiling in batches:
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
                    else:
                        item['status'] = 'passed'
                        selected.append(value)
                        record['accepted'] = len(selected)
                    save(directory/'selection.json', record)
                    if len(selected) == target:
                        record['status'] = 'passed'
                        save(directory/'selection.json', record)
                        return selected, record
            raise NativeWitnessExhaustion(len(selected), target, len(seen), directory)
    except Exception as error:
        record.update(status='failed', error=str(error))
        save(directory/'selection.json', record)
        raise
