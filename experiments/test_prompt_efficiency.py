import json
import unittest
import tempfile
from pathlib import Path
from dataclasses import replace
from sequence_framework import ROOT, load_design, render_binding
from sequence_experiment import io_contract, build_prompt, prompt_sections, prompt_json, design_evidence
from profile_prompt_cost import profile
from run_records import save
from task_context import TaskContext


class PromptEfficiencyTests(unittest.TestCase):
    def setUp(self):
        self.design = load_design(ROOT/'experiments/tests/fixtures/tiny_design.json')

    def test_compact_mode_keeps_api_examples_and_exposes_evidence_on_demand(self):
        feedback = {'gaps': [{'type': 'toggle', 'evidence': 'original evidence\n中文'}],
                    'shared_context': {'initial_sequences': ['source  a\n  b']}}
        kwargs = dict(design=self.design, coverage_feedback=feedback, rag_context='EXACT_RAG_EXAMPLE\n1  2',
                      sequences_per_intent=4)
        full = build_prompt([], self.design.sources[0], '30s', **kwargs)
        compact = build_prompt([], self.design.sources[0], '30s', **kwargs, skill_context=True)
        self.assertEqual(compact, full)
        for evidence in [io_contract(self.design), self.design.context, kwargs['rag_context']]:
            self.assertIn(evidence, compact)
        self.assertNotIn(design_evidence(self.design), compact)
        self.assertNotIn('original evidence', compact)
        for rule in ['no Assume', 'raw Scala LTL',
                     'up to 4 distinct sequences per intent', 'no imports', 'read_rtl', 'read_context']:
            self.assertIn(rule, compact)
        context = TaskContext(self.design, feedback)
        self.assertEqual(json.loads(context.topics['coverage'])['gaps'], feedback['gaps'])
        self.assertEqual(json.loads(context.topics['environment'])['shared_context'], feedback['shared_context'])
        self.assertEqual(sum(s['characters'] for s in prompt_sections(compact)), len(compact))
        self.assertEqual(sum(s['utf8_bytes'] for s in prompt_sections(compact)), len(compact.encode()))

    def test_json_compaction_never_changes_whitespace_inside_source(self):
        value = {'source': ' a  b\n  中文\t', 'data': [True, None, 2]}
        self.assertEqual(json.loads(prompt_json(value)), value)

    def test_task_headings_are_not_mistaken_for_template_boundaries(self):
        context = '# Decision procedure\nretain this task evidence\n# Evidence boundary\n'
        design = replace(self.design, context=context)
        prompt = build_prompt([], design.sources[0], '30s', design=design, skill_context=True)
        self.assertIn(context, prompt)
        self.assertIn(io_contract(design), prompt)

    def test_repair_diagnostics_and_previous_source_are_preserved(self):
        previous = 'EXACT_PREVIOUS_SOURCE\n  indent'
        errors = {'diagnostic': 'EXACT_ERROR'}
        prompt = build_prompt([], self.design.sources[0], '30s', design=self.design,
                              errors=errors, previous=previous, skill_context=True)
        self.assertIn(previous, prompt)
        self.assertIn("EXACT_ERROR", prompt)

    def test_profiler_does_not_double_count_light_bootstrap_task(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            save(root / 'manifest.json', {'design': self.design.record(), 'jg_time_limit': '30s',
                 'sequences_per_intent': 4, 'skill_protocol': 'light-bootstrap-then-task-v1'})
            save(root / 'rag.json', {'retrieved': []})
            save(root / 'residual.json', [])
            from sequence_experiment import framework_catalog
            prompt = build_prompt([], self.design.sources[0], '30s', framework_catalog([]), design=self.design,
                                  sequences_per_intent=4, skill_context=True)
            (root / 'attempt-1').mkdir()
            (root / 'attempt-1/prompt.txt').write_text(prompt)
            before = {str(p): p.read_bytes() for p in root.rglob('*') if p.is_file()}
            result = profile(root, self.design)
            self.assertEqual(result['old_task_characters'], len(prompt))
            self.assertEqual(result['new_task_characters'], len(prompt))
            self.assertEqual(result['task_prompt_reduction_percent'], 0)
            self.assertEqual(before, {str(p): p.read_bytes() for p in root.rglob('*') if p.is_file()})
