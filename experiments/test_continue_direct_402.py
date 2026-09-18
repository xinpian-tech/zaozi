import backend_imports
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from continue_direct_402 import cumulative_costs
from directed_experiment import coverage_loop


class Continuation(unittest.TestCase):
    def test_usage_unknown_is_not_erased_after_successful_retry(self):
        old=dict(requests=3,requests_without_usage=1,token_accounting_complete=False,
                 usage_reported=dict(prompt_tokens=100,completion_tokens=50,total_tokens=150))
        new=dict(requests=1,requests_without_usage=0,token_accounting_complete=True,
                 usage_reported=dict(prompt_tokens=20,completion_tokens=10,total_tokens=30))
        result=cumulative_costs(old,new)
        self.assertEqual(result['usage_reported']['total_tokens'],180)
        self.assertEqual(result['requests'],4)
        self.assertFalse(result['token_accounting_complete'])
        self.assertEqual(result['requests_without_usage'],1)

    def test_resume_preserves_round_budget_and_skips_baseline(self):
        coverage={'score':30,'percent':{'line':30},'bins':{'line':[10,3]},'modules':['x'],'uncovered':[]}
        restored=dict(current=coverage,previous=coverage,best=coverage,baseline=coverage,
            sequences=[],frames=[],rounds=[{'round':1},{'round':2}],failed_round=3)
        calls=[]
        def generate(rd,*args):calls.append(rd.name);return {'stop':'model_stop'}
        backend=SimpleNamespace(method='directed_stimulus',generate=generate)
        def simulate(*args):raise AssertionError('accepted baseline/round must not be replayed')
        with TemporaryDirectory() as tmp:
            result=coverage_loop({'sequences':[]},Path(tmp),simulate,backend,restored=restored)
        self.assertEqual(calls,['round-3'])
        self.assertEqual(result['status'],'completed')
        self.assertEqual(len(result['rounds']),2)


if __name__=='__main__':unittest.main()
