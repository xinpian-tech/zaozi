import json
import io
from pathlib import Path
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from contextlib import redirect_stdout
from framework_runtime import runtime_files, runtime_hashes, runtime_commands
from repair_policy import model_repair_allowed
from sequence_framework import ROOT
from ut_harness import emit


class FrameworkRuntimeTest(unittest.TestCase):
    def test_missing_nix_is_an_environment_failure_without_execution(self):
        with patch('framework_runtime.shutil.which', return_value=None), \
                patch('framework_runtime.subprocess.run') as run:
            with self.assertRaisesRegex(RuntimeError, "nix.*missing from PATH"):
                runtime_commands()
            run.assert_not_called()

    def test_nix_entry_is_executable_not_just_a_present_path(self):
        with patch('framework_runtime.shutil.which', return_value='/fake/nix'), \
                patch('framework_runtime.subprocess.run', return_value=SimpleNamespace(stdout='nix (Nix) test\n')) as run:
            result = runtime_commands()
            self.assertEqual(result['nix']['version'], 'nix (Nix) test')
            self.assertEqual(run.call_args.args[0], ['/fake/nix', '--version'])
        with patch('framework_runtime.shutil.which', return_value='/fake/nix'), \
                patch('framework_runtime.subprocess.run', side_effect=OSError('loader unavailable')):
            with self.assertRaisesRegex(RuntimeError, 'repair the execution environment'):
                runtime_commands()

    def test_every_trusted_runtime_file_exists_and_core_is_hashed(self):
        paths=runtime_files(ROOT)
        self.assertTrue(all(p.is_file() for p in paths))
        self.assertIn(ROOT/'rvprobe/backend/validation.py',paths)
        self.assertNotIn(ROOT/'experiments/ut_validation.py',paths)
        self.assertEqual(set(map(str,paths)),set(runtime_hashes(ROOT)))

    def test_source_errors_are_a_positive_whitelist(self):
        source={'phase':'typecheck','ok':False,'errors':[{'file':'model.ltl','message':'wrong type'}]}
        self.assertTrue(model_repair_allowed(source))
        self.assertFalse(model_repair_allowed({**source,'model_repair_allowed':False}))
        self.assertFalse(model_repair_allowed({**source,'errors':[{'file':'Generated.scala'}]}))
        self.assertFalse(model_repair_allowed({**source,'errors':[]}))
        self.assertFalse(model_repair_allowed({**source,'phase':'input-check'}))
        self.assertFalse(model_repair_allowed({**source,'phase':'unexpected','model_repair_allowed':True}))

    def test_harness_reports_nonrepairable_input_failures_explicitly(self):
        stdout=io.StringIO()
        with redirect_stdout(stdout), self.assertRaises(SystemExit):
            emit({'phase':'input-check','ok':False,'detail':'changed trusted source'},3)
        report=json.loads(stdout.getvalue())
        self.assertFalse(report['model_repair_allowed'])
        self.assertEqual(report['kind'],'framework_infrastructure_failure')


if __name__=='__main__': unittest.main()
