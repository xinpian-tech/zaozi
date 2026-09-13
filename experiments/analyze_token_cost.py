"""Read-only accounting of saved paired runs; never prints model reasoning text.

Provider token counts are exact reported usage. Prompt breakdowns are characters,
not token estimates; no causal savings or monetary claims are inferred.
"""
import argparse
import json
from pathlib import Path

from run_records import save, totals
from sequence_experiment import prompt_json


def analyze(paired):
    paired = Path(paired)
    costs = totals(paired / 'rvprobe')
    usage = costs['usage_reported']
    detail = costs['usage_breakdown']['reasoning_tokens']
    reasoning = detail['reported_tokens'] if detail['complete'] else None
    requests = []
    prompts = []
    for directory in sorted((paired / 'rvprobe').glob('round-*/generation')):
        for line in (directory / 'events.jsonl').read_text().splitlines():
            event = json.loads(line)
            if event.get('phase') == 'model-request' and event.get('status') == 'ok':
                requests.append({key: event.get(key) for key in ('attempt', 'tool_step', 'seconds', 'usage',
                                  'usage_details', 'output_characters')} | {'round': directory.parent.name})
        for path in sorted(directory.glob('attempt-*/prompt.json')):
            meta = json.loads(path.read_text())
            feedback_path = directory.parent / 'feedback.json'
            feedback = json.loads(feedback_path.read_text()) if feedback_path.exists() else {}
            shared = feedback.get('shared_context', {})
            prompts.append({'round': directory.parent.name, 'attempt': path.parent.name,
                'characters': meta['characters'], 'sections': meta['sections'],
                'shared_context_characters': len(prompt_json(shared)) if shared else 0,
                'shared_context_fields_characters': {key: len(prompt_json(value)) for key, value in shared.items()},
                'feedback_without_shared_context_characters': len(prompt_json({key: value for key, value in feedback.items() if key != 'shared_context'}))})
    empty = [row for row in requests if row['tool_step'] == 1 and row['output_characters'] == 0]
    return {'scope': 'saved-run accounting; no model/EDA calls and no reasoning text',
            'new_model_requests': 0, 'rvprobe_costs': costs,
            'partition': {'input_tokens': usage['prompt_tokens'], 'reasoning_tokens': reasoning,
                          'non_reasoning_completion_tokens': usage['completion_tokens'] - reasoning if reasoning is not None else None},
            'empty_generation_responses': empty,
            'requests': requests, 'prompts': prompts,
            'caveats': ['Character sizes are not provider token counts.',
                        'Reasoning tokens are a subset of completion tokens, not an additional charge.',
                        'Request counts include actual tool exchanges and repair calls; current skill injection has no separate bootstrap request.',
                        'No measured token reduction or causal prompt efficacy is claimed.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('paired', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists() or args.out.resolve().is_relative_to(args.paired.resolve()):
        parser.error('output must be new and outside the historical paired directory')
    report = analyze(args.paired)
    save(args.out, report)
    print(json.dumps({'partition': report['partition'], 'requests': len(report['requests']),
                      'empty_responses': len(report['empty_generation_responses']),
                      'first_prompt': report['prompts'][0]}, indent=2))


if __name__ == '__main__':
    main()
