"""Core backend contract tests; real EDA regressions are separate and offline."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import backend_imports
from rvprobe.backend import runtime
from rvprobe.backend.encoding import EncodingOptions
from rvprobe.backend.records import fingerprint
from rvprobe.backend.failures import ReplayInfrastructureFailure


class WitnessBackendTest(unittest.TestCase):
    def test_experiment_without_native_transport_stops_before_any_model_call(self):
        from coverage_flow import Backends
        backends = Backends.__new__(Backends)
        backends.args = object()
        backends.bundle = {}
        backends.replay = {}
        backends.native_witness_simulator = None
        with tempfile.TemporaryDirectory() as temp, \
                patch('coverage_flow.paired_feedback', return_value={}), \
                patch('coverage_flow.run_process') as model:
            with self.assertRaisesRegex(ReplayInfrastructureFailure,'before model'):
                backends('rvprobe',Path(temp)/'round-1',{},[],0)
            model.assert_not_called()

    def goal(self, root, label='target'):
        import hashlib
        root.mkdir(parents=True,exist_ok=True)
        source = f'module UT(input clock, reset, a); {label}: cover property (@(posedge clock) a); endmodule'
        original = root/'original.sv'
        original.write_text(source)
        selected = root/label/'selected'
        selected.mkdir(parents=True)
        (selected/'UT.sv').write_text(source)
        (root/'prepared.json').write_text(json.dumps(dict(sv=str(original),
            svSha256=hashlib.sha256(source.encode()).hexdigest(), sourceSha256='author',
            fingerprint='job', labels=[label], top='UT')))
        return dict(status='generated', label=label, generationLabel=label,
            witnessFile=str(root/label/'jg/witness.vcd'), utModule='UT',
            utSourceSha256='author', fingerprint='job', inputFingerprint='original')

    def receipt(self, frames):
        meta = frames[0]['ltl']
        return dict(passed=True, ltl=dict(policy=meta['policy'], label=meta['label'],
            source_sha256=meta['sha256'], native_cover_hit=True, four_state_simulation=True))

    def transport(self, measure=None):
        return runtime.ReplayTransport(
            frames=lambda row, segment: [dict(segment=segment, drive={'a':row.get('a',0)})],
            render=lambda rows, name, offset: json.dumps(dict(name=name, frames=rows, ordinal=offset)),
            measure=measure or (lambda source, frames: self.receipt(frames)))

    def test_returns_validated_sequence_and_never_calls_unused_providers(self):
        forbidden = Mock(side_effect=AssertionError('unused solver'))
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            backend = runtime.WitnessBackend(self.transport(), forbidden, forbidden)
            result = backend.generate([self.goal(root)], root/'backend', 'run', 4, ordinal=7)
            self.assertEqual(len(result['sequences']),1)
            self.assertEqual(result['frames'][0]['segment'],7)
            self.assertEqual(json.loads(result['sequences'][0])['ordinal'],7)
            report = json.loads((root/'backend/backend.json').read_text())
            self.assertEqual(report['status'],'passed')
            self.assertEqual(report['schedule_sha256'],fingerprint(result['frames']))
            self.assertEqual(report['sampling'][0]['sampling_shortfall'],3)
        forbidden.assert_not_called()

    def test_no_unchecked_or_mismatched_receipts(self):
        for receipt in (None, {'passed':True}, {'passed':True, 'ltl':{'native_cover_hit':True}}):
            with self.subTest(receipt=receipt), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                backend = runtime.WitnessBackend(self.transport(lambda *args: receipt), Mock())
                with self.assertRaisesRegex(ReplayInfrastructureFailure,'receipt'):
                    backend.generate([self.goal(root)],root/'backend','run',1)
                self.assertEqual(json.loads((root/'backend/backend.json').read_text())['status'],'failed')

    def test_mutating_transport_is_not_a_new_candidate(self):
        def measure(source, frames):
            frames[0]['drive']['a'] = 123
            return self.receipt(frames)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaisesRegex(ReplayInfrastructureFailure,'mutated'):
                runtime.WitnessBackend(self.transport(measure), Mock()).generate(
                    [self.goal(root)],root/'backend','run',1)

    def test_unknown_rejection_uses_known_state_and_preserves_the_original_goal(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            goal = self.goal(root)
            before = fingerprint(goal)
            checks = root/'checks.json'
            checks.write_text(json.dumps({'waveform_differences':[{'kind':'output','mask':255,'known':0}]}))
            def measure(source, frames):
                if frames[0]['drive']['a'] == 0:
                    error = ValueError('unknown state')
                    error.diagnostics = dict(kind='formal_replay_semantics_mismatch',replay_checks=str(checks))
                    raise error
                return self.receipt(frames)
            no_resample = Mock(side_effect=AssertionError('must escalate'))
            auxiliary = Mock(return_value=[dict(goal, inputFingerprint='known', a=1)])
            result = runtime.WitnessBackend(self.transport(measure), no_resample, auxiliary).generate(
                [goal],root/'backend','run',1)
            self.assertEqual(result['frames'][0]['drive']['a'],1)
            self.assertEqual(fingerprint(goal),before)
            self.assertEqual(result['frames'][0]['ltl']['label'],'target')
            no_resample.assert_not_called()
            auxiliary.assert_called_once()

    def test_native_miss_is_explicit_and_never_emitted(self):
        def miss(*args):
            error = ValueError('native cover missed')
            error.diagnostics = dict(kind='formal_replay_semantics_mismatch')
            raise error
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            result=runtime.WitnessBackend(self.transport(miss),lambda *args: []).generate(
                [self.goal(root)],root/'backend','run',1)
            self.assertEqual(result['sequences'],[])
            self.assertFalse(result['metadata']['all_intents_satisfied'])
            self.assertEqual(result['metadata']['unresolved_intents'],['target'])
            self.assertEqual(result['metadata']['status'],'completed_with_shortfalls')

    def test_failed_first_intent_does_not_erase_or_block_valid_later_intents(self):
        observed=[]
        def measure(source,frames):
            label=frames[0]['ltl']['label'];observed.append(label)
            if label=='unresolved':
                error=ValueError('native cover missed')
                error.diagnostics={'kind':'formal_replay_semantics_mismatch'}
                raise error
            return self.receipt(frames)
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            goals=[self.goal(root/name,name) for name in ('unresolved','good','later')]
            result=runtime.WitnessBackend(self.transport(measure),lambda *args: []).generate(
                goals,root/'backend','run',1,ordinal=7)
            saved=json.loads((root/'backend/accepted.json').read_text())
        self.assertEqual(observed,['unresolved','good','later'])
        self.assertEqual([f['ltl']['label'] for f in result['frames']],['good','later'])
        self.assertEqual([f['segment'] for f in result['frames']],[7,8])
        self.assertEqual(result['metadata']['unresolved_intents'],['unresolved'])
        self.assertFalse(result['metadata']['all_intents_satisfied'])
        self.assertEqual(saved['sequences'],result['sequences'])
        self.assertTrue(saved['all_intents_processed'])

    def test_partial_pool_retains_only_native_valid_receipts(self):
        def measure(source,frames):
            if frames[0]['drive']['a']:
                error=ValueError('native cover missed')
                error.diagnostics={'kind':'formal_replay_semantics_mismatch'}
                raise error
            return self.receipt(frames)
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);goal=self.goal(root)
            goal['sequences']=[dict(goal),dict(goal,inputFingerprint='bad',a=1)]
            result=runtime.WitnessBackend(self.transport(measure),lambda *args: []).generate(
                [goal],root/'backend','run',2)
        self.assertEqual(len(result['sequences']),1)
        self.assertEqual(result['metadata']['partial_intents'],['target'])
        self.assertEqual(result['metadata']['sampling'][0]['actual'],1)
        self.assertEqual(result['metadata']['sampling'][0]['status'],'partial')
        self.assertEqual(result['frames'][0]['drive']['a'],0)

    def test_later_infrastructure_error_still_aborts_but_preserves_verified_prefix(self):
        def measure(source,frames):
            if frames[0]['ltl']['label']=='broken':
                raise ReplayInfrastructureFailure('driver pin mapping changed')
            return self.receipt(frames)
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            goals=[self.goal(root/name,name) for name in ('good','broken','not_attempted')]
            with self.assertRaisesRegex(ReplayInfrastructureFailure,'pin mapping'):
                runtime.WitnessBackend(self.transport(measure),lambda *args: []).generate(
                    goals,root/'backend','run',1)
            saved=json.loads((root/'backend/accepted.json').read_text())
            self.assertEqual(len(saved['sequences']),1)
            self.assertEqual(saved['frames'][0]['ltl']['label'],'good')
            self.assertFalse(saved['all_intents_processed'])
            self.assertEqual(json.loads((root/'backend/backend.json').read_text())['status'],'failed')

    def test_missing_transport_and_unsafe_names_fail_closed(self):
        with self.assertRaises(ReplayInfrastructureFailure):
            runtime.WitnessBackend(None, Mock())
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            with self.assertRaisesRegex(ValueError,'namespace'):
                runtime.WitnessBackend(self.transport(),Mock()).generate(
                    [self.goal(root)],root/'backend','../bad',1)

    def test_invalid_encoder_limits_rejected_by_library_not_only_cli(self):
        base=dict(out=Path('out'),yosys=Path('yosys'),eda_shell=Path('eda'),label='target')
        for change in ({'jg_time_limit':'forever'}, {'engine_mode':'invalid'}, {'trace_cycles':0}, {'trace_preference':'anything'},
                       {'avoid_stimulus':(Path('old'),)}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                EncodingOptions(**base,**change)

    def test_core_imports_without_experiments_haven_or_site_packages(self):
        code = '''import importlib, pathlib, sys
import rvprobe.backend
for path in pathlib.Path(rvprobe.backend.__file__).parent.glob('*.py'):
    if path.stem != '__init__': importlib.import_module('rvprobe.backend.' + path.stem)
assert not any(name in sys.modules for name in ('coverage_flow', 'sequence_experiment', 'haven', 'backend_imports'))
'''
        with tempfile.TemporaryDirectory() as temp:
            result = subprocess.run([sys.executable,'-S','-c',code],cwd=temp,
                env={**os.environ,'PYTHONPATH':str(backend_imports.ROOT)},capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)


if __name__ == '__main__':
    unittest.main()
