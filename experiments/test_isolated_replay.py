import unittest
from sequence_framework import ROOT, load_design
from cycle_replay import frame
from haven_shared import render_witness_sequence
from isolated_replay import independent_batches, IsolatedSimulation, CandidateBatchFailure, diagnostic_excerpt
from pathlib import Path
from types import SimpleNamespace
from replay_failures import ReplayInfrastructureFailure


class IsolationTests(unittest.TestCase):
    def test_saved_simulation_sources_are_verified_before_replay(self):
        import json
        import tempfile
        from run_records import fingerprint
        from replay_saved_candidate import load_saved_simulation
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root/'sequence_1.sv'
            source.write_text('class generic_saved_sequence; endclass\n')
            (root/'inputs.json').write_text(json.dumps({'sequences':[fingerprint(source.read_text())]}))
            (root/'schedule.json').write_text('[]')
            self.assertEqual(load_saved_simulation(root),
                             {'sequences':[source.read_text()], 'frames':[]})
            (root/'schedule.json').write_text('{}')
            with self.assertRaisesRegex(ValueError, 'schedule must be an array'):
                load_saved_simulation(root)
            (root/'schedule.json').write_text('[]')
            source.write_text('class altered; endclass')
            with self.assertRaisesRegex(ValueError, 'sequence hash differs'):
                load_saved_simulation(root)

    def setUp(self):
        self.design = load_design(ROOT / 'experiments/tests/fixtures/tiny_design.json')
        self.bundle = {'sequences': ['baseline']}
        self.rows = [frame('witness', {n: 0 for n in self.design.drive_names}, segment=0)]

    def test_native_sequences_are_independent_for_both_arms(self):
        self.assertEqual(independent_batches(self.bundle, self.design, ['baseline', 'a', 'b'], []),
                         [(['baseline'], []), (['a'], []), (['b'], [])])

    def test_baseline_uses_the_same_fresh_process_policy_without_dropping_tests(self):
        bundle = {'sequences':['setup_with_side_effects','independent_check']}
        self.assertEqual(independent_batches(bundle,self.design,
            bundle['sequences']+['new_intent'],[]),
            [(['setup_with_side_effects'],[]),(['independent_check'],[]),(['new_intent'],[])])

    def test_witness_ordinals_restart_without_dropping_checks(self):
        later = [{**row, 'segment': 1} for row in self.rows]
        first = render_witness_sequence(self.design, self.rows, 'first', 0)
        second = render_witness_sequence(self.design, later, 'second', 1)
        batches = independent_batches(self.bundle, self.design,
            ['baseline', first, second], self.rows + later)
        self.assertEqual(batches[2], ([render_witness_sequence(self.design, later, 'second', 0)], later))

    def test_modified_raw_source_is_not_silently_replaced(self):
        source = render_witness_sequence(self.design, self.rows, 'first', 0)
        with self.assertRaisesRegex(ValueError, 'differs'):
            independent_batches(self.bundle, self.design, ['baseline', source + '\n// modified'], self.rows)

    def test_saved_later_round_rebases_only_verified_global_ordinals(self):
        from replay_saved_candidate import normalize_saved_ordinals
        rows=[{**self.rows[0],'segment':42}]
        source=render_witness_sequence(self.design,rows,'later_round',42)
        original={'sequences':[source],'frames':rows}
        fixed,start=normalize_saved_ordinals(original,self.design)
        self.assertEqual(start,42)
        self.assertEqual(original['sequences'],[source])
        self.assertEqual(fixed['frames'],rows)
        self.assertEqual(fixed['sequences'],[render_witness_sequence(self.design,rows,'later_round',0)])
        independent_batches(self.bundle,self.design,['baseline',*fixed['sequences']],rows)
        with self.assertRaisesRegex(ValueError,'differs'):
            normalize_saved_ordinals({**original,'sequences':[source+'\n// modified']},self.design)
        isolated={'sequences':[render_witness_sequence(self.design,rows,'later_round',0)],'frames':rows}
        self.assertEqual(normalize_saved_ordinals(isolated,self.design,already_isolated=True),(isolated,0))
        with self.assertRaisesRegex(ValueError,'differs'):
            normalize_saved_ordinals(original,self.design,already_isolated=True)

    def test_missing_frames_and_changed_baseline_fail(self):
        source = render_witness_sequence(self.design, self.rows, 'first', 0)
        for sequences, frames in [(['baseline', source], []), (['baseline'], self.rows), (['other'], [])]:
            with self.assertRaises(ValueError):
                independent_batches(self.bundle, self.design, sequences, frames)

    def test_reused_non_contiguous_segment_fails(self):
        rows = self.rows + [{**self.rows[0], 'segment': 1}] + self.rows
        with self.assertRaisesRegex(ValueError, 'non-contiguous'):
            independent_batches(self.bundle, self.design, ['baseline'], rows)

    def test_all_candidate_failures_are_reported_without_partial_acceptance(self):
        class Failure(ValueError):
            diagnostics={'model_repair_allowed':True,'kind':'candidate','log':'saved.log'}
        class Simulator:
            bundle={'sequences':['baseline'],'fingerprint':'fixed'}
            seed=1; config={}
            def __init__(self): self.calls=[]
            def __call__(self,path,sources,rows):
                self.calls.append(sources[0])
                if sources[0].startswith('bad'): raise Failure('measured defect')
                return {'artifact_sha256':{}}
        simulator=Simulator(); isolated=IsolatedSimulation(simulator,Path('/unused-cache'))
        batches=[([name],[]) for name in ('baseline','bad1','good','bad2')]
        for _ in range(2):
            with self.assertRaises(CandidateBatchFailure) as caught: isolated.evaluate(batches)
            self.assertEqual(caught.exception.diagnostics['failed_sequences'],2)
            self.assertEqual(len(caught.exception.diagnostics['failures']),2)
        self.assertEqual(simulator.calls,['baseline','bad1','good','bad2'])

    def test_infrastructure_and_baseline_failures_are_not_aggregated(self):
        class Simulator:
            bundle={'sequences':['baseline'],'fingerprint':'fixed'}
            seed=1; config={}
            def __init__(self,fail): self.calls=[]; self.fail=fail
            def __call__(self,path,sources,rows):
                self.calls.append(sources[0])
                if sources[0]==self.fail: raise ReplayInfrastructureFailure('broken transport')
                return {'artifact_sha256':{}}
        for failed,expected in [('baseline',['baseline']),('broken',['baseline','broken'])]:
            simulator=Simulator(failed); isolated=IsolatedSimulation(simulator,Path('/unused-cache'))
            with self.assertRaises(ReplayInfrastructureFailure):
                isolated.evaluate([([name],[]) for name in ('baseline','broken','later')])
            self.assertEqual(simulator.calls,expected)

    def test_native_selection_and_final_evaluation_share_verified_cache(self):
        class Simulator:
            bundle={'sequences':[],'fingerprint':'fixed'}
            seed=1; config={}
            def __init__(self):self.calls=0
            def __call__(self,path,sources,rows):
                self.calls+=1
                return {'artifact_sha256':{}}
        simulator=Simulator()
        isolated=IsolatedSimulation(simulator,Path('/unused-cache'))
        rows=[{'segment':23,'kind':'witness'}]
        target, result=isolated.measure_one(['frozen source'],rows)
        databases,results=isolated.evaluate([(['frozen source'],rows)])
        self.assertEqual(databases,[target/'simv.vdb'])
        self.assertEqual(results,[result])
        self.assertEqual(simulator.calls,1)

    def test_compiler_error_context_is_preserved_without_banner(self):
        excerpt=diagnostic_excerpt('copyright banner\nUVM_ERROR : 0\nError-[MFNF] Member not found\nsequence.sv, 12\nmissing_field\n')
        self.assertIn('missing_field',excerpt)
        self.assertNotIn('copyright',excerpt)
        self.assertNotIn('UVM_ERROR : 0',excerpt)
