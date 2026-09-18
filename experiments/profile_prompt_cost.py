#!/usr/bin/env python3
"""Offline prompt regression on saved first attempts: no model or EDA calls.

Measure exact text sizes, not estimated provider tokens or monetary savings.
IO stays inline; framework references and RTL are read on demand. With --bundle, apply the
explicit common-evidence projection to both arms' shared context. Historical files
are read-only; removed derived artifacts are NOT claimed to be losslessly retained.
"""
import argparse
import hashlib
import json
from pathlib import Path

from sequence_framework import load_design, render_binding
from sequence_experiment import io_contract, build_prompt, prompt_sections, design_evidence, framework_catalog
from task_context import TaskContext
from prompt_rag import RagHit, render_hits
from run_records import save, framework_hashes
from sequence_framework import ROOT
from prompt_context import paired_feedback, POLICY


def profile(directory, design, bundle=None):
    directory = Path(directory)
    manifest = json.loads((directory/'manifest.json').read_text())
    if manifest['design'] != design.record():
        raise ValueError('saved design differs from the current manifest or RTL')
    hits = [RagHit(**hit) for hit in json.loads((directory/'rag.json').read_text())['retrieved']]
    old = (directory/'attempt-1/prompt.txt').read_text()
    feedback = manifest.get('feedback')
    if bundle is not None:
        if bundle['design'] != design.record():
            raise ValueError('bundle design differs from the saved generation')
        old_shared = (feedback or {}).get('shared_context', {})
        # Verify provenance before replacing legacy context; don't substitute another baseline.
        if old_shared.get('policy') == POLICY:
            if old_shared != paired_feedback({}, bundle)['shared_context']:
                raise ValueError('bundle differs from the saved shared context')
        elif any(old_shared.get(key) != bundle[bundle_key] for key, bundle_key in (
            ('initial_sequences', 'sequences'), ('structured_spec', 'structured_spec'),
            ('blueprint', 'blueprint'), ('protocol_flows', 'protocol_flows'))):
            raise ValueError('bundle differs from the saved legacy shared context')
        feedback = paired_feedback(feedback, bundle)
    new = build_prompt(json.loads((directory/'residual.json').read_text()), design.sources[0],
                       manifest['jg_time_limit'], framework_catalog(hits), design=design,
                       coverage_feedback=feedback,
                       sequences_per_intent=manifest['sequences_per_intent'], skill_context=True)
    if not (render_binding(design) in old or io_contract(design) in old) or io_contract(design) not in new:
        raise ValueError('historical or new prompt lacks the current IO contract')
    # Historical catalogs may describe the removed UT API; never offer them to the new model.
    if any(h.source not in ("docs/zaozi-ltl-api.md", "utlib/src/Gen.scala") for h in hits):
        raise ValueError('historical RAG uses a different authoring contract; use a current LTL-only run')
    if design_evidence(design) in new:
        raise ValueError('new initial prompt must not contain full RTL')
    # One actual task prompt versus one new task prompt. Older profilers incorrectly
    # assumed every historical run sent the task twice, including light-bootstrap runs.
    # Exclude bootstrap/tool payloads and retries; these are NOT full-request savings.
    before, after = len(old), len(new)
    initial=TaskContext(design,feedback,framework=hits).initial_evidence()
    return {'run': str(directory.resolve()), 'old_prompt_sha256': hashlib.sha256(old.encode()).hexdigest(),
            'new_prompt_sha256': hashlib.sha256(new.encode()).hexdigest(),
            'old_task_characters': len(old), 'new_task_characters': len(new),
            'new_initial_evidence_characters':len(json.dumps(initial,ensure_ascii=False,separators=(',',':'))),
            'bootstrap_characters': 0,
            'task_prompt_reduction_percent': 100*(before-after)/before,
            'common_context_policy': POLICY if bundle is not None else 'unchanged',
            'feedback_sha256': hashlib.sha256(json.dumps(feedback, sort_keys=True).encode()).hexdigest(),
            'old_sections': prompt_sections(old), 'new_sections': prompt_sections(new),
            'io_preserved': True, 'new_output_contract':'runtime-ltl-v3', 'framework_access':'on-demand', 'rtl_access': 'on-demand',
            'excluded_costs': ['bootstrap', 'skill', 'task tool exchanges', 'retries', 'model output']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--design', required=True, type=Path)
    parser.add_argument('--generation', required=True, type=Path, nargs='+')
    parser.add_argument('--bundle', type=Path, help='frozen shared bundle for current common-evidence projection')
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    if args.out.exists() or any(args.out.resolve().is_relative_to(p.resolve()) for p in args.generation) or (
            args.bundle and args.out.resolve().is_relative_to(args.bundle.resolve().parent)):
        parser.error('output must be new and outside historical generation directories')
    design = load_design(args.design)
    bundle = json.loads(args.bundle.read_text()) if args.bundle else None
    rows = [profile(p, design, bundle) for p in args.generation]
    save(args.out, {'scope': 'offline text-size regression, not measured token savings or coverage efficacy',
                    'new_llm_requests': 0, 'framework': framework_hashes(ROOT), 'runs': rows})
    print(json.dumps([{k:v for k,v in row.items() if k.endswith('characters') or k.endswith('percent') or k=='run'}
                      for row in rows], indent=2))


if __name__ == '__main__':
    main()
