"""Offline payload-size audit at saved tool boundaries; not a token prediction.

Reuses the exact historical tool schedule without calling a model or modifying
historical artifacts. Independent-request behavior/coverage requires a new trial.
"""
import argparse
import hashlib
import json
from pathlib import Path

from evidence_packet import packet, request_messages, POLICY
from run_records import save
from sequence_framework import Design, Port
from task_context import TaskContext, MAX_MODEL_CALLS, MAX_TOOL_CALLS


def profile(generation):
    generation = Path(generation)
    manifest = json.loads((generation/'manifest.json').read_text())
    data = manifest['design']
    for source in data['sources']:
        if hashlib.sha256(Path(source['path']).read_bytes()).hexdigest() != source['sha256']:
            raise ValueError('saved RTL changed')
    design = Design(data['top'], tuple(Path(p['path']) for p in data['sources']),
        tuple(Path(p) for p in data['include_dirs']), tuple(Port(**p) for p in data['ports']),
        data['clock'], data['reset']['port'], data['reset']['active_low'],
        data['sequence']['name'], data['sequence']['item_type'], data['context'],
        tuple(data['parameters'].items()))
    history = json.loads((generation.parent/'accepted-history.json').read_text())
    context = TaskContext(design, manifest['feedback'], history)
    attempt = generation/'attempt-1'
    skill = json.loads((attempt/'skill-context.json').read_text())
    base = [{'role':'user','content':'RVProbe LTL API and usage:\n'+skill['content']},
            {'role':'user','content':(attempt/'prompt.txt').read_text()}]
    initial = context.initial_evidence()
    calls = json.loads((attempt/'dialogue-1.json').read_text())['calls']
    saved = []
    for path in sorted(attempt.glob('task-tool-1-*.json'), key=lambda p:int(p.stem.split('-')[-1])):
        value = json.loads(path.read_text())
        fn = value['tool_call']['function']
        saved.append((int(path.stem.split('-')[-1]), {'name':fn['name'],
            'arguments':json.loads(fn['arguments']), 'result':value['result']}))
    rows = []
    for call in calls:
        used = call['tool_budget_used']
        current = packet(context, initial, [item for count,item in saved if count <= used])
        messages = request_messages(base, current, MAX_MODEL_CALLS-call['tool_step']-1,
                                    MAX_TOOL_CALLS-used,
                                    call['tool_step']==MAX_MODEL_CALLS-1 or used>=MAX_TOOL_CALLS)
        rows.append({'step':call['tool_step'], 'historical_input_characters':
            call['input_characters']+call['history_reasoning_characters'],
            'clean_input_characters':sum(len(m['content']) for m in messages),
            'historical_reasoning_history_characters':call['history_reasoning_characters']})
    old = sum(r['historical_input_characters'] for r in rows)
    new = sum(r['clean_input_characters'] for r in rows)
    return {'source':str(generation), 'http_requests_held_constant':len(rows),
        'historical_input_characters':old, 'clean_input_characters':new,
        'reduction_percent':100*(old-new)/old, 'rows':rows,
        'historical_total_tokens':sum(c['usage']['total_tokens'] for c in calls)}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--generation', type=Path, nargs='+', required=True)
    p.add_argument('--out', type=Path, required=True)
    args = p.parse_args()
    if args.out.exists() or any(args.out.resolve().is_relative_to(g.resolve().parent) for g in args.generation):
        p.error('use a new output outside historical rounds')
    result = {'policy':POLICY, 'new_model_requests':0,
        'measurement':'sum of content and reasoning character lengths, excluding schemas/tool arguments; not tokens',
        'caveat':'Fixed historical tool schedule only; changed model behavior and coverage are not measured.',
        'rounds':[profile(g) for g in args.generation]}
    save(args.out, result)
    print(json.dumps({**result, 'rounds':[{k:v for k,v in r.items() if k!='rows'} for r in result['rounds']]}, indent=2))


if __name__ == '__main__':
    main()
