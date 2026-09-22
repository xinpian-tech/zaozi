"""No-model regressions for batched evidence, local repairs and source fidelity."""
import hashlib
import json
import os
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
import io
from contextlib import redirect_stdout, redirect_stderr
from unittest.mock import patch

import sequence_experiment as generation
from run_records import Records, totals
from sequence_framework import load_design
from task_context import TaskContext, RepairContext, PAGE_CHARS
from repair_diagnostics import project
from rvprobe_skill import snapshot
from test_support import goal_response


class TokenFlowTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.source=self.root/'dut.sv'
        self.source.write_text('module target;\nwire value;\nwire value2;\nendmodule\n')
        self.design=replace(load_design(Path(__file__).parent/'tests/fixtures/tiny_design.json'),
            sources=(self.source,),include_dirs=(),context='PRIVATE_SPEC_BODY')
        self.feedback={'gaps':[{'type':'toggle','signal':'value','context':'RTL_SNIPPET'}],
            'bins':{'toggle':[8,2]},'formal_environment':{'fixed':'PHYSICAL_CONDITION'},
            'shared_context':{'initial_dsl':{'source':'HAVEN_NATIVE_DSL'}}}
        self.context=TaskContext(self.design,self.feedback)

    def test_bootstrap_contains_exact_counts_environment_catalog_not_rtl_or_native_dsl(self):
        value=self.context.initial_evidence()
        self.assertEqual(value['coverage']['bins'],self.feedback['bins'])
        self.assertEqual(value['coverage']['gaps_by_type'],{'toggle':1})
        full=self.context.dispatch('list_rtl',{})
        self.assertEqual(value['rtl']['files'],[
            {k:row[k] for k in ('file_id','name','lines','characters')}
            for row in full['files']])
        self.assertNotIn('sha256',value['rtl']['files'][0])
        self.assertNotIn('encoding',value['rtl']['files'][0])
        text=json.dumps(value)
        self.assertIn('PHYSICAL_CONDITION',text)
        for sentinel in ('RTL_SNIPPET','module target','HAVEN_NATIVE_DSL'):self.assertNotIn(sentinel,text)
        self.assertIn('RTL_SNIPPET',self.context.topics['coverage'])
        from task_context import INSTRUCTION
        self.assertIn('output LTL immediately without an RTL tool call',INSTRUCTION)
        self.assertIn('Do not read RTL merely to confirm',INSTRUCTION)

    def test_model_projection_omits_repeated_batch_rule_but_audit_retains_it(self):
        feedback={**self.feedback,'batch_instruction':'EXACT_FIXED_BATCH_RULE'}
        context=TaskContext(self.design,feedback)
        self.assertNotIn('batch_instruction',context.initial_evidence()['environment'])
        self.assertEqual(json.loads(context.topics['environment'])['batch_instruction'],
                         'EXACT_FIXED_BATCH_RULE')
        self.assertEqual(context.record()['model_projection']['omitted_environment_fields'],
                         ['batch_instruction'])

    def test_batched_ranges_merge_overlap_without_repeating_or_losing_text(self):
        ranges=[{'file_id':'rtl_0001','start_line':2,'line_count':2},
                {'file_id':'rtl_0001','start_line':1,'line_count':2}]
        result=self.context.dispatch('read_rtl_batch',{'ranges':ranges})['ranges']
        self.assertEqual(len(result),1)
        self.assertEqual(result[0]['text'],'1: module target;\n2: wire value;\n3: wire value2;\n')
        self.assertEqual(set(result[0]['request_indices']),{0,1})
        self.assertIsNone(result[0]['next_offset'])

    def test_batch_long_unicode_ranges_have_lossless_bounded_continuations(self):
        self.source.write_text('wire '+('中文'*PAGE_CHARS)+';\nnext;\n')
        context=TaskContext(self.design)
        result=context.dispatch('read_rtl_batch',{'ranges':[{'file_id':'rtl_0001','start_line':1,'line_count':1}]})['ranges'][0]
        self.assertEqual(len(result['text']),PAGE_CHARS)
        pieces=[result['text']];offset=result['next_offset']
        while offset is not None:
            page=context.dispatch('read_rtl',{'file_id':'rtl_0001','offset':offset,
                'limit':min(PAGE_CHARS,result['end_offset']-offset)})
            pieces.append(page['text']);offset=offset+len(page['text'])
            if offset==result['end_offset']:offset=None
        self.assertEqual(''.join(pieces),'1: '+self.source.read_text().splitlines(keepends=True)[0])

    def test_search_batch_preserves_queries_and_pagination(self):
        self.source.write_text('wire value;\n'*30)
        context=TaskContext(self.design)
        args={'queries':[{'query':'value'},{'query':'wire','offset':3}]}
        results=context.dispatch('search_rtl_batch',args)['results']
        self.assertEqual(sum(len(r['matches']) for r in results),20)
        self.assertEqual(results[0]['next_offset'],20)
        self.assertEqual(results[1]['next_offset'],3)
        continued=context.dispatch('search_rtl',{'query':'wire','offset':results[1]['next_offset']})
        self.assertEqual(continued['matches'][0]['line'],4)

    def test_batch_rejects_invalid_entries_and_changed_rtl(self):
        for name,args in [('read_rtl_batch',{'ranges':[]}),
                         ('read_rtl_batch',{'ranges':[{'file_id':'/etc/passwd','start_line':1,'line_count':2}]}),
                         ('read_rtl_batch',{'ranges':[{'file_id':'rtl_0001','start_line':True,'line_count':2}]}),
                         ('search_rtl_batch',{'queries':[{'query':'x','shell':'ls'}]}),
                         ('search_rtl_batch',{'queries':[{'query':'x'}]*9})]:
            with self.subTest(name=name,args=args),self.assertRaises(ValueError):self.context.dispatch(name,args)
        self.source.write_text('changed')
        with self.assertRaisesRegex(RuntimeError,'frozen RTL changed'):
            self.context.dispatch('read_rtl_batch',{'ranges':[{'file_id':'rtl_0001','start_line':1,'line_count':1}]})

    def test_solver_repair_reuses_exact_reads_and_exposes_no_coverage_replanning(self):
        read=self.context.dispatch('read_rtl',{'file_id':'rtl_0001','start_line':2,'line_count':1})
        for code in RepairContext.SEMANTIC_CODES:
            with self.subTest(code=code):
                errors=[{'file':'model.ltl','goal':'observe_response','code':code}]
                repair=RepairContext(self.context,errors,[read])
                names={t['function']['name'] for t in repair.tools}
                self.assertEqual(names,{'read_framework','read_diagnostics','read_rtl',
                                       'read_rtl_batch','inspect_rtl_batch','read_context'})
                initial=repair.initial_evidence()
                self.assertEqual(initial['previously_read_rtl']['ranges'][0]['text'],read['text'])
                self.assertIn('PHYSICAL_CONDITION',json.dumps(initial))
                self.assertNotIn('RTL_SNIPPET',json.dumps(initial))
                self.assertNotIn('current_feedback',initial)
                self.assertNotIn('accepted_ltl',initial)
                self.assertNotIn('HAVEN_NATIVE_DSL',json.dumps(initial))
                self.assertEqual(json.loads(repair.dispatch('read_context',{'topic':'rtl_history'})['text'])[0]['text'],read['text'])
                self.assertEqual(repair.dispatch('read_rtl',{'file_id':'rtl_0001','start_line':2,'line_count':1}),read)
                for topic in ('coverage','history','environment'):
                    with self.assertRaises(ValueError):repair.dispatch('read_context',{'topic':topic})
                with self.assertRaises(ValueError):repair.dispatch('read_coverage',{})
                self.assertEqual(repair.call_cost('read_rtl_batch',{'ranges':[{},{}]}),2)
                prompt=generation.build_prompt([],self.design.sources[0],'120s',
                    errors=errors,previous='PRIOR',design=self.design)
                self.assertIn('read only missing implementation facts',prompt)
                self.assertNotIn('RTL search and coverage replanning are unavailable',prompt)

    def test_only_recognized_goal_diagnostics_enable_semantic_evidence(self):
        good={'file':'model.ltl','goal':'observe_response','code':'jg_goal_infeasible'}
        for errors in ([{**good,'file':'framework.scala'}],[{**good,'goal':''}],
                       [{**good,'code':'unknown_infrastructure_failure'}],[good,'unknown failure']):
            with self.subTest(errors=errors):
                context=RepairContext(self.context,errors)
                self.assertFalse(context.semantic)
                self.assertEqual({t['function']['name'] for t in context.tools},
                                 {'read_framework','read_diagnostics'})

    def test_framework_content_is_on_demand_and_hash_checked(self):
        source=self.root/'framework.scala';source.write_text('FRAMEWORK_ONLY_BODY')
        doc=SimpleNamespace(id='api',source='framework.scala',content=source.read_text(),
            title='API',source_sha256=hashlib.sha256(source.read_bytes()).hexdigest())
        context=TaskContext(self.design,framework=[doc])
        self.assertNotIn(doc.content,json.dumps(context.initial_evidence()))
        with patch('prompt_rag.REPO_ROOT',self.root):
            self.assertEqual(context.dispatch('read_framework',{'id':'api'})['text'],doc.content)
            source.write_text('changed')
            with self.assertRaisesRegex(RuntimeError,'frozen framework source changed'):
                context.dispatch('read_framework',{'id':'api'})

    def test_expression_catalog_keeps_additional_apis_on_demand(self):
        from dataclasses import replace
        _, docs=generation.load_corpus(generation.DEFAULT_RAG_CORPUS)
        hits=generation.retrieve_diverse(generation.retrieval_queries(),docs,6)
        self.assertEqual(len(hits),5)
        self.assertEqual(generation.supplemental_references(hits),hits)
        changed=replace(hits[0],content=hits[0].content+'\nNEW_API')
        novel=replace(hits[1],id='new-api')
        self.assertEqual(generation.supplemental_references([changed,novel]),[changed,novel])
        self.assertEqual(generation.supplemental_references(hits,{'sha256':'different-skill'}),hits)
        self.assertIn('supplied',generation.framework_catalog([]))

    def test_diagnostic_projection_preserves_full_report_and_prioritizes_syntax(self):
        errors=[{'kind':'Type Mismatch Error','message':'CASCADE'},
                {'kind':'Syntax Error','file':'ModelUT.scala','line':8,'col':1,'message':'unclosed identifier'}]*2
        original=json.dumps(errors)
        result=project(errors)
        self.assertEqual(result['selection'],'syntax-first')
        self.assertEqual(result['diagnostics'],[errors[1]])
        self.assertEqual(result['total_diagnostics'],4)
        repair=RepairContext(self.context,errors)
        self.assertEqual(json.loads(repair.dispatch('read_diagnostics',{})['text']),errors)
        self.assertEqual(json.dumps(errors),original)

    def test_local_repair_does_not_expose_rtl_search_or_coverage_replanning(self):
        repair=RepairContext(self.context,['error'])
        self.assertEqual({t['function']['name'] for t in repair.tools},{'read_framework','read_diagnostics'})
        for name,args in [('read_rtl',{'file_id':'rtl_0001'}),('read_context',{'topic':'coverage'})]:
            with self.assertRaises(ValueError):repair.dispatch(name,args)
        previous=goal_response('goal','io.valid')['ltl']
        prompt=generation.build_prompt([],self.source,'30s',design=self.design,errors=['error'],previous=previous,skill_context=True)
        self.assertIn(previous,prompt)
        self.assertNotIn('PRIVATE_SPEC_BODY',prompt)
        self.assertNotIn('RTL_SNIPPET',prompt)
        self.assertIn('do not replan coverage',prompt)

    def test_long_diagnostic_projection_keeps_exact_report_readable(self):
        from repair_diagnostics import MAX_PROJECTED_MESSAGE_CHARS
        message='TYPE_ERROR '+('x'*10000)+' ORIGINAL_TAIL'
        errors=[{'kind':'Type Mismatch Error','line':8,'message':message}]
        result=project(errors)
        row=result['diagnostics'][0]
        self.assertEqual(row['line'],8)
        self.assertTrue(row['message'].startswith(message[:MAX_PROJECTED_MESSAGE_CHARS]))
        self.assertIn('tail omitted',row['message'])
        self.assertLess(len(row['message']),MAX_PROJECTED_MESSAGE_CHARS+100)
        repair=RepairContext(self.context,errors)
        self.assertEqual(json.loads(repair.dispatch('read_diagnostics',{})['text']),errors)
        self.assertEqual(errors[0]['message'],message)

    def test_direct_ltl_after_evidence_needs_no_handoff(self):
        args=SimpleNamespace(model='test',temperature=0,timeout=1,request_retries=1,
            task_context=self.context,rvprobe_skill_snapshot=snapshot())
        call={'id':'read-1','type':'function','function':{'name':'list_rtl','arguments':'{}'}}
        ltl='Gen(valid, "valid_seen")'
        with patch.object(generation,'send_completion',side_effect=[self.reply(None,[call]),self.reply(ltl)]) as send:
            raw,info=generation.request_model(args,'SPEC_IO',self.root,Records(self.root))
        self.assertEqual(raw,ltl)
        self.assertEqual(send.call_count,2)
        self.assertEqual(info['http_requests'],2)
        self.assertFalse(list(self.root.glob('evidence-handoff-*.json')))

    def test_retrieval_requests_can_use_separate_budget_from_final_authoring(self):
        args=SimpleNamespace(model='test',temperature=0,timeout=1,request_retries=1,
            task_context=self.context,rvprobe_skill_snapshot=snapshot(),
            evidence_steps=2,evidence_tools=16,retrieval_max_tokens=32768,
            retrieval_reasoning_effort='low',max_tokens=393216,reasoning_effort='max')
        call={'id':'read-1','type':'function','function':{'name':'list_rtl','arguments':'{}'}}
        ltl='Gen(valid, "valid_seen")'
        call2={'id':'read-2','type':'function','function':{'name':'read_context','arguments':json.dumps({'topic':'environment'})}}
        with patch.object(generation,'send_completion',side_effect=[self.reply(None,[call]),self.reply(None,[call2]),self.reply(ltl)]) as send:
            raw,_=generation.request_model(args,'SPEC_IO',self.root,Records(self.root))
        self.assertEqual(raw,ltl)
        first,last=send.call_args_list[0].args[0],send.call_args_list[-1].args[0]
        self.assertEqual((first['max_tokens'],first['reasoning_effort']),(32768,'low'))
        self.assertEqual((last['max_tokens'],last['reasoning_effort']),(393216,'max'))

    def test_tool_budget_overflow_retains_final_authoring_opportunity(self):
        args=SimpleNamespace(model='test',temperature=0,timeout=1,request_retries=1,
            task_context=self.context,rvprobe_skill_snapshot=snapshot())
        def call(name,args,ident):
            return {'id':ident,'type':'function','function':{'name':name,'arguments':json.dumps(args)}}
        first=call('list_rtl',{},'one')
        overflow=call('search_rtl_batch',{'queries':[{'query':'wire'}]*2},'two')
        ltl='Gen(valid, "valid_seen")'
        replies=[self.reply(None,[first]),self.reply(None,[overflow]),self.reply(ltl)]
        with patch('rvprobe_skill.MAX_TOOL_CALLS',2), patch.object(self.context,'dispatch',wraps=self.context.dispatch) as dispatch, \
                patch.object(generation,'send_completion',side_effect=replies) as send:
            raw,info=generation.request_model(args,'SPEC_IO',self.root,Records(self.root))
        self.assertEqual(raw,ltl)
        self.assertEqual(info['task_tool_calls'],1)
        self.assertEqual(send.call_count,3)
        self.assertEqual(send.call_args.args[0]['tool_choice'],'none')
        self.assertFalse(any(c.args[0]=='search_rtl_batch' for c in dispatch.call_args_list))
        self.assertIn('NOT executed',json.dumps(send.call_args.args[0]))
        self.assertEqual(len(list(self.root.glob('tool-budget-refusal-*.json'))),1)

    @staticmethod
    def reply(content='FINAL',calls=None):
        return {'model':'test','choices':[{'message':{'role':'assistant','content':content,
            **({'tool_calls':calls,'reasoning_content':'PRIVATE_PROTOCOL_FIELD'} if calls else {})},
            'finish_reason':'tool_calls' if calls else 'stop'}],
            'usage':{'prompt_tokens':10,'completion_tokens':2,'total_tokens':12}}

    def test_batches_charge_queries_and_start_clean_independent_requests(self):
        args=SimpleNamespace(model='test',temperature=0,timeout=1,request_retries=1,
            task_context=self.context,rvprobe_skill_snapshot=snapshot())
        payloads=[]
        def send(payload,timeout):
            payloads.append(json.loads(json.dumps(payload)))
            if payload['tool_choice']=='none':return self.reply()
            return self.reply(None,[{'id':str(len(payloads)),'type':'function','function':{
                'name':'search_rtl_batch','arguments':json.dumps({'queries':[{'query':'value'}]*8})}}])
        with patch.object(generation,'send_completion',side_effect=send):
            _,info=generation.request_model(args,'TASK',self.root,Records(self.root))
        self.assertEqual(len(payloads),9)
        self.assertEqual(info['task_tool_calls'],64)
        self.assertEqual(totals(self.root)['usage_reported']['total_tokens'],108)
        self.assertNotIn('PRIVATE_PROTOCOL_FIELD',json.dumps(payloads))
        self.assertTrue(all(m['role']=='user' for p in payloads for m in p['messages']))
        # Repeated queries are retained in the audit, but only one copy of
        # identical observed results is sent to the final authoring request.
        packet=json.loads((self.root/'evidence-1-8.json').read_text())
        self.assertEqual(len(packet['observations']),1)
        saved=''.join(f.read_text() for f in self.root.glob('*.json*'))
        self.assertNotIn('PRIVATE_PROTOCOL_FIELD',saved)

    def test_generation_enters_local_repair_without_new_rtl_or_rag_dump(self):
        design_path=Path(__file__).parent/'tests/fixtures/tiny_design.json'
        modinfo=self.root/'modinfo.txt';divider='='*80
        modinfo.write_text(f'{divider}\nModule : tiny_external\n{divider}\n  10 0/1 result <= payload;\n')
        response=goal_response('goal','io.valid')['ltl']
        payloads=[]
        def send(payload,timeout):
            payloads.append(json.loads(json.dumps(payload)));return self.reply(response)
        reports=[({'phase':'typecheck','ok':False,'errors':[{'file':'model.ltl','kind':'Syntax Error','message':'synthetic compiler diagnostic'}]},''),
                 ({'phase':'solve','ok':True,'result':{'status':'generated','goals':[]}},'')]
        with patch.object(generation,'send_completion',side_effect=send),patch.object(generation,'harness',side_effect=reports), \
                redirect_stdout(io.StringIO()),redirect_stderr(io.StringIO()):
            result=generation.main(['--design',str(design_path),'--modinfo',str(modinfo),'--out',str(self.root/'run')])
        self.assertEqual(result,0);self.assertEqual(len(payloads),2)
        names=lambda p:{t['function']['name'] for t in p['tools']}
        self.assertIn('read_rtl_batch',names(payloads[0]))
        self.assertEqual(names(payloads[1]),{'read_framework','read_diagnostics'})
        self.assertIn(response,payloads[1]['messages'][1]['content'])
        first=payloads[0]['messages'][1]['content']
        self.assertNotIn('object FrameworkUTExample extends',first)
        raw=json.loads((self.root/'run/attempt-2/task-context.json').read_text())
        self.assertEqual(raw['request_mode'],'local-source-repair-v1')

    def test_retrieval_handoff_starts_one_clean_tools_disabled_author(self):
        args=SimpleNamespace(model='test',temperature=0,timeout=1,request_retries=1,
            task_context=self.context,rvprobe_skill_snapshot=snapshot())
        call={'id':'read-1','type':'function','function':{'name':'read_rtl',
              'arguments':json.dumps({'file_id':'rtl_0001','start_line':1,'line_count':1})}}
        # Backwards-compatible READY is the only explicit handoff. Other text
        # goes to the normal LTL source validator, not a silent extra request.
        replies=[self.reply(None,[call]),self.reply('READY'),self.reply('FINAL_LTL')]
        payloads=[]
        def send(payload,timeout):
            payloads.append(json.loads(json.dumps(payload)));return replies.pop(0)
        with patch.object(generation,'send_completion',side_effect=send):
            raw,info=generation.request_model(args,'SPEC_IO',self.root,Records(self.root))
        self.assertEqual(raw,'FINAL_LTL');self.assertEqual(info['http_requests'],3)
        self.assertIn('output the requested LTL now',payloads[1]['messages'][-1]['content'])
        self.assertEqual(payloads[-1]['tool_choice'],'none')
        self.assertFalse(any(m['role']=='assistant' for m in payloads[-1]['messages']))
        self.assertIn('module target',json.dumps(payloads[-1]))
        self.assertEqual(totals(self.root)['usage_reported']['total_tokens'],36)

    def test_incomplete_retrieval_handoff_does_not_start_authoring(self):
        args=SimpleNamespace(model='test',temperature=0,timeout=1,request_retries=3,
            task_context=self.context,rvprobe_skill_snapshot=snapshot())
        call={'id':'read-1','type':'function','function':{'name':'list_rtl','arguments':'{}'}}
        replies=[self.reply(None,[call]),self.reply(None)]
        with patch.object(generation,'send_completion',side_effect=replies) as send:
            with self.assertRaisesRegex(RuntimeError,'incomplete evidence handoff'):
                generation.request_model(args,'SPEC_IO',self.root,Records(self.root))
        self.assertEqual(send.call_count,2)
        self.assertEqual(totals(self.root)['usage_reported']['total_tokens'],24)

    def test_campaign_gate_waits_for_response_then_prevents_the_next_paid_call(self):
        args=SimpleNamespace(model='test',temperature=0,timeout=1,request_retries=3,
            task_context=self.context,rvprobe_skill_snapshot=snapshot())
        flag=self.root/'stop.json'
        call={'id':'read-1','type':'function','function':{'name':'list_rtl','arguments':'{}'}}
        def send(payload,timeout):
            flag.write_text('{"reason":"peer pilot failed"}')
            return self.reply(None,[call])
        with patch.dict(os.environ,{'RVPROBE_STOP_NEW_MODEL_REQUESTS':str(flag)}), \
                patch.object(generation,'send_completion',side_effect=send) as model:
            with self.assertRaisesRegex(RuntimeError,'campaign_gate_stop'):
                generation.request_model(args,'SPEC_IO',self.root,Records(self.root))
        self.assertEqual(model.call_count,1)
        self.assertEqual(totals(self.root)['usage_reported']['total_tokens'],12)
        self.assertTrue(totals(self.root)['token_accounting_complete'])


if __name__=='__main__':unittest.main()
