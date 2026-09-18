import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from report_rvprobe_rerun import export
from run_records import save


class RerunReport(unittest.TestCase):
    def fixture(self,root,completion):
        save(root/'progress.json',dict(status='finished',designs={'alu':{'status':'completed'}},
            rvprobe_generation_options={'max_tokens':393216,'request_timeout_seconds':3600}))
        old=root/'original.json'
        save(old,{'rows':[dict(method='rvprobe',design='alu',status='failed',coverage=90,
            tokens=100,rounds=1,seconds=10,summary='immutable-old-summary')]})
        directory=root/'alu/flow/paired'
        save(directory/'summary.json',dict(status='completed',baseline={'score':80},arms={
            'rvprobe':dict(status='completed',rounds=[{'round':1}],final={'score':95},elapsed_seconds=20)}))
        path=directory/'rvprobe/round-1/generation/events.jsonl';path.parent.mkdir(parents=True)
        path.write_text(json.dumps(dict(id='req1',phase='model-request',status='ok',
            requested_max_tokens=393216,request_timeout_seconds=3600,finish_reason='stop',
            usage={'prompt_tokens':10,'completion_tokens':completion,'total_tokens':completion+10}))+'\n')
        return old,path

    def test_preserves_reference_and_distinguishes_requested_from_observed_limit(self):
        for count,above in ((100,0),(70000,1)):
            with self.subTest(count=count),TemporaryDirectory() as tmp:
                root=Path(tmp);old,_=self.fixture(root,count);original=old.read_bytes()
                result=export(root,old);row=result['rows'][0]
                self.assertEqual((row['coverage_gain'],row['tokens'],row['requests_above_64k']),(5,count+10,above))
                self.assertEqual(row['max_observed_completion_tokens'],count)
                self.assertEqual(old.read_bytes(),original)
                self.assertTrue((root/'budget-comparison.csv').is_file())

    def test_rejects_unexpected_actual_request_budget(self):
        with TemporaryDirectory() as tmp:
            root=Path(tmp);old,path=self.fixture(root,100)
            event=json.loads(path.read_text());event['requested_max_tokens']=65536
            path.write_text(json.dumps(event)+'\n')
            with self.assertRaisesRegex(ValueError,'request budget'):export(root,old)


if __name__=='__main__':unittest.main()
