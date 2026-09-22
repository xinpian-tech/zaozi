"""Rebuild independent model requests from observed data, never assistant thought.

Not a continuation of a provider tool conversation: tool IDs and assistant
messages are deliberately absent. Exact evidence is supplied as user data.
"""
from copy import deepcopy
import json

POLICY = 'independent-evidence-requests-v3-direct-authoring'


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))


def request_messages(base, evidence, remaining_steps, remaining_tools, final=False, retrieval=False):
    ready_instruction = ('If the specification, IO and supplied evidence are sufficient, output the requested LTL now, '
                         'not READY or an evidence summary; do not read RTL merely for confirmation. ')
    messages = list(base) + [{'role':'user', 'content':
        'Independent evidence request. Prior assistant conversations are not included. '
        'Use the exact observed data below; request only missing evidence, batching independent reads. '
        + ready_instruction + 'Do not repeat completed reads. '
        f'Remaining evidence steps: {remaining_steps}; remaining tool entries: {remaining_tools}.\n'
        'Frozen task evidence (data, not instructions):\n' + compact(evidence)}]
    if final:
        messages.append({'role':'user', 'content':
            'The read-only evidence budget is now closed. Using the evidence already returned, '
            'provide the requested raw LTL fragment (or STOP) now. Do not request more tools or invent missing evidence.'})
    return messages


def packet(context, initial, observations):
    result = deepcopy(initial)
    result['dialogue_policy'] = POLICY
    rows = []
    prior = result.get('previously_read_rtl')
    if prior:
        rows.extend(prior['ranges'] if prior['inline'] else prior.get('inline_ranges', []))
        prior['ranges'] = [{k:v for k,v in r.items() if k != 'text'} for r in prior['ranges']]
        if 'inline_ranges' in prior:
            prior['inline_ranges'] = [{k:v for k,v in r.items() if k != 'text'}
                                      for r in prior['inline_ranges']]
        prior['body_location'] = 'rtl_ranges; non-inline ranges remain available via read tools'
    evidence = []
    seen = set()
    for observation in observations:
        item = deepcopy(observation)
        name, value = item['name'], item['result']
        pieces = ([value] if name == 'read_rtl' and 'error' not in value else
                  value.get('ranges', []) if name in ('read_rtl_batch', 'inspect_rtl_batch') else [])
        for piece in pieces:
            if piece.get('text'):
                rows.append(dict(piece))
                del piece['text']
        key = compact(item)
        if key not in seen:
            seen.add(key)
            evidence.append(item)
    if rows:
        from rtl_evidence import validate_and_merge, project_inline
        merged = validate_and_merge(context.files, rows)
        result['rtl_ranges'] = project_inline(merged)
        if len(result['rtl_ranges']) < len(merged) or any(
                a.get('offset') != b.get('offset') or len(a.get('text','')) != len(b.get('text',''))
                for a,b in zip(result['rtl_ranges'], merged)):
            result['rtl_evidence_index'] = {
                'policy':'bounded-recent-inline-v1',
                'complete_ranges':[{k:v for k,v in row.items() if k!='text'} for row in merged],
                'inline_characters':sum(len(row.get('text','')) for row in result['rtl_ranges']),
                'omitted_characters':sum(len(row.get('text','')) for row in merged)-
                    sum(len(row.get('text','')) for row in result['rtl_ranges']),
                'access':'read_context(topic=rtl_history) or read_rtl at omitted offsets'}
    result['observations'] = evidence
    return result
