"""No-model regressions for batched evidence, local repairs and source fidelity."""
import hashlib
import json
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
        self.assertEqual(value['rtl'],self.context.dispatch('list_rtl',{}))
        text=json.dumps(value)
        self.assertIn('PHYSICAL_CONDITION',text)
        for sentinel in ('RTL_SNIPPET','module target','HAVEN_NATIVE_DSL'):self.assertNotIn(sentinel,text)
        self.assertIn('RTL_SNIPPET',self.context.topics['coverage'])

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

    @staticmethod
    def reply(content='FINAL',calls=None):
        return {'model':'test','choices':[{'message':{'role':'assistant','content':content,
            **({'tool_calls':calls,'reasoning_content':'PRIVATE_PROTOCOL_FIELD'} if calls else {})},
            'finish_reason':'tool_calls' if calls else 'stop'}],
            'usage':{'prompt_tokens':10,'completion_tokens':2,'total_tokens':12}}

    def test_batches_charge_underlying_queries_and_preserve_provider_protocol(self):
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
        self.assertIn('PRIVATE_PROTOCOL_FIELD',json.dumps(payloads[-1]))
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
        reports=[({'phase':'typecheck','ok':False,'errors':[{'kind':'Syntax Error','message':'synthetic compiler diagnostic'}]},''),
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


if __name__=='__main__':unittest.main()
