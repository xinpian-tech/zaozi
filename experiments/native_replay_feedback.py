"""Bounded next-round observations, not authority to repair a model expression."""
from copy import deepcopy
import re

FIELDS = ('label', 'status', 'actual', 'requested_cap', 'sampling_shortfall')


def outcome(row):
    """Do not infer bad LTL, UNSAT or unreachable behavior from search exhaustion.

    Only machine fields from the known selector are included. No source, paths,
    VCD, arbitrary log tails or extra model calls are introduced by this helper.
    """
    base = {key: deepcopy(row[key]) for key in FIELDS if key in row}
    if row.get('status') not in ('unresolved', 'partial'):
        return base
    selection = row.get('native_selection')
    if not isinstance(selection, dict):
        return base
    diagnostics = selection.get('diagnostics')
    if not isinstance(diagnostics, dict) or diagnostics.get('kind') != 'native_witness_search_exhausted':
        return base
    facts = dict(stage='native-four-state-replay', kind='native_witness_search_exhausted',
                 cause_not_established=True, automatic_ltl_repair_allowed=False)
    escalation = selection.get('known_state_escalation')
    if isinstance(escalation, dict) and escalation.get('reason') == 'observed_unknown_required_output':
        facts['observed_required_output_unknown'] = True
    attempts = selection.get('attempts', [])
    if isinstance(attempts, list):
        facts['rejected_candidates'] = sum(isinstance(a, dict) and a.get('status') == 'rejected' for a in attempts)
    auxiliary = diagnostics.get('auxiliary_search')
    if isinstance(auxiliary, dict):
        compact = {}
        for key in ('status', 'stop_reason', 'termination_reason'):
            value = auxiliary.get(key)
            if isinstance(value, str) and re.fullmatch(r'[a-z][a-z_]{0,63}', value):
                compact[key] = value
        limit = auxiliary.get('solve_time_limit')
        if isinstance(limit, str) and re.fullmatch(r'[1-9][0-9]{0,8}[smh]', limit):
            compact['solve_time_limit'] = limit
        count = auxiliary.get('attempts')
        if type(count) is int and 0 <= count <= 512:
            compact['attempts'] = count
        if compact:
            facts['known_state_search'] = compact
    base['native_observations'] = facts
    return base
