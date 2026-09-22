"""Bounded SVA feedback routing; no provider or EDA invocation."""
import backend_imports
from copy import deepcopy
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from directed_baselines import DirectedBackend, ModelOutputError, check_sva_repair, sva_shortfall
from directed_experiment import coverage_loop
from test_directed_baselines import fixture


class SvaRepairTests(unittest.TestCase):
    def exercise(self, statuses, responses=None):
        design,config=fixture(); design.context='synthetic'
        intents=[dict(label='good',intent='first output',property='@(posedge clk) req ##1 ack'),
                 dict(label='bad',intent='second output',property='@(posedge clk) !req ##1 ack')]
        args=SimpleNamespace(attempts=3)
        backend=DirectedBackend('directed_sva',{'fingerprint':'f','sequences':[]},design,config,None,args)
        prompts=[]; materialized=[]
        def dialogue(prompt,context,args,directory):
            directory.mkdir(parents=True)
            prompts.append(prompt)
            raw=(responses[len(prompts)-1] if responses else json.dumps({'intents':intents}))
            (directory/'prompt.txt').write_text(prompt)
            return raw
        def solve(*args):
            index=len(prompts)-1
            goals=[dict(label=label,status=status,detail={})
                   for label,status in zip(('good','bad'),statuses[index])]
            return {'version':index},goals
        def materialize(directory,job,goals,selected,ad,ordinal):
            materialized.append((job,goals,selected,ad.name))
            return {'sequences':['synthetic'], 'frames':[], 'metadata':{}}
        with TemporaryDirectory() as tmp, patch('directed_baselines.TaskContext'), \
             patch('directed_baselines.direct_dialogue',side_effect=dialogue), \
             patch('directed_baselines.solve_sva',side_effect=solve), \
             patch('rtl_evidence.collect',return_value={}), \
             patch.object(backend,'materialize_sva',side_effect=materialize):
            result=backend.generate(Path(tmp)/'round-1',{},[],0)
        return prompts,materialized,result

    def test_unreachable_enters_repair_and_success_is_materialized(self):
        prompts,selected,_=self.exercise([['generated','unreachable'],['generated','generated']])
        self.assertEqual(len(prompts),2)
        self.assertIn('No witness exists',prompts[1])
        self.assertIn('Previous candidate',prompts[1])
        self.assertEqual(selected[0][3],'attempt-2')

    def test_timeout_is_not_unsat_and_one_stalled_resource_repair_retains_partial(self):
        prompts,selected,_=self.exercise([['generated','undetermined']]*2)
        self.assertEqual(len(prompts),2)
        self.assertIn('not UNSAT',prompts[1])
        self.assertEqual(selected[0][3],'attempt-1')
        self.assertEqual(selected[0][1][0]['status'],'generated')

    def test_unsat_uses_full_budget_and_all_failures_remain_unresolved(self):
        prompts,selected,_=self.exercise([['unreachable','unreachable']]*3)
        self.assertEqual(len(prompts),3)
        self.assertTrue(all(g['status']=='unreachable' for g in selected[0][1]))

    def test_stop_during_repair_does_not_discard_valid_prior_goals(self):
        initial=json.dumps({'intents':[dict(label='good',intent='first output',property='req'),
                                      dict(label='bad',intent='second output',property='ack')]})
        prompts,selected,_=self.exercise([['generated','unreachable']], [initial,'STOP','STOP'])
        self.assertEqual(len(prompts),3)
        self.assertEqual(selected[0][3],'attempt-1')

    def test_repair_cannot_drop_rename_or_edit_solved_goals(self):
        original=[dict(label='a',intent='observe ack',property='req ##1 ack')]
        for field,value in [('label','b'),('intent','input only'),('property','req')]:
            changed=deepcopy(original);changed[0][field]=value
            with self.assertRaises(ModelOutputError):check_sva_repair(changed,original,{'a'})
        self.assertFalse(sva_shortfall([{'label':'a','status':'unknown'}])[0]['unreachability_proven'])
        with self.assertRaises(RuntimeError):sva_shortfall([{'label':'a','status':'backend_crash'}])

    def test_generation_runtime_errors_have_bounded_explicit_repair(self):
        baseline={'score':20,'percent':{'line':20},'bins':{'line':[10,2]},'modules':['example'],'uncovered':[]}
        for kind,allowed,expected in [('model_candidate_error',True,2),
                ('replay_setup_failure',False,1),('candidate_or_unclassified_failure',True,1)]:
            calls=[]
            def generate(directory,feedback,*args):
                calls.append(feedback)
                error=ValueError('measured candidate failure')
                error.diagnostics={'kind':kind,'model_repair_allowed':allowed}
                error.repair_context={'intents':[{'label':'same'}]}
                raise error
            backend=SimpleNamespace(method='directed_sva',history=[],generate=generate)
            with TemporaryDirectory() as tmp:
                result=coverage_loop({'sequences':[]},Path(tmp),lambda *a:baseline,backend)
            self.assertEqual(len(calls),expected)
            self.assertEqual(result['status'],'failed')
            self.assertEqual(result['final']['score'],20)
            if expected==2:self.assertIn('rejected_model_candidate',calls[1])


if __name__=='__main__':unittest.main()
