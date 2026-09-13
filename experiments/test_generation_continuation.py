import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

from coverage_flow import saved_generation
from run_records import save


class GenerationContinuationTests(unittest.TestCase):
    def fixture(self, root):
        directory=root/'flow/paired/rvprobe/round-1/generation'
        bundle={k:{'fixture':1} for k in
                ('initial_dsl','sequences','components','bfm_components','blueprint','haven_sha256')}
        save(root/'flow/shared/bundle.json',bundle)
        save(directory/'manifest.json',{'model':'provider','design':{'fixture':'design'}})
        save(directory/'summary.json',{'model':'provider','attempts':3,'costs':{'usage_reported':{'total_tokens':17}},
                                       'elapsed_seconds':12})
        save(directory/'attempt-3/provider.json',{'requested_model':'provider','response_status':'complete'})
        save(directory/'attempt-3/response.txt',{'fixture':'unchanged'})
        return directory,bundle,SimpleNamespace(record=lambda:{'fixture':'design'})

    def test_latest_attempt_retains_prior_cost_and_verifies_saved_sources(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory,bundle,design=self.fixture(Path(tmp))
            with patch('coverage_flow.generation.parse_response',return_value={'fixture':'parsed'}), \
                 patch('sequence_framework.check_saved_sources') as verify:
                result=saved_generation(directory,bundle,design,'provider')
            self.assertEqual(result['response'],str(directory/'attempt-3/response.txt'))
            self.assertEqual(result['prior_costs']['usage_reported']['total_tokens'],17)
            verify.assert_called_once_with(directory/'attempt-3/sources',design,{'fixture':'parsed'})

    def test_wrong_model_design_or_shared_components_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory,bundle,design=self.fixture(Path(tmp))
            with self.assertRaisesRegex(ValueError,'requested model'):
                saved_generation(directory,bundle,design,'manual-author-debug')
            with self.assertRaisesRegex(ValueError,'RTL/IO'):
                saved_generation(directory,bundle,SimpleNamespace(record=lambda:{}),'provider')
            with self.assertRaisesRegex(ValueError,'shared environment changed'):
                saved_generation(directory,{**bundle,'components':{}},design,'provider')

    def test_truncated_provider_response_is_not_reused(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory,bundle,design=self.fixture(Path(tmp))
            save(directory/'attempt-3/provider.json',{'requested_model':'provider','response_status':'truncated'})
            with self.assertRaisesRegex(ValueError,'complete provider response'):
                saved_generation(directory,bundle,design,'provider')
