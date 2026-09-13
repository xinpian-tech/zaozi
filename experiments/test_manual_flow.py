import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import importlib.util
from unittest.mock import patch

from manual_flow import ManualCompletion, ManualWorkers, worker_entry, generation, paired


class ManualFlowTests(unittest.TestCase):
    def test_remote_model_identity_and_expired_mailbox_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            responder=ManualCompletion(directory,wait_seconds=0)
            with self.assertRaisesRegex(ValueError,'impersonate'):
                responder({'model':'deepseek'},1)
            with self.assertRaisesRegex(RuntimeError,'no remote fallback'):
                responder({'model':'manual-author-debug','messages':[]},1)
            self.assertEqual(responder.count,1)

    def test_response_is_bound_to_request_and_never_fabricates_token_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            def answer(*args):
                request=json.loads((root/'0001/request.json').read_text())
                (root/'0001/response.json').write_text(json.dumps({
                    'request_sha256':request['sha256'],
                    'message':{'role':'assistant','content':'synthetic'}}))
            with patch('manual_flow.time.sleep',answer):
                result=ManualCompletion(root)({'model':'manual-author-debug','messages':[]},1)
            self.assertEqual(result['choices'][0]['message']['content'],'synthetic')
            self.assertIsNone(result['usage']['total_tokens'])

    def test_unexpected_worker_has_no_external_fallback(self):
        with self.assertRaisesRegex(ValueError,'unexpected'):
            worker_entry(['curl','https://invalid.example'])
        with self.assertRaisesRegex(ValueError,'unexpected'):
            worker_entry([sys.executable,str(Path(paired.__file__).resolve()),'run'])
        with self.assertRaisesRegex(ValueError,'credentials'):
            worker_entry([sys.executable,str(Path(generation.__file__).resolve()),'--env-file','secret'])
        with self.assertRaisesRegex(ValueError,'credentials'):
            worker_entry([sys.executable,str(Path(generation.__file__).resolve()),'--env-file=secret'])

    def test_subprocess_keeps_bounds_and_removes_provider_credentials(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            runner=ManualWorkers(root/'mailbox',root/'haven')
            command=[sys.executable,str(Path(generation.__file__).resolve()),'--model','manual-author-debug']
            with patch.dict(os.environ,{'OPENAI_API_KEY':'not-a-real-key','RVPROBE_LLM_BASE_URL':'https://invalid.example'}), patch('manual_flow.run_worker',return_value=SimpleNamespace(returncode=7)) as execute:
                result=runner(command,timeout=42,check=True,stdout=-1,stderr=-2)
            self.assertEqual(result.returncode,7)
            argv=execute.call_args.args[0]; kwargs=execute.call_args.kwargs
            self.assertEqual(argv[-len(command):],command)
            self.assertIn('worker',argv)
            self.assertEqual(kwargs['timeout'],42)
            self.assertTrue(kwargs['check'])
            self.assertNotIn('OPENAI_API_KEY',kwargs['env'])
            self.assertNotIn('RVPROBE_LLM_BASE_URL',kwargs['env'])
            self.assertEqual(kwargs['env']['PYTHONPATH'].split(os.pathsep)[0],str(root/'haven/src'))
            self.assertEqual(runner.count,1)

    def test_wrong_response_hash_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            def answer(*args):
                (root/'0001/response.json').write_text(json.dumps({'request_sha256':'wrong','message':{'role':'assistant','content':'{}'}}))
            with patch('manual_flow.time.sleep',answer), self.assertRaisesRegex(ValueError,'exact request'):
                ManualCompletion(root)({'model':'manual-author-debug','messages':[]},1)


@unittest.skipUnless(importlib.util.find_spec('haven'), 'HAVEN Python environment required')
class OfflineCompileTests(unittest.TestCase):
    def test_success_and_offline_failure_do_not_construct_model_client(self):
        from contextlib import ExitStack
        from unittest.mock import MagicMock
        import haven.graph.task_graph as graph
        for ok in (True,False):
            with self.subTest(ok=ok), tempfile.TemporaryDirectory() as directory, ExitStack() as patches:
                om=MagicMock();om.run_dir=Path(directory)
                state={'task':{'module_name':'probe'},'config':{'simulation':{'model_repairs':False}},
                       'components':{},'sequences':[],'compile_passed':True}
                patches.enter_context(patch.object(graph,'_status'))
                patches.enter_context(patch.object(graph,'_om',return_value=om))
                patches.enter_context(patch.object(graph,'write_check_files'))
                patches.enter_context(patch.object(graph,'write_check_filelist',return_value='files.f'))
                patches.enter_context(patch.object(graph,'vcs_syntax_check',return_value={
                    'ok':ok,'log':'synthetic compiler output','errors':[] if ok else ['syntax error']}))
                client=patches.enter_context(patch.object(graph.LLMClient,'for_coding',side_effect=AssertionError('client constructed')))
                result=graph.node_compile_check(state)
                self.assertEqual(result['compile_passed'],ok)
                client.assert_not_called()
                om.save_ir_json.assert_called_once()
