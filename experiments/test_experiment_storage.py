import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from experiment_storage import archive_closed_simulation, archive_tree, require_space


class StorageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.work, self.archive = self.root/'work', self.root/'archive'
        self.sim = self.work/'design'/'simulation'
        self.sim.mkdir(parents=True)
        self.env = patch.dict(os.environ, {'RVPROBE_STORAGE_WORK_ROOT':str(self.work),
            'RVPROBE_STORAGE_ARCHIVE_ROOT':str(self.archive)})
        self.env.start(); self.addCleanup(self.env.stop)
        (self.sim/'sim.log').write_text('UVM_FATAL example failure')
        (self.sim/'simv.vdb').mkdir()
        (self.sim/'simv.vdb'/'coverage').write_text('keep')
        (self.sim/'csrc').mkdir()
        (self.sim/'csrc'/'object.o').write_text('rebuild')
        (self.sim/'simv').write_text('rebuild')

    def test_archive_and_verify_before_pruning_keeps_failure_and_coverage(self):
        archive_closed_simulation(self.sim)
        dest = self.archive/'design'/'simulation'
        self.assertEqual((dest/'sim.log').read_bytes(),(self.sim/'sim.log').read_bytes())
        self.assertTrue((self.sim/'simv.vdb'/'coverage').exists())
        self.assertFalse((self.sim/'csrc').exists())
        self.assertFalse((self.sim/'simv').exists())
        self.assertFalse(json.loads((dest/'storage-checkpoint.json').read_text())['simulation_success_implied'])

    def test_failed_verification_retains_caches(self):
        with patch('experiment_storage.validate',side_effect=ValueError('archive mismatch')):
            with self.assertRaisesRegex(ValueError,'archive mismatch'):
                archive_closed_simulation(self.sim)
        self.assertTrue((self.sim/'csrc'/'object.o').exists())
        self.assertTrue((self.sim/'simv').exists())

    def test_repeated_archive_preserves_existing_coverage_symlink(self):
        (self.sim/'simv.vdb'/'link').symlink_to('coverage')
        dest = self.archive/'design'/'simulation'
        archive_tree(self.sim, dest)
        archive_tree(self.sim, dest)
        self.assertEqual((dest/'simv.vdb'/'link').read_text(),'keep')
        (dest/'simv.vdb'/'link').unlink()
        (dest/'simv.vdb'/'link').symlink_to('different')
        with self.assertRaisesRegex(ValueError,'link conflicts'):
            archive_tree(self.sim, dest)

    def test_outside_root_rejected(self):
        with self.assertRaisesRegex(ValueError,'outside'):
            archive_closed_simulation(self.root)

    def test_low_space_checked_before_simulation(self):
        with patch('experiment_storage.shutil.disk_usage') as usage:
            usage.return_value.free = 1
            with self.assertRaisesRegex(OSError,'scratch space too low'):
                require_space(self.sim)


if __name__ == '__main__':
    unittest.main()
