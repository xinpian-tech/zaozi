import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from fourway_report import export
from run_records import save


class FourWayReport(unittest.TestCase):
    def test_missing_child_error_uses_recorded_request_failure(self):
        with TemporaryDirectory() as tmp:
            root=Path(tmp);base=root/'rvprobe/ue_uart/flow/paired'
            save(base/'summary.json',{'status':'failed','arms':{'rvprobe':{
                'status':'failed','failed_round':3,'error':'subprocess exited 1'}}})
            generation=base/'rvprobe/round-3/generation'
            save(generation/'summary.json',{'status':'failed'})
            (generation/'events.jsonl').write_text(json.dumps(dict(
                phase='model-request',status='failed',error_type='IncompleteRead'))+'\n')
            report=export(root,root/'ethmac')
            row=next(r for r in report['rows'] if r['design']=='ue_uart' and r['method']=='rvprobe')
            self.assertEqual(row['error'],'IncompleteRead')
            self.assertEqual(row['outer_error'],'subprocess exited 1')

    def test_explicit_continuation_uses_cumulative_cost_without_overwriting_original(self):
        with TemporaryDirectory() as tmp:
            root=Path(tmp)/'suite';root.mkdir();ethmac=Path(tmp)/'ethmac'
            original=root/'directed_sva/sdram/summary.json'
            save(original,{'status':'failed','error':'HTTP 402'})
            (root/'results.md').write_text('original report')
            path=Path(tmp)/'continuation/cumulative-summary.json'
            save(path,{'status':'completed','prior_summary':str(original),'rounds':[{'round':1}],
                'final':{'score':80},'stage2_seconds':123,
                'costs':{'usage_reported':{'total_tokens':300},'token_accounting_complete':False},
                'prior_costs':{'usage_reported':{'total_tokens':100}},
                'incremental_costs':{'usage_reported':{'total_tokens':200}}})
            output=Path(tmp)/'report'
            report=export(root,ethmac,{'directed_sva/sdram':str(path)},output)
            row=next(r for r in report['rows'] if r['design']=='sdram' and r['method']=='directed_sva')
            self.assertEqual((row['tokens'],row['incremental_tokens'],row['seconds']),(300,200,123))
            self.assertEqual((root/'results.md').read_text(),'original report')
            self.assertTrue((output/'results.csv').is_file())
            with self.assertRaises(ValueError):export(root,ethmac,{'directed_sva/alu':str(path)},output)

    def test_failures_missing_usage_and_all_cells_remain_visible(self):
        with TemporaryDirectory() as tmp:
            root=Path(tmp)/'suite';root.mkdir();ethmac=Path(tmp)/'ethmac'
            save(root/'directed_sva/alu/summary.json',{'status':'failed','failed_round':2,'error':'timeout',
                'rounds':[{'round':1}],'final':{'score':42,'bins':{'line':[10,4]}},
                'costs':{'usage_reported':{'total_tokens':123},'requests':2,'token_accounting_complete':False}})
            save(root/'haven/progress.json',{'designs':{'alu':{'status':'running_haven'}}})
            report=export(root,ethmac)
            self.assertEqual(len(report['rows']),64)
            self.assertEqual(report['terminal_cells'],1)
            rows={(r['design'],r['method']):r for r in report['rows']}
            row=rows['alu','directed_sva']
            self.assertEqual((row['status'],row['coverage'],row['tokens'],row['attempted_rounds']),('failed',42,123,2))
            self.assertFalse(row['usage_complete'])
            self.assertEqual(rows['alu','haven']['status'],'running')
            self.assertIsNone(rows['aes','directed_sva']['tokens'])
            self.assertIn('123†',(root/'results.md').read_text())


if __name__=='__main__':unittest.main()
