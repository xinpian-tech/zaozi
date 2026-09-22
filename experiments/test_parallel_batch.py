"""Parallel scheduling, environment packaging and archival without models/EDA."""
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from types import SimpleNamespace

from haven_design_batch import archive_design, bounded_designs, main
from merge_stage1_environment import merge_maps, package
from run_records import save
from frozen_stage1 import load_fixed_setup, verify_fixed_setup
import test_frozen_stage1 as fixtures


class ParallelBatchTests(unittest.TestCase):
    def test_three_concurrent_slots_and_all_designs_once(self):
        barrier = threading.Barrier(3)
        lock = threading.Lock()
        live = maximum = 0
        seen = []
        def worker(name):
            nonlocal live, maximum
            with lock:
                live += 1
                maximum = max(maximum, live)
                seen.append(name)
            barrier.wait(timeout=10)
            with lock:
                live -= 1
        pending, error = bounded_designs(range(6), worker, 3)
        self.assertEqual(maximum, 3)
        self.assertEqual(sorted(seen), list(range(6)))
        self.assertEqual((pending, error), ([], None))

    def test_archive_failure_stops_new_admission(self):
        seen = []
        def worker(name):
            seen.append(name)
            return 'archive unavailable'
        pending, error = bounded_designs(range(5), worker, 1)
        self.assertEqual(seen, [0])
        self.assertEqual(pending, [1, 2, 3, 4])
        self.assertEqual(error, 'archive unavailable')

    def test_low_space_does_not_start_worker(self):
        def admit(active):
            raise OSError('insufficient scratch space')
        with patch('haven_design_batch.run') as run:
            pending, error = bounded_designs(['alu'], run, 3, admit)
            run.assert_not_called()
        self.assertEqual(pending, ['alu'])
        self.assertIn('scratch', error)

    def test_archive_both_success_and_early_failure_then_release_scratch(self):
        for status in ('completed', 'failed'):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp); work = root/'work'; archive = root/'archive'
                save(work/'tokens.json', {'tokens': 123})
                archive_design(work, archive, {'status': status}, True)
                self.assertTrue(work.is_symlink())
                self.assertEqual(json.loads((work/'tokens.json').read_text()), {'tokens': 123})
                self.assertEqual(json.loads((archive/'design-result.json').read_text())['status'], status)

    def test_bad_archive_keeps_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)/'work'; archive = Path(tmp)/'archive'
            save(work/'important.json', {'data': 1})
            with patch('experiment_storage.archive_tree'):
                with self.assertRaises(ValueError):
                    archive_design(work, archive, {'status': 'failed'}, True)
            self.assertFalse(work.is_symlink())
            self.assertTrue((work/'important.json').exists())

    def test_batch_isolates_tmp_and_continues_after_model_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            record = fixtures.FrozenStage1Tests().fixture(root)
            mapping = root/'map.json'
            save(mapping, {'alu': str(record), 'aes': str(record), 'sha3': str(record)})
            barrier = threading.Barrier(3)
            temporaries = []
            def fake_run(argv, **kwargs):
                flow = Path(argv[argv.index('--out')+1])
                self.assertEqual(argv[argv.index('--rvprobe-max-tokens')+1],'393216')
                self.assertEqual(argv[argv.index('--rvprobe-request-timeout')+1],'3600')
                self.assertEqual(argv[argv.index('--rvprobe-reasoning-effort')+1],'max')
                self.assertEqual(argv[argv.index('--rvprobe-model')+1],'deepseek-v4-flash')
                self.assertEqual(argv[argv.index('--sequences-per-intent')+1],'8')
                temporaries.append(kwargs['env']['TMPDIR'])
                barrier.wait(timeout=10)
                status = 'failed' if flow.parent.name == 'aes' else 'completed'
                save(flow/'progress.json', {'status': status})
                return SimpleNamespace(returncode=1 if status == 'failed' else 0)
            with patch('haven_design_batch.run', side_effect=fake_run), patch.dict('os.environ'), patch('sys.argv', [
                    'batch', '--haven-root', str(root/'haven'), '--env-file', str(root/'unused.env'),
                    '--out', str(root/'work'), '--archive-root', str(root/'archive'),
                    '--relocate-completed', '--jobs', '3', '--arm', 'both',
                    '--rvprobe-max-tokens','393216','--rvprobe-request-timeout','3600',
                    '--rvprobe-model','deepseek-v4-flash','--sequences-per-intent','8',
                    '--stage1-map', str(mapping), '--designs', 'alu', 'aes', 'sha3']):
                main()
            self.assertEqual(len(set(temporaries)), 3)
            result = json.loads((root/'archive/summary.json').read_text())
            self.assertEqual(result['status'], 'finished_with_failures')
            self.assertEqual(result['models'],{'rvprobe':'deepseek-v4-flash',
                                               'haven':'deepseek-v4-flash-vision-exp'})
            self.assertEqual(result['sequences_per_intent'],8)
            for name, item in result['designs'].items():
                self.assertEqual(item['storage_status'], 'relocated')
                self.assertTrue((root/'work'/name).is_symlink())
                self.assertEqual(Path(root/'work'/name/'tmp'), Path(next(p for p in temporaries if f'/{name}/' in p)))


class PackageTests(unittest.TestCase):
    def test_later_map_wins_and_relative_records_resolve_from_map(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            save(root/'a.json', {'alu': 'old.json', 'aes': 'aes.json'})
            save(root/'b.json', {'alu': 'new.json'})
            result = merge_maps([root/'a.json', root/'b.json'])
            self.assertEqual(result, {'alu': str(root/'new.json'), 'aes': str(root/'aes.json')})

    def test_packaged_artifacts_preserve_bytes_and_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); record = fixtures.FrozenStage1Tests().fixture(root)
            save(root/'map.json', {'alu': str(record)})
            original, identity = load_fixed_setup(record)
            with patch('merge_stage1_environment.check_contract') as check:
                mapping = package([root/'map.json'], root/'packaged', designs=['alu'])
                check.assert_called_once()
            setup, packaged = load_fixed_setup(mapping['alu'])
            verify_fixed_setup(identity); verify_fixed_setup(packaged)
            self.assertEqual(identity['haven_sha256'], packaged['haven_sha256'])
            self.assertNotEqual(setup['stage1'], original['stage1'])
            self.assertEqual((Path(setup['stage1'])/'final/sequence_1.sv').read_bytes(),
                             (Path(original['stage1'])/'final/sequence_1.sv').read_bytes())
            self.assertTrue((root/'packaged/stage1-map.json').exists())

    def test_failed_contract_does_not_publish_package(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); record = fixtures.FrozenStage1Tests().fixture(root)
            save(root/'map.json', {'alu': str(record)})
            with patch('merge_stage1_environment.check_contract', side_effect=ValueError('legacy item')):
                with self.assertRaisesRegex(ValueError, 'legacy item'):
                    package([root/'map.json'], root/'packaged', designs=['alu'])
            self.assertFalse((root/'packaged/stage1-map.json').exists())


if __name__ == '__main__':
    unittest.main()
