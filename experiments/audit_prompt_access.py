"""Rebuild paired initial prompts and check tool evidence offline; no model/EDA calls."""
import argparse
import json
from pathlib import Path

from coverage_flow import build_haven_prompt
from prompt_context import paired_feedback
from prompt_rag import load_corpus, retrieve_diverse, render_hits
from sequence_experiment import build_prompt, retrieval_queries, DEFAULT_RAG_CORPUS, design_evidence, prompt_json
from sequence_framework import load_design, render_binding
from task_context import TaskContext, INSTRUCTION
from run_records import save, utc
from rvprobe_skill import snapshot


def main():
    from haven.dsl.schema import DSLSequenceSet
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tracking', type=Path, required=True)
    parser.add_argument('--haven-root', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    tracking = json.loads(args.tracking.read_text())
    args.out.mkdir(parents=True, exist_ok=False)
    _, docs = load_corpus(DEFAULT_RAG_CORPUS)
    rag = render_hits(retrieve_diverse(retrieval_queries(), docs, 6))
    template = (args.haven_root/'src/haven/prompts/gen_sequence_dsl_gap.md').read_text()
    rows = []
    for name, attempts in tracking['attempts'].items():
        paired = Path(attempts[-1]).parent
        bundle_path = next(p for p in (paired.parent/'shared/bundle.json', paired/'shared/bundle.json') if p.exists())
        bundle = json.loads(bundle_path.read_text())
        replay = Path(bundle['replay_config'])
        design = load_design(replay.parent/json.loads(replay.read_text())['design'])
        if design.record() != bundle['design']:
            raise ValueError(f'{name}: frozen design changed')
        contexts = {}
        feedbacks = {}
        for arm in ('haven', 'rvprobe'):
            feedback_path = paired/arm/'round-1/feedback.json'
            feedback = paired_feedback(json.loads(feedback_path.read_text()) if feedback_path.exists() else {}, bundle)
            feedbacks[arm] = feedback
            contexts[arm] = TaskContext(design, feedback)
        prompts = {
            'rvprobe': build_prompt([], design.sources[0], '30s', rag, design=design,
                coverage_feedback=feedbacks['rvprobe'], sequences_per_intent=4, skill_context=True),
            'haven': build_haven_prompt(template, design, feedbacks['haven'],
                bundle['initial_dsl'], bundle['initial_dsl'], DSLSequenceSet.model_json_schema())}
        for arm, prompt in prompts.items():
            if arm == 'haven':
                if INSTRUCTION in prompt or design_evidence(design) not in prompt or prompt_json(feedbacks[arm]) not in prompt:
                    raise ValueError(f'{name}: HAVEN must retain inline evidence without task tools')
                continue
            if design.context not in prompt or INSTRUCTION not in prompt:
                raise ValueError(f'{name}/{arm}: missing specification or tool instructions')
            if any(p.name not in prompt for p in design.ports):
                raise ValueError(f'{name}/{arm}: missing IO port')
            for file_id, entry in contexts[arm].files.items():
                original = ''.join(entry['lines'])
                if len(original.strip()) > 100 and original in prompt:
                    raise ValueError(f'{name}/{arm}: RTL body in initial prompt')
                offset, parts = 0, []
                while offset is not None:
                    page = contexts[arm].dispatch('read_rtl', {'file_id': file_id, 'offset': offset})
                    parts.append(page['text'])
                    offset = page['next_offset']
                if ''.join(parts) != entry['text']:
                    raise ValueError(f'{name}/{arm}: lossy RTL paging')
        for topic in ('baseline', 'environment'):
            if contexts['haven'].topics[topic] != contexts['rvprobe'].topics[topic]:
                raise ValueError(f'{name}: different shared {topic}')
        if render_binding(design) not in prompts['rvprobe']:
            raise ValueError(f'{name}: RVProbe binding missing')
        row = {'design': name, 'rtl_files': len(contexts['rvprobe'].files), 'passed': True,
               'new_prompt_characters': {k: len(v) for k, v in prompts.items()}, 'old_prompt_characters': {}}
        for arm in prompts:
            suffix = 'generation/attempt-1/prompt.txt' if arm == 'rvprobe' else 'attempt-1/prompt.txt'
            old = paired/arm/'round-1'/suffix
            if old.exists():
                row['old_prompt_characters'][arm] = len(old.read_text())
                if arm == 'haven':
                    saved_feedback = json.loads((paired/arm/'round-1/feedback.json').read_text())
                    restored = build_haven_prompt(template, design, saved_feedback,
                        bundle['initial_dsl'], bundle['initial_dsl'], DSLSequenceSet.model_json_schema())
                    row['haven_exact_saved_prompt_match'] = restored == old.read_text()
        save(args.out/name/'prompts.json', {
            'scope': 'offline rebuilt prompts, not submitted to a model', 'skill': snapshot(),
            'prompts': prompts, 'task_access': {'rvprobe': contexts['rvprobe'].record()},
            'haven_access': 'full-inline-unchanged'})
        rows.append(row)
        print(json.dumps(row), flush=True)
    save(args.out/'summary.json', {'created_utc': utc(), 'new_llm_requests': 0,
        'scope': 'initial prompt boundary and lossless on-demand RTL read audit; not measured token savings',
        'designs': rows})


if __name__ == '__main__':
    main()
