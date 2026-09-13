import unittest
import tempfile
from pathlib import Path
from types import SimpleNamespace
from recover_stage1_dsl import compact_rtl_evidence, merge_model_dsl, preserve_dispatch, measured_log


class RecoveryTests(unittest.TestCase):
    def test_rtl_projection_keeps_strings_and_code(self):
        source = '/* history\n */ module x; // comment\nstring s="//not a comment";\n`define W 8\nendmodule'
        result = compact_rtl_evidence(source)
        self.assertEqual(result, 'module x;\nstring s="//not a comment";\n`define W 8\nendmodule')

    def test_full_prompt_bundle_cannot_be_repeated_as_a_simulation_log(self):
        from run_records import save
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'feedback.json'
            save(path,{'original_rtl':{},'previous_model_dsl':{}})
            with self.assertRaisesRegex(ValueError,'previous full prompt bundle'):
                measured_log(path)
            save(path,{'status':'simulation_failed'})
            self.assertIn('simulation_failed',measured_log(path))

    def response(self, *rows):
        return SimpleNamespace(module_name='probe',sequences=[
            SimpleNamespace(name=r['name'],model_dump=lambda r=r:r) for r in rows])

    def test_merge_retains_unchanged_baseline_and_checks(self):
        one={'name':'one','steps':[{'type':'poll'}]}
        two={'name':'two','steps':[]}
        before={'module_name':'probe','sequences':[one,two]}
        changed={**one,'description':'model changed this sequence'}
        merged=merge_model_dsl(before,self.response(changed))
        self.assertIs(merged['sequences'][1],two)
        self.assertIs(before['sequences'][0],one)
        for rows in ([two,two],[{'name':'unknown'}],[{'name':'one','steps':[]}]):
            with self.assertRaises(ValueError): merge_model_dsl(before,self.response(*rows))

    def test_refresh_preserves_exact_dispatch_tasks(self):
        before='task env_dispatch_send(item); q.put(item); endtask\ntask env_dispatch_wait(); ack.get(x); endtask'
        replacement='class driver; task run(); seq_item_port.get_next_item(req); seq_item_port.item_done(); endtask endclass'
        result=preserve_dispatch(before,replacement)
        self.assertIn(before,result)
        self.assertIn('env_dispatch_send(req);',result)
        self.assertIn('env_dispatch_wait();\nseq_item_port.item_done();',result)
