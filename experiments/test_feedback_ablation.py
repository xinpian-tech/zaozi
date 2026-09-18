import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest

from feedback_ablation import redact_feedback,NoCoverageContext,remove_coverage_instructions,GENERIC_ERROR
from sequence_framework import load_design
from sequence_experiment import build_prompt
from task_context import TaskContext, RepairContext
from directed_experiment import coverage_loop
from coverage_flow import paired_loop


class FeedbackAblation(unittest.TestCase):
    def test_no_coverage_disables_all_routes_but_keeps_environment_and_history(self):
        design=load_design(Path(__file__).parent/'tests/fixtures/tiny_design.json')
        value={'score':91.234,'bins':{'line':[10,9]},'gaps':[{'type':'line','text':'SECRET_GAP'}],
               'modinfo':'SECRET_PATH','progress':{'gain':1},'intent_batch_limit':4,
               'shared_context':{'trusted_environment':{'note':'FIXED_ENV'},'baseline':{'sequence_count':1}}}
        clean=redact_feedback(value,'no_coverage')
        context=NoCoverageContext(TaskContext(design,clean,{'coverage_round':2,'ltls':[{'source':'OWN_LTL'}]}))
        evidence=json.dumps(context.initial_evidence())
        for secret in ('91.234','SECRET_GAP','SECRET_PATH','gap_count'):
            self.assertNotIn(secret,evidence)
        self.assertIn('FIXED_ENV',evidence);self.assertIn('OWN_LTL',evidence)
        self.assertNotIn('read_coverage',json.dumps(context.tools))
        for name,args in [('read_coverage',{}),('read_context',{'topic':'coverage'}),('read_context',{'topic':'coverage_reports'})]:
            with self.assertRaisesRegex(ValueError,'unavailable'):context.dispatch(name,args)
        prompt=build_prompt([],design.sources[0],'120s',design=design,coverage_feedback=clean)
        prompt=remove_coverage_instructions(prompt)
        self.assertNotIn('read_coverage()',prompt)
        self.assertNotIn('Choose goals from spec and measured gaps',prompt)
        self.assertIn(design.context,prompt)
        self.assertIn('Initialize any observed storage through legal IO activity first',prompt)

    def test_no_coverage_retains_runtime_repair_feedback(self):
        design=load_design(Path(__file__).parent/'tests/fixtures/tiny_design.json')
        feedback=redact_feedback({'score':99,'runtime_failure':{'error':'REPLAY_DIAGNOSTIC'},
            'rejected_model_candidate':{'ltl':'OWN_LTL'}},'no_coverage')
        context=NoCoverageContext(TaskContext(design,feedback))
        evidence=context.initial_evidence()
        self.assertEqual(evidence['validation_feedback']['runtime_failure']['error'],'REPLAY_DIAGNOSTIC')
        self.assertNotIn('score',json.dumps(evidence))

    def test_no_diagnostics_keeps_candidate_but_redacts_all_runtime_details(self):
        value={'score':91,'runtime_failure':{'error':'SECRET_ERROR','diagnostics':{'log':'SECRET_LOG'}},
               'repair_instruction':'SECRET_HINT','rejected_model_candidate':{'ltl':'OWN_LTL'}}
        clean=redact_feedback(value,'no_diagnostics')
        self.assertNotIn('SECRET',json.dumps(clean))
        self.assertEqual(clean['score'],91)
        self.assertEqual(clean['rejected_model_candidate'],{'ltl':'OWN_LTL'})
        design=load_design(Path(__file__).parent/'tests/fixtures/tiny_design.json')
        context=RepairContext(TaskContext(design),GENERIC_ERROR)
        self.assertNotIn('SECRET',json.dumps(context.dispatch('read_diagnostics',{})))

    def test_stalled_and_empty_rounds_have_equal_opportunities_in_both_loops(self):
        baseline={'score':100,'percent':{'line':100},'bins':{'line':[10,10]},'modules':['test'],'uncovered':[]}
        for kind in ('paired','direct'):
            calls=[]
            def generate(*args):calls.append(1);return {'stop':'model_stop'}
            with TemporaryDirectory() as tmp:
                if kind=='paired':
                    result=paired_loop({'fingerprint':'test','sequences':[]},Path(tmp),lambda *a:baseline,generate,
                        arms=('rvprobe',),fixed_rounds=True)
                    result=result['arms']['rvprobe']
                else:
                    backend=SimpleNamespace(method='directed_sv_constraint',history=[],generate=generate)
                    result=coverage_loop({'sequences':[]},Path(tmp),lambda *a:baseline,backend,fixed_rounds=True)
            self.assertEqual(len(calls),3)
            self.assertEqual(len(result['rounds']),3)
            self.assertEqual(result['final']['score'],100)
            self.assertEqual(result['status'],'completed')


if __name__=='__main__':unittest.main()
