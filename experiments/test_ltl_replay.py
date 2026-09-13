"""Observer extraction and fail-closed replay provenance (no EDA/provider needed)."""
import hashlib
import unittest
import json
import tempfile
from pathlib import Path
from copy import deepcopy
from ltl_replay import POLICY, attach, monitor, install, check_hit
from test_hardening import SV, FIXTURES
from sequence_framework import load_design


def metadata(source=SV):
    return dict(policy=POLICY, source=source, top='TestUT', label='target',
                sha256=hashlib.sha256(source.encode()).hexdigest())


class NativeReplayTest(unittest.TestCase):
    def setUp(self):
        self.design = load_design(FIXTURES/'tiny_design.json')

    def test_observer_has_no_dut_and_uses_live_outputs(self):
        source = SV.replace('valid & payload <= 8\'h7', 'valid & done_net & result_net == payload')
        _, code = monitor(metadata(source), self.design)
        self.assertNotIn('tiny_external dut', code)
        self.assertNotIn('result_net', code)
        self.assertIn('valid & done & result == payload', code)
        self.assertIn('disable iff (reset || !rvp_active)', code)
        self.assertNotIn('assign result = result;', code)

    def test_modified_source_and_forged_wrappers_rejected(self):
        meta = metadata(); meta['source'] += '\n'
        with self.assertRaisesRegex(ValueError,'source changed'): monitor(meta,self.design)
        with self.assertRaises(ValueError): monitor(metadata(SV.replace('.valid(valid)','.valid(1)')),self.design)

    def test_output_alias_chain_never_drives_observer_input(self):
        source=SV.replace('assign result = result_net;',
            'wire [7:0] copy; assign copy = result_net; assign result = copy;')
        _,code=monitor(metadata(source),self.design)
        self.assertIn('assign copy = result;',code)
        self.assertNotIn('assign result =',code)

    def test_selected_goal_is_bound_to_prepared_solver_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);selected=root/'target/selected';selected.mkdir(parents=True)
            original=root/'original.sv';original.write_text(SV)
            emitted=selected/'TestUT.sv';emitted.write_text(SV)
            (root/'prepared.json').write_text(json.dumps(dict(sv=str(original),svSha256=metadata()['sha256'],
                sourceSha256='author',fingerprint='job',labels=['target'],top='TestUT')))
            goal=dict(witnessFile=str(root/'target/jg/witness.vcd'),utModule='TestUT',
                      generationLabel='target',utSourceSha256='author',fingerprint='job')
            rows=[{}];attach(rows,goal);self.assertEqual(rows[0]['ltl']['label'],'target')
            emitted.write_text(SV.replace("valid & payload <= 8'h7","valid"))
            with self.assertRaisesRegex(ValueError,'selected LTL'):attach([{}],goal)
            emitted.write_text(SV);original.write_text(SV+'\n')
            with self.assertRaisesRegex(ValueError,'prepared solver job'):attach([{}],goal)

    def test_author_class_and_lowered_module_are_distinct(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); selected=root/'target/selected'; selected.mkdir(parents=True)
            source=SV.replace('module TestUT(', 'module TestUT_ab123456(')
            original=root/'original.sv'; original.write_text(source)
            (selected/'TestUT_ab123456.sv').write_text(source)
            (root/'prepared.json').write_text(json.dumps(dict(sv=str(original),
                svSha256=hashlib.sha256(source.encode()).hexdigest(),sourceSha256='author',
                fingerprint='job',labels=['target'],module='TestUT',top='TestUT_ab123456')))
            goal=dict(witnessFile=str(root/'target/jg/witness.vcd'),utModule='TestUT',
                      generationLabel='target',utSourceSha256='author',fingerprint='job')
            rows=[{}]; attach(rows,goal)
            self.assertEqual(rows[0]['ltl']['top'],'TestUT_ab123456')
            monitor(rows[0]['ltl'],self.design)
            from replay_saved_candidate import refresh_ltl_provenance
            (root/'summary.json').write_text(json.dumps({'result':{'goals':[goal]}}))
            old=deepcopy(rows[0]['ltl']); old['top']='TestUT'
            candidate={'sequences':['unchanged'],'frames':[{'ltl':old,'drive':{'valid':1}}]}
            fixed,count=refresh_ltl_provenance(candidate,root)
            self.assertEqual(count,1)
            self.assertEqual(fixed['sequences'],candidate['sequences'])
            self.assertEqual(fixed['frames'][0]['drive'],candidate['frames'][0]['drive'])
            self.assertEqual(candidate['frames'][0]['ltl']['top'],'TestUT')
            candidate['frames'][0]['ltl']['source']+='\n'
            with self.assertRaisesRegex(ValueError,'differs beyond'):
                refresh_ltl_provenance(candidate,root)
            goal['utModule']='different'
            with self.assertRaisesRegex(ValueError,'author module'): attach([{}],goal)

    def test_only_exact_goal_hit_counts(self):
        meta=metadata()
        exact=f'RVPROBE_LTL_HIT {meta["sha256"]} target'
        self.assertTrue(check_hit(meta,exact)['native_cover_hit'])
        for log in ('', 'compile '+exact, exact.replace('target','different')):
            with self.assertRaisesRegex(ValueError,'did not hold'):check_hit(meta,log)

    def test_legacy_and_multi_goal_schedules(self):
        components={'top':'module top; endmodule','filelist':''}
        before=deepcopy(components)
        self.assertIsNone(install(components,[],self.design))
        self.assertEqual(components,before)
        with self.assertRaisesRegex(ValueError,'isolated'):
            install(components,[{'segment':0,'ltl':metadata()},{'segment':1}],self.design)
        self.assertIsNotNone(install(components,[{'segment':0,'ltl':metadata()}],self.design))
        self.assertIn('.done(rvp_observed.done)',components['top'])


if __name__=='__main__':unittest.main()
