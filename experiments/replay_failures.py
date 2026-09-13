"""Measured transport failures, not guesses about a DUT's initialization logic."""
import re

POLICY = 'explicit-transport-failure-no-model-repair-v2'


class ReplayInfrastructureFailure(ValueError):
    def __init__(self, message):
        super().__init__(message)
        self.diagnostics=dict(kind='replay_setup_failure',model_repair_allowed=False,
            action='Repair the replay/provenance adapter; keep the already validated author UT unchanged.')


def classify(log):
    if re.search(r'(?m)^original LTL goal did not hold on live IO:', log):
        return dict(kind='formal_replay_semantics_mismatch',model_repair_allowed=False,
                    action='Inspect input fidelity, initial state, clock sampling and the original Cover monitor; preserve the proved UT and witness, do not regenerate LTL.')
    if re.search(r'(?m)^(sampled drive differs:|replay order/reset differs|driver inserted or dropped|sample count differs|sample columns differ|missing or incomplete replay completion)',log):
        return dict(kind='transport_contract_failure',model_repair_allowed=False,
                    action='Repair the stimulus/clock/observation adapter; do not ask the author to weaken the original LTL.')
    tags = set(re.findall(r'^UVM_FATAL\b[^\n]*\[([A-Z_]+)\]', log, re.M))
    if 'WITNESS_X' in tags:
        return dict(kind='unknown_concrete_output', model_repair_allowed=False,
                    action='Resolve the formal/simulation initial-state contract; do not zero-fill storage or mask checked bits.')
    if 'ENVIRONMENT_WITNESS' in tags:
        return dict(kind='environment_response_mismatch', model_repair_allowed=False,
                    action='Check the shared response model against concrete BFM behavior; do not drive over the live response.')
    if tags & {'ENVIRONMENT_ADAPTER', 'EVENT_TIME', 'INPUT_WITNESS'}:
        return dict(kind='transport_contract_failure', model_repair_allowed=False,
                    action='Repair and validate the shared adapter before generating new candidates.')
    if 'CANDIDATE_ENVIRONMENT' in tags:
        return dict(kind='candidate_environment_violation', model_repair_allowed=True,
                    action='The candidate requests a different value for a fixed shared-environment input. '
                           'Keep the shared environment unchanged; use its declared fixed values. '
                           'Do not remove checks or claim coverage for unavailable configuration changes.')
    return dict(kind='candidate_or_unclassified_failure', model_repair_allowed=True)
