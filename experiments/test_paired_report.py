import tempfile
import unittest
from pathlib import Path
from report_paired_batch import collect, run_record
from run_records import save


class PairedReportTests(unittest.TestCase):
    def test_failed_pair_is_terminal_not_successful_and_costs_not_doubled(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            run=root/'design6-probe-complete-pair-20260101-v1'
            arm={'status':'completed','rounds':[], 'costs':{'usage_reported':{'total_tokens':12},'token_accounting_complete':True}}
            save(run/'paired/summary.json',{'status':'failed','arms':{'haven':arm,'rvprobe':{**arm,'status':'failed'}}})
            setup=root/'design6-probe-baseline-20260101-v1'
            save(setup/'stage1-costs.json',{'native_token_tracker':{'total':{'total_tokens':0}}})
            save(setup/'usage-correction.json',{'total_tokens':10})
            result=collect(root,'20260101',[6])
            self.assertEqual(result['terminal_pairs'],1)
            self.assertEqual(result['successful_pairs'],0)
            self.assertEqual(result['known_tokens_all_attempts_and_setup'],34)

    def test_summary_takes_precedence_over_stale_progress(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)
            save(path/'paired/progress.json',{'status':'running'})
            save(path/'paired/summary.json',{'status':'failed','error':'baseline failed'})
            result=run_record(path)
            self.assertEqual(result['status'],'failed')
            self.assertFalse(result['terminal_pair'])
            self.assertEqual(result['arms']['haven']['status'],'not_started')
