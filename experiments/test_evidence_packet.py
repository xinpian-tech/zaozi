import json
import tempfile
import unittest
from copy import deepcopy
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from evidence_packet import packet, request_messages
from sequence_framework import load_design
from task_context import TaskContext, RepairContext


class EvidencePacketTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.source = Path(self.tmp.name)/'dut.sv'
        self.source.write_text('first;\nunseen;\nthird;\n')
        self.design = replace(load_design(Path(__file__).parent/'tests/fixtures/tiny_design.json'),
                              sources=(self.source,), include_dirs=())
        self.context = TaskContext(self.design)

    def read(self, line):
        args = {'file_id':'rtl_0001','start_line':line,'line_count':1}
        return {'name':'read_rtl','arguments':args,'result':self.context.dispatch('read_rtl',args)}

    def test_merges_only_observed_ranges_and_never_mutates_audit(self):
        observations = [self.read(1), self.read(3), self.read(1)]
        before = deepcopy(observations)
        value = packet(self.context, self.context.initial_evidence(), observations)
        self.assertEqual(observations, before)
        self.assertEqual([r['text'] for r in value['rtl_ranges']], ['1: first;\n','3: third;\n'])
        self.assertNotIn('unseen', json.dumps(value))
        self.assertEqual(len(value['observations']), 2)
        self.assertIn('next_offset', value['observations'][0]['result'])

    def test_large_history_retains_prefix_and_exact_unicode_continuation(self):
        self.source.write_text('中文一;\n中文二;\n中文三;\n')
        first = TaskContext(self.design)
        whole = first.dispatch('read_rtl', {'file_id':'rtl_0001'})
        later = TaskContext(self.design, history={'coverage_round':2, 'rtl_evidence':[whole]})
        with patch('rtl_evidence.INLINE_LIMIT', 9):
            initial = later.initial_evidence()
        cached = initial['previously_read_rtl']
        self.assertFalse(cached['inline'])
        self.assertEqual(cached['inline_text_characters'], 9)
        prefix = cached['inline_ranges'][0]
        self.assertEqual(prefix['text'], whole['text'][:9])
        offset = prefix['next_offset']
        rest = later.dispatch('read_rtl', {'file_id':'rtl_0001','offset':offset})
        self.assertEqual(prefix['text'] + rest['text'], whole['text'])
        value = packet(later, initial, [{'name':'read_rtl','arguments':{'file_id':'rtl_0001','offset':offset},'result':rest}])
        self.assertEqual(value['previously_read_rtl']['inline_ranges'][0]['next_offset'], offset)
        self.assertEqual(len(value['rtl_ranges']), 1)
        self.assertEqual(value['rtl_ranges'][0]['text'], whole['text'])

    def test_small_history_and_duplicate_new_read_have_one_body(self):
        read = self.read(3)
        later = TaskContext(self.design, history={'rtl_evidence':[read['result']]})
        value = packet(later, later.initial_evidence(), [read])
        self.assertEqual(json.dumps(value).count('3: third;'), 1)

    def test_forged_current_read_is_rejected_before_another_request(self):
        read = self.read(1)
        read['result']['text'] = 'fabricated evidence'
        with self.assertRaisesRegex(ValueError, 'does not match frozen source'):
            packet(self.context, self.context.initial_evidence(), [read])

    def test_errors_and_coverage_are_retained_and_repair_has_no_rtl(self):
        observations = [{'name':'read_coverage','arguments':{},'result':{'text':'ALL_GAPS','next_offset':200}},
                        {'name':'read_rtl','arguments':{},'result':{'error':'missing file'}}]
        value = packet(self.context, self.context.initial_evidence(), observations)
        self.assertEqual(value['observations'], observations)
        repair = RepairContext(self.context, ['SYNTAX_ERROR'])
        result = packet(repair, repair.initial_evidence(), [{'name':'read_diagnostics','arguments':{},
                        'result':repair.dispatch('read_diagnostics',{})}])
        self.assertNotIn('rtl_ranges', result)
        self.assertIn('SYNTAX_ERROR', json.dumps(result))

    def test_fresh_author_request_has_no_protocol_fragments_and_preserves_inputs(self):
        base = [{'role':'user','content':'FULL_SKILL'}, {'role':'user','content':'FULL_SPEC_IO'}]
        value = packet(self.context, self.context.initial_evidence(), [self.read(1)])
        messages = request_messages(base, value, 0, 0, final=True)
        self.assertEqual(messages[:2], base)
        self.assertTrue(all(m['role']=='user' for m in messages))
        self.assertNotIn('tool_call_id', json.dumps(messages))
        self.assertNotIn('reasoning_content', json.dumps(messages))
        self.assertIn('first;', json.dumps(messages))
        self.assertIn('budget is now closed', messages[-1]['content'])


if __name__ == '__main__':
    unittest.main()
