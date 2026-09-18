import backend_imports
from copy import deepcopy
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from recover_rvprobe_quality import checked_manifest, paid_generation, restore_interrupted
from continue_rvprobe_quality import combine_summary
from run_records import begin, save, totals


class RecoveryTests(unittest.TestCase):
    def test_manifest_tampering_is_rejected(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)/'run'
            begin(root, dict(kind='rvprobe-quality-continuation-v1', total_rounds=6))
            self.assertEqual(checked_manifest(root/'manifest.json')['total_rounds'], 6)
            changed = json.loads((root/'manifest.json').read_text())
            changed['total_rounds'] = 7
            save(root/'manifest.json', changed)
            with self.assertRaisesRegex(ValueError, 'fingerprint'):
                checked_manifest(root/'manifest.json')

    def test_only_complete_paid_generation_for_same_design_is_reused(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            source = root/'attempt-1/sources'
            source.mkdir(parents=True)
            (source/'model.ltl').write_text('Gen(req ### ack)')
            event = dict(id='request-1', phase='model-request', status='completed',
                         usage=dict(prompt_tokens=100, completion_tokens=50, total_tokens=150))
            (root/'events.jsonl').write_text(json.dumps(event)+'\n')
            design = SimpleNamespace(record=lambda: {'top': 'dut'})
            good = dict(status='generated', model='fixed-model', design=design.record(),
                        result=dict(status='generated'), sources=str(source), costs=totals(root))
            save(root/'summary.json', good)
            _, provenance = paid_generation(root, 'fixed-model', design)
            self.assertEqual(provenance['remote_llm_requests'], 0)
            for key, value in [('model', 'wrong-model'), ('status', 'failed'),
                               ('design', {'top': 'another-dut'}), ('costs', {})]:
                bad = deepcopy(good)
                bad[key] = value
                save(root/'summary.json', bad)
                with self.subTest(key=key), self.assertRaises(ValueError):
                    paid_generation(root, 'fixed-model', design)

    def test_recovery_preserves_prefix_and_exact_simulated_inputs(self):
        with tempfile.TemporaryDirectory() as name:
            work = Path(name)
            cov1 = dict(score=90, replay=dict(passed=True))
            cov2 = dict(score=92, replay=dict(passed=True), artifact_sha256={'file':'hash'})
            rows = [dict(round=1, added_sequences=1, coverage=cov1),
                    dict(round=2, added_sequences=1, metadata={}, coverage=cov2)]
            progress = dict(status='running', bundle='frozen', arms=dict(rvprobe=dict(
                rounds=rows, active_round=3)))
            save(work/'progress.json', progress)
            rd = work/'rvprobe/round-2'
            save(rd/'candidate.json', dict(sequences=['new'], frames=[], metadata={}))
            save(rd/'simulation/batches.json', dict(databases=['cache/simv.vdb'],
                 runs=[dict(artifact_sha256={'file':'hash'})]))
            state = dict(rounds=[rows[0]], sequences=['old'], frames=[], baseline=cov1)
            expected = [(['actual'], [])]
            with patch('recover_rvprobe_quality.verify_artifacts'), \
                    patch('recover_rvprobe_quality.independent_batches', return_value=expected), \
                    patch('recover_rvprobe_quality.load_saved_simulation',
                          return_value=dict(sequences=['actual'], frames=[])) as loaded:
                restored = deepcopy(state)
                restore_interrupted(work, dict(total_rounds=3), dict(fingerprint='frozen'), None, restored)
                self.assertEqual(restored['current']['score'], 92)
                self.assertEqual(restored['sequences'], ['old', 'new'])
                loaded.return_value = dict(sequences=['modified'], frames=[])
                with self.assertRaisesRegex(ValueError, 'actually simulated'):
                    restore_interrupted(work, dict(total_rounds=3), dict(fingerprint='frozen'), None, deepcopy(state))
                progress['arms']['rvprobe']['rounds'][0]['added_sequences'] = 2
                save(work/'progress.json', progress)
                # state shares rows in this synthetic fixture; use a restored copy.
                state['rounds'][0]['added_sequences'] = 1
                with self.assertRaisesRegex(ValueError, 'inconsistent'):
                    restore_interrupted(work, dict(total_rounds=3), dict(fingerprint='frozen'), None, deepcopy(state))

    def test_all_paid_calls_remain_charged_once(self):
        with tempfile.TemporaryDirectory() as name:
            source = Path(name)
            save(source/'summary.json', {})
            save(source/'manifest.json', {})
            def cost(number):
                row = totals(source)
                row.update(requests=int(number>0), usage_reported=dict(
                    prompt_tokens=number, completion_tokens=0, total_tokens=number))
                return row
            old_arm = dict(costs=cost(147267), elapsed_seconds=10)
            incremental = dict(costs=cost(0), elapsed_seconds=2,
                               arms=dict(rvprobe=dict(costs=cost(0), elapsed_seconds=2)))
            extra = [dict(summary='failed', costs=cost(53800), elapsed_seconds=1),
                     dict(summary='interrupted', costs=cost(35386), elapsed_seconds=None)]
            result = combine_summary({}, incremental, source, old_arm, 233325,
                                     dict(framework_transition={}), extra)
            self.assertEqual(result['tokens'], 236453)
            self.assertFalse(result['cumulative_token_cap_met'])
            self.assertEqual(result['incremental_costs']['usage_reported']['total_tokens'], 0)


if __name__ == '__main__':
    unittest.main()
