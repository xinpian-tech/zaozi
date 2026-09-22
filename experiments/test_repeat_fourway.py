import backend_imports
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from repeat_fourway import cells, key, command, price, METHODS, PROFILE
from sequence_experiment import invoke, request_model, send_completion
from run_records import Records


class RepeatProtocolTests(unittest.TestCase):
    def test_exact_two_repeats_no_best_of_selection(self):
        rows=cells(2)
        self.assertEqual(len(rows),128)
        self.assertEqual(len({key(c) for c in rows}),128)
        for method in METHODS:
            self.assertEqual(sum(c[1]==method for c in rows),32)

    def test_all_methods_receive_regular_profile_without_changing_haven_prompt(self):
        with TemporaryDirectory() as tmp:
            root=Path(tmp);setup=root/'setup.json'
            setup.write_text(json.dumps({'stage1':'/fixed/stage1','haven_snapshot':'/fixed/haven'}))
            args=SimpleNamespace(env_file=root/'secret.env',yosys=root/'yosys')
            for method in METHODS:
                argv=command((1,method,'alu'),root,{'alu':str(setup)},args)
                self.assertIn('deepseek-v4-flash',argv)
                self.assertIn('65536',argv);self.assertIn('high',argv)
                self.assertIn('1200',argv)
                self.assertNotIn('--continue-generation',argv)
                self.assertEqual(argv[argv.index('--rounds')+1],'3')
                if method=='haven':
                    self.assertIn('--haven-max-tokens',argv)
                    self.assertNotIn('--rvprobe-max-tokens',argv)

    def test_haven_explicit_provider_fields_not_inherited_rvprobe_defaults(self):
        seen=[]
        def send(payload,timeout):
            seen.append(payload)
            return {'model':'test','choices':[{'finish_reason':'stop','message':{'content':'{}'}}],
                    'usage':{'prompt_tokens':8,'completion_tokens':2,'total_tokens':10}}
        with TemporaryDirectory() as tmp, patch('sequence_experiment.send_completion',side_effect=send):
            args=SimpleNamespace(model='test',temperature=.3,timeout=1200,request_retries=1,
                provider_max_tokens=65536,provider_reasoning_effort='high',max_tokens=393216,reasoning_effort='max')
            root=Path(tmp)
            request_model(args,'same original HAVEN prompt',root,Records(root))
        self.assertEqual(seen[0]['max_tokens'],65536)
        self.assertEqual(seen[0]['reasoning_effort'],'high')
        self.assertEqual(seen[0]['messages'][0]['content'],'same original HAVEN prompt')

    def test_stop_gate_precedes_any_network_activity(self):
        with TemporaryDirectory() as tmp:
            stop=Path(tmp)/'STOP';stop.touch()
            with patch.dict('os.environ',{'RVPROBE_STOP_NEW_MODEL_REQUESTS':str(stop)}), \
                 patch('sequence_experiment.urllib.request.urlopen') as urlopen:
                with self.assertRaisesRegex(RuntimeError,'campaign_gate_stop'):
                    send_completion({},1)
                urlopen.assert_not_called()

    def test_missing_usage_not_free_and_output_reasoning_not_counted_twice(self):
        costs=dict(requests=1,token_accounting_complete=True,
            usage_reported={'completion_tokens':1000000},usage_breakdown={
                'prompt_cache_hit_tokens':dict(complete=True,reported_tokens=1000000),
                'prompt_cache_miss_tokens':dict(complete=True,reported_tokens=1000000)})
        self.assertEqual(price(costs),'5.02')
        costs['token_accounting_complete']=False
        self.assertIsNone(price(costs))
        self.assertEqual(price({'requests':0}),'0')


if __name__=='__main__':unittest.main()
