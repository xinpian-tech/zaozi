import unittest
from types import SimpleNamespace
from unittest.mock import patch

try:
    from haven.eda import vcs_utils
except ImportError:
    vcs_utils = None


@unittest.skipIf(vcs_utils is None,'HAVEN dependencies required')
class InfrastructureFailureTests(unittest.TestCase):
    def test_storage_failure_never_becomes_model_syntax_feedback(self):
        result=SimpleNamespace(returncode=1,stdout='Error-[VFS_SDB_ERROR] VCS database file access error',stderr='')
        with patch.object(vcs_utils,'run_eda_command',return_value=result):
            for call in (lambda:vcs_utils.vcs_syntax_check('files.f','.',config={}),
                         lambda:vcs_utils.vcs_compile('files.f',cwd='.',config={})):
                with self.assertRaisesRegex(RuntimeError,'refusing paid code repair'): call()

    def test_real_syntax_errors_still_reach_model_feedback(self):
        result=SimpleNamespace(returncode=1,stdout='Error-[SE] Syntax error\n bad source\n',stderr='')
        with patch.object(vcs_utils,'run_eda_command',return_value=result):
            result=vcs_utils.vcs_syntax_check('files.f','.',config={})
        self.assertFalse(result['ok'])
        self.assertIn('Syntax error',result['errors'][0])

    def test_missing_files_never_trigger_unrelated_source_repairs(self):
        result=SimpleNamespace(returncode=1,stdout='Error-[SFCOR] Source file cannot be opened',stderr='')
        with patch.object(vcs_utils,'run_eda_command',return_value=result):
            for call in (lambda:vcs_utils.vcs_syntax_check('files.f','.',config={}),
                         lambda:vcs_utils.vcs_compile('files.f',cwd='.',config={})):
                with self.assertRaisesRegex(RuntimeError,'refusing paid code repair'): call()
