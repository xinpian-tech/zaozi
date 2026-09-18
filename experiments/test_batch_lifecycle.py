import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from batch_lifecycle import current_owner, observe
from batch_status import reconcile
from batch_service import service_command
from run_records import save


class BatchLifecycleTest(unittest.TestCase):
    def fixture(self, root):
        save(root/'progress.json',dict(status='running',pid=1234567,
            designs={'alu':{'status':'running_rvprobe'},'uart':{'status':'queued'}}))
        save(root/'alu/flow/paired/summary.json',dict(status='failed',finished_utc='recorded'))

    def test_absent_owner_overrides_stale_running_and_preserves_child_results(self):
        with tempfile.TemporaryDirectory() as tmp, patch('batch_lifecycle.process_identity',return_value=None):
            root=Path(tmp);self.fixture(root)
            view=observe(root)
            self.assertEqual(view['status'],'interrupted')
            self.assertEqual(view['designs']['alu']['status'],'failed')
            self.assertEqual(view['designs']['uart']['status'],'not_started')
            self.assertEqual(json.loads((root/'progress.json').read_text())['status'],'running')

    def test_reconcile_preserves_original_record_and_refuses_live_owner(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);self.fixture(root)
            with patch('batch_lifecycle.process_identity',return_value={'pid':1234567}):
                with self.assertRaisesRegex(ValueError,'active'):
                    reconcile(root)
            with patch('batch_lifecycle.process_identity',return_value=None), \
                    patch('batch_status.process_identity',return_value=None):
                reconcile(root)
            self.assertEqual(json.loads((root/'progress.json').read_text())['status'],'interrupted')
            self.assertEqual(json.loads((root/'progress.before-reconcile.json').read_text())['status'],'running')

    def test_pid_reuse_is_not_the_original_owner(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);self.fixture(root)
            record=json.loads((root/'progress.json').read_text())
            record['owner']={'pid':1234567,'start_ticks':'old','boot_id':'old'}
            save(root/'progress.json',record)
            with patch('batch_lifecycle.process_identity',return_value={'pid':1234567,'start_ticks':'new','boot_id':'old'}):
                self.assertEqual(observe(root)['status'],'interrupted')

    def test_current_identity_is_stable(self):
        owner=current_owner()
        self.assertEqual(owner['pid'],os.getpid())
        self.assertEqual(current_owner(),owner)

    def test_service_is_one_shot_and_does_not_expose_environment_values(self):
        with patch('batch_service.shutil.which',return_value='/bin/systemd-run'), \
                patch.dict(os.environ,{'HTTPS_PROXY':'secret-proxy','OPENAI_API_KEY':'secret-key'}):
            command=service_command('rvprobe-test',Path('/tmp/log'),['/bin/true'],Path('/tmp'))
        self.assertIn('--property=Type=exec',command)
        self.assertIn('--setenv=HTTPS_PROXY',command)
        self.assertNotIn('secret',str(command))
        self.assertNotIn('OPENAI_API_KEY',str(command))
        self.assertNotIn('--on-calendar',str(command))
        with self.assertRaises(ValueError): service_command('bad/unit',Path('/tmp/log'),['true'],Path('/tmp'))


if __name__=='__main__': unittest.main()
