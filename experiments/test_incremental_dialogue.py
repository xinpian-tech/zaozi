import json
from copy import deepcopy
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from incremental_dialogue import invoke, POLICY
from run_records import Records, totals
from sequence_framework import load_design
from task_context import TaskContext


class IncrementalDialogueTests(unittest.TestCase):
    def setUp(self):
        self.tmp=TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.context=TaskContext(load_design(Path(__file__).parent/'tests/fixtures/tiny_design.json'))
        initial=self.context.initial_evidence()
        self.context.initial_evidence=lambda:deepcopy(initial)
        self.payloads=[]

    def reply(self, calls=None, finish=None):
        message={'role':'assistant','content':None if calls else 'Gen(valid, "v")',
                 'reasoning_content':'PRIVATE_PROVIDER_REASONING'}
        if calls:message['tool_calls']=calls
        return {'model':'test', 'choices':[{'message':message,'finish_reason':finish or ('tool_calls' if calls else 'stop')}],
                'usage':{'prompt_tokens':10,'completion_tokens':4,'total_tokens':14,
                         'prompt_cache_hit_tokens':6,'prompt_cache_miss_tokens':4,
                         'completion_tokens_details':{'reasoning_tokens':3}}}

    def call(self, name='list_rtl', args=None, id='call-1'):
        return {'id':id,'type':'function','function':{'name':name,'arguments':json.dumps(args or {})}}

    def run_dialogue(self, responses):
        iterator=iter(responses)
        def send(payload, timeout):
            self.payloads.append(deepcopy(payload));return next(iterator)
        return invoke('FIXED_SPEC_IO', 'test', .3, 3600, send, Records(self.root),
            self.root, 1, {'content':'FIXED_SKILL','sha256':'test'}, self.context,
            max_tokens=393216, reasoning_effort='max')

    def test_second_request_can_author_with_exact_prefix_and_matching_tool_reply(self):
        text,info=self.run_dialogue([self.reply([self.call()]),self.reply()])
        self.assertEqual(text,'Gen(valid, "v")');self.assertEqual(len(self.payloads),2)
        first,second=[p['messages'] for p in self.payloads]
        self.assertEqual(second[:len(first)],first)
        self.assertEqual([m['role'] for m in second[-2:]],['assistant','tool'])
        self.assertEqual(second[-1]['tool_call_id'],'call-1')
        self.assertEqual(second[-2]['reasoning_content'],'PRIVATE_PROVIDER_REASONING')
        self.assertEqual(sum('FIXED_SKILL' in (m.get('content') or '') for m in second),1)
        self.assertEqual(info['dialogue_policy'],POLICY)
        self.assertEqual(totals(self.root)['usage_reported']['total_tokens'],28)
        self.assertNotIn('PRIVATE_PROVIDER_REASONING',''.join(p.read_text() for p in self.root.iterdir()))

    def test_batch_overshoot_is_not_executed_and_final_request_is_reserved(self):
        batch=self.call('search_rtl_batch',{'queries':[{'query':'a'},{'query':'b'}]},'batch')
        later=self.call(id='later')
        with patch('incremental_dialogue.MAX_TOOL_CALLS',2),patch.object(self.context,'dispatch',wraps=self.context.dispatch) as dispatch:
            _,info=self.run_dialogue([self.reply([self.call()]),self.reply([batch,later]),self.reply()])
        self.assertEqual(dispatch.call_count,1)
        self.assertEqual(info['task_tool_calls'],1)
        last=self.payloads[-1]
        self.assertEqual(last['tool_choice'],'none')
        replies=[m for m in last['messages'] if m['role']=='tool']
        self.assertEqual([m['tool_call_id'] for m in replies],['call-1','batch','later'])
        self.assertIn('NOT executed',replies[-1]['content'])
        self.assertEqual(len(list(self.root.glob('tool-budget-refusal-*.json'))),2)

    def test_last_model_call_disables_tools_without_an_extra_ready_request(self):
        with patch('incremental_dialogue.MAX_MODEL_CALLS',2):
            self.run_dialogue([self.reply([self.call()]),self.reply()])
        self.assertEqual(self.payloads[-1]['tool_choice'],'none')
        self.assertEqual(len(self.payloads),2)

    def test_mixed_tool_turn_has_authoring_budget_and_final_uses_authoring_options(self):
        responses=iter([self.reply([self.call()]),self.reply()])
        def send(payload,timeout):
            self.payloads.append(deepcopy(payload));return next(responses)
        _,info=invoke('FIXED_SPEC_IO','test',.3,3600,send,Records(self.root),
            self.root,1,{'content':'FIXED_SKILL','sha256':'test'},self.context,
            max_tokens=16384,reasoning_effort='high',evidence_steps=1,evidence_tools=2,
            retrieval_max_tokens=8192,retrieval_reasoning_effort='low')
        self.assertEqual(len(self.payloads),2)
        self.assertEqual((self.payloads[0]['max_tokens'],self.payloads[0]['reasoning_effort']),(16384,'low'))
        self.assertEqual((self.payloads[1]['max_tokens'],self.payloads[1]['reasoning_effort']),(16384,'high'))
        self.assertEqual(self.payloads[1]['tool_choice'],'none')
        self.assertEqual(info['task_tool_calls'],1)

    def test_direct_answer_on_mixed_turn_is_not_limited_by_retrieval_cap(self):
        responses=iter([self.reply()])
        def send(payload,timeout):
            self.payloads.append(deepcopy(payload));return next(responses)
        text,info=invoke('FIXED_SPEC_IO','test',.3,3600,send,Records(self.root),
            self.root,1,{'content':'FIXED_SKILL','sha256':'test'},self.context,
            max_tokens=16384,reasoning_effort='high',evidence_steps=1,evidence_tools=2,
            retrieval_max_tokens=8192,retrieval_reasoning_effort='low')
        self.assertEqual(text,'Gen(valid, "v")')
        self.assertEqual(len(self.payloads),1)
        self.assertEqual((self.payloads[0]['max_tokens'],self.payloads[0]['reasoning_effort']),
                         (16384,'low'))
        self.assertEqual(self.payloads[0]['tool_choice'],'auto')
        self.assertEqual(info['task_tool_calls'],0)

    def test_zero_evidence_steps_makes_one_tools_disabled_request(self):
        responses=iter([self.reply()])
        def send(payload,timeout):
            self.payloads.append(deepcopy(payload));return next(responses)
        invoke('FIXED_SPEC_IO','test',.3,3600,send,Records(self.root),
            self.root,1,{'content':'FIXED_SKILL','sha256':'test'},self.context,
            evidence_steps=0,evidence_tools=2)
        self.assertEqual(len(self.payloads),1)
        self.assertEqual(self.payloads[0]['tool_choice'],'none')

    def test_unexpected_final_tools_fail_without_more_requests(self):
        with patch('incremental_dialogue.MAX_MODEL_CALLS',1):
            with self.assertRaisesRegex(RuntimeError,'closed evidence budget'):
                self.run_dialogue([self.reply([self.call()])])
        self.assertEqual(totals(self.root)['requests'],1)

    def test_truncated_tool_reply_never_dispatches(self):
        with patch.object(self.context,'dispatch') as dispatch:
            with self.assertRaisesRegex(RuntimeError,'no automatic regeneration'):
                self.run_dialogue([self.reply([self.call()],finish='length')])
        dispatch.assert_not_called()
        self.assertTrue((self.root/'incomplete-response-1.json').exists())

    def test_duplicate_call_ids_rejected(self):
        with self.assertRaisesRegex(ValueError,'unique nonempty IDs'):
            self.run_dialogue([self.reply([self.call(),self.call()])])

    def test_transport_error_retains_completed_request_costs(self):
        def send(payload,timeout):
            if not self.payloads:
                self.payloads.append(payload);return self.reply([self.call()])
            raise RuntimeError('transport unavailable')
        with self.assertRaisesRegex(RuntimeError,'transport unavailable'):
            invoke('task','test',0,1,send,Records(self.root),self.root,1,None,self.context)
        costs=totals(self.root)
        self.assertEqual(costs['requests'],2)
        self.assertEqual(costs['requests_without_usage'],1)
        self.assertEqual(costs['usage_reported']['total_tokens'],14)


if __name__=='__main__':unittest.main()
