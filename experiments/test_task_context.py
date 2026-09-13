from dataclasses import replace
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from sequence_framework import load_design
from sequence_experiment import build_prompt, request_model
from run_records import Records, totals
from rvprobe_skill import snapshot
from task_context import TaskContext, TOOLS, MAX_MODEL_CALLS, PAGE_CHARS


class TaskAccessTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.source=self.root/'dut.sv';self.source.write_text('module DUT_SENTINEL;\nwire example;\nendmodule\n')
        self.design=replace(load_design(Path(__file__).parent/'tests/fixtures/tiny_design.json'),
                            sources=(self.source,),include_dirs=(),context='SPEC_SENTINEL')
        self.feedback={'gaps':[{'expression':'COVERAGE_SENTINEL'}],
            'formal_environment':{'restriction':'ENV_SENTINEL'},
            'shared_context':{'initial_dsl':{'name':'NATIVE_DSL_SENTINEL'},
                              'baseline':{'bundle_fingerprint':'BASELINE_SENTINEL'},'pin_ownership':{'pins':[]}}}
        self.context=TaskContext(self.design,self.feedback)

    def test_initial_prompt_only_spec_io_and_framework(self):
        prompt=build_prompt([(1,'RTL_GAP_SENTINEL')],self.source,'30s',design=self.design,
                            coverage_feedback=self.feedback,skill_context=True)
        self.assertIn('SPEC_SENTINEL',prompt)
        for sentinel in ('DUT_SENTINEL','RTL_GAP_SENTINEL','COVERAGE_SENTINEL','BASELINE_SENTINEL','ENV_SENTINEL'):
            self.assertNotIn(sentinel,prompt)
        self.assertIn('read_rtl',prompt);self.assertIn('read_context',prompt)

    def test_search_and_read_return_exact_frozen_evidence(self):
        listing=self.context.dispatch('list_rtl',{})['files'];self.assertEqual(len(listing),1)
        hit=self.context.dispatch('search_rtl',{'query':'wire example'})['matches'][0]
        result=self.context.dispatch('read_rtl',{'file_id':hit['file_id'],'offset':hit['offset']})
        self.assertTrue(result['text'].startswith('2: wire example;'))
        self.assertEqual(self.context.dispatch('search_rtl',{'query':'.*'})['total_matches'],0)
        self.assertNotIn(str(self.root),json.dumps(listing))

    def test_pagination_does_not_lose_long_lines_or_unicode(self):
        self.source.write_text('wire '+('中文'*PAGE_CHARS)+';\n')
        context=TaskContext(self.design)
        pieces=[];offset=0
        while offset is not None:
            result=context.dispatch('read_rtl',{'file_id':'rtl_0001','offset':offset})
            pieces.append(result['text']);offset=result['next_offset']
        self.assertEqual(''.join(pieces),'1: '+self.source.read_text())
        self.assertTrue(all(len(x)<=PAGE_CHARS for x in pieces))

    def test_context_evidence_available_but_not_mutated(self):
        self.assertIn('COVERAGE_SENTINEL',self.context.dispatch('read_context',{'topic':'coverage'})['text'])
        self.assertIn('ENV_SENTINEL',self.context.dispatch('read_context',{'topic':'environment'})['text'])
        self.assertIn('BASELINE_SENTINEL',self.context.dispatch('read_context',{'topic':'baseline'})['text'])
        self.assertIn('initial_dsl',self.feedback['shared_context'])
        self.assertNotIn('NATIVE_DSL_SENTINEL',str(self.context.topics))

    def test_arbitrary_files_paths_and_arguments_are_rejected(self):
        for name,args in [('read_rtl',{'file_id':'/etc/passwd'}),('list_rtl',{'path':str(self.root)}),
            ('read_rtl',{'file_id':'rtl_0001','offset':True}),('read_context',{'topic':'secrets'}),
            ('search_rtl',{'query':'a','command':'ls'}),('shell',{'cmd':'true'})]:
            with self.subTest(name=name,args=args),self.assertRaises(ValueError):self.context.dispatch(name,args)

    def test_include_filter_and_symlink_escape(self):
        includes=self.root/'include';includes.mkdir()
        (includes/'header.svh').write_text('`define INCLUDED 1\n')
        (includes/'.env').write_text('SECRET_SENTINEL')
        outside=self.root/'outside.sv';outside.write_text('OUTSIDE_SENTINEL')
        (includes/'escape.sv').symlink_to(outside)
        context=TaskContext(replace(self.design,include_dirs=(includes,)))
        names=[v['name'] for v in context.dispatch('list_rtl',{})['files']]
        self.assertEqual(names,['dut.sv','header.svh'])

    def test_changed_rtl_cannot_be_read_or_searched(self):
        self.source.write_text('changed')
        for name,args in [('read_rtl',{'file_id':'rtl_0001'}),('search_rtl',{'query':'module'})]:
            with self.assertRaisesRegex(RuntimeError,'frozen RTL changed'):self.context.dispatch(name,args)

    def test_bulky_reports_are_separate_without_dropping_gaps_or_counts(self):
        import hashlib
        body='FULL_RTL_REPORT\n'*3000
        feedback={'gaps':[{'type':'fsm','covered':5,'total':6,'report_section':body},
                          {'type':'line','line':2,'context':'wire example;'}],'bins':{'fsm':[6,5]}}
        context=TaskContext(self.design,feedback)
        coverage=json.loads(context.topics['coverage'])
        self.assertEqual(len(coverage['gaps']),2)
        self.assertEqual(coverage['bins'],feedback['bins'])
        self.assertEqual(coverage['gaps'][0]['covered'],5)
        self.assertNotIn('FULL_RTL_REPORT',context.topics['coverage'])
        sha=hashlib.sha256(body.encode()).hexdigest()
        self.assertEqual(coverage['gaps'][0]['report_reference']['sha256'],sha)
        self.assertEqual(json.loads(context.topics['coverage_reports'])[sha],body)
        self.assertEqual(feedback['gaps'][0]['report_section'],body)

    def test_line_ranges_and_character_continuation(self):
        result=self.context.dispatch('read_rtl',{'file_id':'rtl_0001','start_line':2,'line_count':1})
        self.assertEqual(result['text'],'2: wire example;\n')
        rest=self.context.dispatch('read_rtl',{'file_id':'rtl_0001','offset':result['next_offset']})
        self.assertEqual(rest['text'],'3: endmodule\n')
        for args in ({'start_line':2,'offset':1},{'start_line':99},{'start_line':True},{'line_count':2049}):
            with self.assertRaises(ValueError):self.context.dispatch('read_rtl',{'file_id':'rtl_0001',**args})

    @staticmethod
    def reply(message,finish='stop'):
        return {'model':'test-model','choices':[{'finish_reason':finish,'message':message}],
                'usage':{'prompt_tokens':10,'completion_tokens':2,'total_tokens':12}}

    def call(self,name,args,number):
        return self.reply({'role':'assistant','content':None,'tool_calls':[{'id':f'call-{number}',
            'type':'function','function':{'name':name,'arguments':json.dumps(args)}}]},'tool_calls')

    def test_tool_dialogue_with_optional_skill_records_all_usage(self):
        for with_skill in (False,True):
            with self.subTest(with_skill=with_skill):
                directory=self.root/str(with_skill);directory.mkdir()
                args=SimpleNamespace(model='test',temperature=0,timeout=1,request_retries=1,
                    task_context=self.context,rvprobe_skill_snapshot=snapshot() if with_skill else None)
                replies=[
                    self.call('list_rtl',{},1),self.call('read_rtl',{'file_id':'rtl_0001'},2),
                    self.call('read_context',{'topic':'environment'},3),self.reply({'role':'assistant','content':'FINAL_JSON'})]
                payloads=[]
                def send(payload,timeout):
                    payloads.append(json.loads(json.dumps(payload)));return replies.pop(0)
                with patch('sequence_experiment.send_completion',side_effect=send):
                    raw,info=request_model(args,'SPEC_IO_ONLY',directory,Records(directory))
                self.assertEqual(raw,'FINAL_JSON')
                self.assertEqual(info['http_requests'],4)
                self.assertEqual(totals(directory)['usage_reported']['total_tokens'],12*len(payloads))
                self.assertNotIn('DUT_SENTINEL',json.dumps(payloads[0]))
                self.assertIn('DUT_SENTINEL',json.dumps(payloads[-1]))
                self.assertEqual(len(list(directory.glob('task-tool-*.json'))),3)
                task_payload=payloads[0]
                self.assertEqual(task_payload['tools'],TOOLS)

    def test_dialogue_budget_has_no_unrecorded_extra_call(self):
        args=SimpleNamespace(model='test',temperature=0,timeout=1,request_retries=1,task_context=self.context)
        with patch('sequence_experiment.send_completion',return_value=self.call('list_rtl',{},1)) as send:
            with self.assertRaisesRegex(RuntimeError,'budget exhausted'):
                request_model(args,'task',self.root,Records(self.root))
        self.assertEqual(send.call_count,MAX_MODEL_CALLS)
        self.assertEqual(totals(self.root)['requests'],MAX_MODEL_CALLS)

    def test_bad_tool_arguments_return_error_then_can_be_corrected(self):
        args=SimpleNamespace(model='test',temperature=0,timeout=1,request_retries=1,task_context=self.context)
        replies=[self.call('read_rtl',{'file_id':'/etc/passwd'},1),
                 self.call('read_rtl',{'file_id':'rtl_0001'},2),self.reply({'content':'FINAL_JSON'})]
        with patch('sequence_experiment.send_completion',side_effect=replies):
            raw,_=request_model(args,'task',self.root,Records(self.root))
        self.assertEqual(raw,'FINAL_JSON')
        error=json.loads((self.root/'task-tool-1-1.json').read_text())['result']
        self.assertEqual(set(error),{'error'})
        self.assertIn('unknown RTL file ID',error['error'])
        self.assertEqual(totals(self.root)['requests'],3)

    def test_last_budget_slot_returns_answer_with_tools_disabled(self):
        args=SimpleNamespace(model='test',temperature=0,timeout=1,request_retries=1,task_context=self.context)
        payloads=[]
        def send(payload,timeout):
            payloads.append(payload)
            if len(payloads) == MAX_MODEL_CALLS:
                return self.reply({'role':'assistant','content':'FINAL_JSON'})
            return self.call('list_rtl',{},len(payloads))
        with patch('sequence_experiment.send_completion',side_effect=send):
            raw,info=request_model(args,'task',self.root,Records(self.root))
        self.assertEqual(raw,'FINAL_JSON')
        self.assertEqual(info['http_requests'],MAX_MODEL_CALLS)
        self.assertEqual(payloads[-1]['tool_choice'],'none')
        self.assertTrue(all(p['tool_choice']=='auto' for p in payloads[:-1]))
        self.assertEqual(len(list(self.root.glob('task-tool-*.json'))),MAX_MODEL_CALLS-1)

    def test_truncated_tool_call_preserves_cost_and_cannot_resume(self):
        args=SimpleNamespace(model='test',temperature=0,timeout=1,request_retries=3,task_context=self.context)
        reply=self.call('read_rtl',{'file_id':'rtl_0001'},1)
        reply['choices'][0]['finish_reason']='length'
        with patch('sequence_experiment.send_completion',return_value=reply) as send:
            with self.assertRaisesRegex(RuntimeError,'incomplete task tool response'):
                request_model(args,'task',self.root,Records(self.root))
            with self.assertRaisesRegex(RuntimeError,'automatic resume regeneration is disabled'):
                request_model(args,'task',self.root,Records(self.root))
        self.assertEqual(send.call_count,1)
        self.assertEqual(totals(self.root)['usage_reported']['total_tokens'],12)
        self.assertFalse(list(self.root.glob('task-tool-*.json')))

    def test_haven_worker_preserves_no_tool_request(self):
        import coverage_flow
        from run_records import save
        options={'model':'test','temperature':0,'timeout':1,'request_retries':1}
        save(self.root/'options.json',options);(self.root/'prompt.txt').write_text('ORIGINAL_INLINE_PROMPT')
        argv=['request','--directory',str(self.root),'--options',str(self.root/'options.json')]
        with patch('sequence_experiment.send_completion',return_value=self.reply({'content':'FINAL_JSON'})) as send:
            self.assertEqual(coverage_flow.main(argv),0)
        payload=send.call_args[0][0]
        self.assertEqual(send.call_count,1)
        self.assertNotIn('tools',payload)
        self.assertEqual(payload['messages'],[{'role':'user','content':'ORIGINAL_INLINE_PROMPT'}])
        self.assertFalse((self.root/'task-context.json').exists())


if __name__=='__main__':unittest.main()
