"""Versioned common task evidence; no model-generated summaries or gap selection."""
from copy import deepcopy
import re

from run_records import fingerprint

POLICY = "common-evidence-v7"
MAX_INTENTS = 4
BATCH_INSTRUCTION = (
    "Propose a small useful batch of at most 4 new finite verification intents from the measured gaps. "
    "Use one Gen label per intent for RVProbe, or one DSL sequence per intent for HAVEN. "
    "Once the batch is selected, output the complete UT/DSL rather than expanding the gap analysis. "
    "Keep the selected intents' required depth and checks; defer unselected gaps to later rounds. "
    "You need not close or classify every residual in this response. The framework owns the "
    "coverage loop and stopping budgets. Do not declare model_stop merely because this batch "
    "is complete; stop only when you cannot propose a useful new intent. "
    "Do not attempt exhaustive reachability proofs; record a suspected contradiction briefly "
    "only if needed. Keep intent conditions necessary and leave unrelated inputs free. "
    "Output-related intents must identify the intended transaction through its actual handshake "
    "and required history, not merely match a numeric value on a potentially stale output. "
    "Initialize any observed storage through legal IO activity first. Formal cover uses a "
    "two-state overapproximation: arbitrary uninitialized storage may satisfy a weak predicate "
    "that cannot hold in native four-state replay. Both methods are checked in the unchanged "
    "native environment. Do not replace a failing output-related intent with an input-only "
    "condition or delete its essential checks."
)

# Preserve HAVEN's existing prompt verbatim. Only RVProbe's authoring contract changes.
RVPROBE_BATCH_INSTRUCTION = (BATCH_INSTRUCTION
    .replace("Use one Gen label per intent for RVProbe, or one DSL sequence per intent for HAVEN.",
             "Use one Gen label per intent.")
    .replace("complete UT/DSL", "LTL fragment")
    .replace("model_stop", "STOP")
    .replace("record a suspected contradiction briefly only if needed. ",
             "do not emit proof classifications. "))


def rvprobe_batch_instruction(limit=MAX_INTENTS):
    """Render the RVProbe-only batch contract without changing HAVEN's text."""
    if type(limit) is not int or not 1 <= limit <= 8:
        raise ValueError('invalid RVProbe intent batch limit')
    return RVPROBE_BATCH_INSTRUCTION.replace('at most 4 new finite',
                                             f'at most {limit} new finite')


def public_bfm_parameters(sources):
    """Expose actual interface parameters, never model bodies or test scenarios."""
    result={}
    for name,source in sources.items():
        code=re.sub(r'//[^\n]*|/\*.*?\*/',' ',source,flags=re.S)
        header=re.search(r'\binterface\s+\w+\s*#\s*\((.*?)\)\s*\(',code,re.S)
        if header: result[name]=header[1].strip()
    return result


def common_context(bundle):
    """Project fixed evidence, not compiled UVM or Stage-1 planning.

    HAVEN retains native DSL/transaction evidence inline. RVProbe's TaskContext
    removes that abstraction and exposes physical conditions/RTL on demand.
    This is an explicit method-specific
    evidence projection, NOT a lossless summary of the removed derived artifacts.
    No fields are chosen based on a design name, coverage result or known answer.
    """
    blueprint = bundle["blueprint"]
    # Derive only declarations from the actual frozen BFM artifacts. No BFM
    # implementation, protocol scenario or historical answer is injected.
    bfm_api = {}
    if bundle.get('bfm_components'):
        from haven.utils.bfm_api import available
        bfm_api = available(blueprint.get('bfm_configs'))
    return deepcopy({
        "policy": POLICY,
        "protocol_flows": bundle["protocol_flows"],
        "initial_dsl": bundle["initial_dsl"],
        "seq_item_fields": blueprint.get("data_contracts", {}).get("seq_item_fields", []),
        "actual_seq_item_declaration": bundle.get('native_seq_item'),
        "transaction_contract": blueprint.get("transaction_contract"),
        "trusted_environment": (bundle.get('replay') or {}).get('environment'),
        "pin_ownership": blueprint.get('environment_contract'),
        "sequence_dispatch": blueprint.get('sequence_dispatch'),
        "bfm_callable_api": bfm_api,
        "bfm_configuration": blueprint.get('bfm_configs') or [],
        "bfm_parameter_declarations": public_bfm_parameters(bundle.get('bfm_components') or {}),
        "environment_conformance": bundle.get('event_environment_policy'),
        "baseline": {"bundle_fingerprint": bundle["fingerprint"],
                     "sequence_count": len(bundle["sequences"]),
                     "compiled_sequences_sha256": fingerprint(bundle["sequences"])},
        "omitted_derived_artifacts": {
            key: fingerprint(bundle[key]) for key in ("structured_spec", "blueprint", "sequences")},
    })


def paired_feedback(feedback, bundle):
    result = deepcopy(feedback)
    result["shared_context"] = common_context(bundle)
    result["batch_instruction"] = BATCH_INSTRUCTION
    if (bundle.get('replay') or {}).get('environment',{}).get('boundary') == 'independent-dut-v1':
        result['batch_instruction'] = BATCH_INSTRUCTION.replace(
            'Both methods are checked in the unchanged native environment.',
            'The boundary is the independent DUT. Both methods can use the same raw pin transport. '
            'Raw replay drives all data inputs exactly; external device models do not override them. '
            'Clock/reset/static policy remains fixed. Native baseline stimulus producers remain available.')
    result['intent_batch_limit'] = MAX_INTENTS
    return result
