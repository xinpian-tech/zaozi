import json
from pathlib import Path
import tempfile
import unittest
from dataclasses import replace
from unittest.mock import patch
from run_records import save
from sequence_framework import load_design
from task_context import TaskContext
from rtl_evidence import collect,collect_generation,validate_and_merge,index,project_inline


class RtlEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.source=self.root/'dut.sv'
        self.source.write_text('first;\nunseen;\nthird;\n')
        self.design=replace(load_design(Path(__file__).parent/'tests/fixtures/tiny_design.json'),
            sources=(self.source,),include_dirs=())
        self.context=TaskContext(self.design)

    def read(self,line,count=1):
        return self.context.dispatch('read_rtl',{'file_id':'rtl_0001','start_line':line,'line_count':count})

    def test_merge_deduplicates_only_seen_overlapping_ranges(self):
        rows=[self.read(1),self.read(3),self.read(1)]
        merged=validate_and_merge(self.context.files,rows)
        self.assertEqual([r['text'] for r in merged],['1: first;\n','3: third;\n'])
        self.assertNotIn('unseen',json.dumps(merged))
        merged=validate_and_merge(self.context.files,rows+[self.read(1,2)])
        self.assertEqual(len(merged),1)
        self.assertEqual(merged[0]['text'],self.context.files['rtl_0001']['text'])

    def test_initial_first_round_has_no_rtl_and_later_only_verified_prior_reads(self):
        self.assertNotIn('previously_read_rtl',self.context.initial_evidence())
        history={'ltls':[{'source':'own accepted UT'}],'rtl_evidence':[self.read(3)]}
        context=TaskContext(self.design,history=history)
        cache=context.initial_evidence()['previously_read_rtl']
        self.assertTrue(cache['inline']);self.assertEqual(cache['ranges'][0]['text'],'3: third;\n')
        self.assertEqual(json.loads(context.topics['history']),{'ltls':history['ltls']})
        self.assertIn('rtl_evidence',history)
        with patch('rtl_evidence.INLINE_LIMIT',1):
            cache=context.initial_evidence()['previously_read_rtl']
        self.assertFalse(cache['inline']);self.assertNotIn('text',cache['ranges'][0])
        self.assertIn('3: third;',context.dispatch('read_context',{'topic':'rtl_history'})['text'])

    def test_reject_changed_source_hash_forged_text_and_offsets(self):
        row=self.read(1)
        for change in ({'sha256':'bad'},{'text':'invented answer'},{'offset':True},
                       {'offset':99999},{'file_id':'other-run-file'}):
            with self.subTest(change=change),self.assertRaises(ValueError):
                validate_and_merge(self.context.files,[row|change])
        self.source.write_text('changed;\n')
        with self.assertRaises(ValueError):TaskContext(self.design,history={'rtl_evidence':[row]})

    def test_collect_only_prior_round_read_results_not_goals_framework_or_other_runs(self):
        def record(root,number,name,result):
            save(root/f'round-{number}/generation/attempt-1/task-tool-1-1.json',
                 {'tool_call':{'function':{'name':name}},'result':result})
        record(self.root,1,'read_rtl',self.read(1))
        record(self.root,2,'read_framework',{'text':'NOT_RTL'})
        record(self.root,3,'read_rtl_batch',{'ranges':[self.read(3)]})
        record(self.root/'other-run',1,'read_rtl',self.read(2))
        self.assertEqual(len(collect(self.root,3)),1)
        rows=collect(self.root,4);self.assertEqual([r['source_round'] for r in rows],[1,3])
        self.assertNotIn('unseen',json.dumps(rows));self.assertNotIn('NOT_RTL',json.dumps(rows))

    def test_relevant_small_file_can_be_requested_in_one_large_range(self):
        self.source.write_text('wire x;\n'*600)
        context=TaskContext(self.design)
        result=context.dispatch('read_rtl_batch',{'ranges':[{'file_id':'rtl_0001','start_line':1,'line_count':600}]})
        self.assertEqual(result['ranges'][0]['text'],context.files['rtl_0001']['text'])
        self.assertIsNone(result['ranges'][0]['next_offset'])

    def test_inspect_source_is_reused_but_search_previews_are_not(self):
        generation=self.root/'round-1/generation'
        result=self.context.dispatch('inspect_rtl_batch',{
            'queries':[{'query':'third'}],'context_lines':0})
        save(generation/'attempt-1/task-tool-1-1.json',
             {'tool_call':{'function':{'name':'inspect_rtl_batch'}},'result':result})
        save(generation/'attempt-1/task-tool-1-2.json',
             {'tool_call':{'function':{'name':'search_rtl'}},
              'result':{'matches':[{'preview':'NOT_READ'}]}})
        rows=collect_generation(generation)
        self.assertEqual([r['text'] for r in rows],['3: third;\n'])
        self.assertEqual(collect(self.root,2),[{**r,'source_round':1} for r in rows])
        self.assertEqual(len(validate_and_merge(self.context.files,rows)),1)
        self.assertNotIn('NOT_READ',json.dumps(rows))

    def test_compact_index_and_recent_projection_preserve_access_metadata(self):
        rows=validate_and_merge(self.context.files,[self.read(1),self.read(2),self.read(3)])
        compact=index(rows)
        self.assertFalse(compact['inline'])
        self.assertNotIn('text',compact['ranges'][0])
        projected=project_inline(rows,limit=10)
        self.assertLessEqual(sum(len(r['text']) for r in projected),10)
        self.assertTrue(any(r.get('omitted_prefix',0)>0 for r in projected))

    def test_later_round_packet_is_complete_and_duplicate_tools_are_not_exposed(self):
        feedback={'gaps':[{'type':'toggle','signal':'signal','direction':'0->1'}],'score':12}
        history={'coverage_round':2,'ltls':[{'source':'own accepted UT'}],'rtl_evidence':[self.read(1)]}
        context=TaskContext(self.design,feedback,history)
        packet=context.initial_evidence()
        from coverage_table import unpack
        self.assertTrue(packet['current_feedback']['complete'])
        compact=packet['current_feedback']['coverage']
        self.assertEqual({**compact,'gaps':unpack(compact['gaps'])},feedback)
        self.assertNotIn('score',packet['coverage'])  # Present exactly once in current_feedback.
        self.assertEqual(packet['accepted_ltl']['ltls'],history['ltls'])
        names={t['function']['name'] for t in context.tools}
        self.assertNotIn('read_coverage',names);self.assertNotIn('list_rtl',names)
        self.assertIn('read_rtl',names)
        schema=next(t for t in context.tools if t['function']['name']=='read_context')
        topics=schema['function']['parameters']['properties']['topic']['enum']
        self.assertNotIn('coverage',topics);self.assertNotIn('history',topics)
        self.assertNotIn('current_feedback',TaskContext(self.design,feedback).initial_evidence())

    def test_large_later_feedback_and_history_keep_lossless_read_tools(self):
        feedback={'gaps':[{'context':'x'*40000}]}
        context=TaskContext(self.design,feedback,{'coverage_round':2,'ltls':[{'source':'y'*50000}]})
        packet=context.initial_evidence()
        self.assertNotIn('current_feedback',packet);self.assertNotIn('accepted_ltl',packet)
        names={t['function']['name'] for t in context.tools}
        self.assertIn('read_coverage',names)
        self.assertEqual(json.loads(context.topics['coverage']),feedback)
        self.assertIn('y'*50000,context.topics['history'])


if __name__=='__main__':unittest.main()
