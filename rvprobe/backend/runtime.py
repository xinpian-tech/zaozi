"""RVProbe's native-validated witness-to-sequence boundary.

Experiment adapters supply IO serialization and a simulator, not acceptance or
retry policy. A successful solver result alone never leaves this boundary as a
validated sequence. No model calls or coverage policy belong here.
"""
from copy import deepcopy
from dataclasses import dataclass
from pathlib import Path
from typing import Callable
import re

from .failures import ReplayInfrastructureFailure
from .records import fingerprint, save
from .replay import attach, POLICY as REPLAY_POLICY
from .selection import pool_target, select_witnesses

POLICY = 'rvprobe-native-sequence-backend-v2'


@dataclass(frozen=True)
class ReplayTransport:
    frames: Callable  # (candidate, segment) -> clocked IO rows
    render: Callable  # (rows, name, ordinal) -> sequence source
    measure: Callable  # (source, rows) -> transport + native Cover receipt

    def __post_init__(self):
        if not all(callable(f) for f in (self.frames, self.render, self.measure)):
            raise ReplayInfrastructureFailure('RVProbe requires a native replay transport')


def require_receipt(meta, receipt):
    expected = dict(policy=REPLAY_POLICY, label=meta['label'],
                    source_sha256=meta['sha256'], native_cover_hit=True,
                    four_state_simulation=True)
    if (not isinstance(receipt, dict) or receipt.get('passed') is not True or
            receipt.get('ltl') != expected):
        raise ReplayInfrastructureFailure('missing or mismatched native LTL/transport receipt')


class WitnessBackend:
    def __init__(self, transport: ReplayTransport, replenish, known_state=None):
        if not isinstance(transport, ReplayTransport):
            raise ReplayInfrastructureFailure('RVProbe requires a native replay transport')
        self.transport = transport
        self.replenish = replenish
        self.known_state = known_state

    def generate(self, goals, directory, namespace, cap, ordinal=0):
        """Return validated sequences; all failed attempts retain their records.

        Providers receive (frozen_goal, directory, budget). Their candidates
        must still pass the SAME original goal through the transport adapter.
        """
        directory = Path(directory)
        if type(cap) is not int or not 1 <= cap <= 256:
            raise ValueError('invalid sequence cap')
        labels = [goal['label'] for goal in goals]
        if (len(set(labels)) != len(labels) or
                any(not re.fullmatch(r'[A-Za-z_]\w*', name) for name in [namespace, *labels])):
            raise ValueError('invalid or duplicate backend goal/namespace')
        sequences, frames, sampling = [], [], []
        record = dict(policy=POLICY, status='running', remote_llm_requests=0,
                      shortfall_policy='retain-native-valid-sequences-and-continue-independent-intents-v1')
        save(directory/'backend.json', record)
        try:
            for goal in goals:
                rows = goal.get('sequences', [goal] if goal['status'] == 'generated' else [])
                if not rows:
                    sampling.append(dict(label=goal['label'], actual=0, requested_cap=cap,
                        available_pool=0, sampling_shortfall=cap, status=goal['status'],
                        solver_detail=goal.get('detail'), solver_unknown_reason=goal.get('unknownReason')))
                    continue
                search = directory/goal['label']
                accepted_frames = 0

                def evaluate(row, index):
                    nonlocal accepted_frames
                    segment = ordinal + len(frames) + accepted_frames
                    new = self.transport.frames(deepcopy(row), segment)
                    attach(new, goal)
                    name = f'{namespace}_{goal["label"]}_{index}'
                    source = self.transport.render(deepcopy(new), name, 0)
                    before = fingerprint(new)
                    receipt = self.transport.measure(source, new)
                    if fingerprint(new) != before:
                        raise ReplayInfrastructureFailure('replay transport mutated the witness schedule')
                    require_receipt(new[0]['ltl'], receipt)
                    # Global ordinals are bookkeeping. Isolated replay renders
                    # these identical rows at ordinal zero before simulation.
                    emitted = self.transport.render(deepcopy(new), name, segment)
                    accepted_frames += len(new)
                    return dict(source=emitted, frames=new, receipt=receipt)

                selected, selection = select_witnesses(rows, pool_target(cap, len(rows)), evaluate,
                    lambda budget: self.replenish(goal, search/'sampling', budget), search,
                    auxiliary=(lambda budget: self.known_state(goal, search/'encoded', budget))
                    if self.known_state else None)
                sampling.append(dict(label=goal['label'], actual=len(selected), requested_cap=cap,
                    available_pool=len(rows), sampling_shortfall=max(0, cap-len(selected)),
                    pool_policy='up-to-cap-only-native-valid-v2',
                    status=('native-validated' if selection['status']=='passed' else
                            ('partial' if selected else 'unresolved')),
                    native_selection=selection))
                for accepted in selected:
                    sequences.append(accepted['source'])
                    frames.extend(accepted['frames'])
                # A later failed intent must not erase already validated artifacts.
                save(directory/'accepted.json', dict(sequences=sequences, frames=frames,
                    sampling=sampling, all_intents_processed=False))
            unresolved = [item['label'] for item in sampling if not item['actual']]
            partial = [item['label'] for item in sampling if item['status']=='partial']
            record.update(status=('completed_with_shortfalls' if unresolved or partial else 'passed'),
                          unresolved_intents=unresolved, partial_intents=partial,
                          all_intents_satisfied=not unresolved,
                          sequences=len(sequences), sampling=sampling,
                          sequence_sha256=[fingerprint(s) for s in sequences],
                          schedule_sha256=fingerprint(frames))
            return dict(sequences=sequences, frames=frames,
                        metadata=dict(backend=POLICY, status=record['status'], sampling=sampling,
                            unresolved_intents=unresolved, partial_intents=partial,
                            all_intents_satisfied=not unresolved))
        except Exception as error:
            record.update(status='failed', error=str(error))
            raise
        finally:
            save(directory/'accepted.json', dict(sequences=sequences, frames=frames,
                sampling=sampling, all_intents_processed=record['status'] not in ('running','failed')))
            save(directory/'backend.json', record)
