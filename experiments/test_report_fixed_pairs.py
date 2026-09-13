import json
from pathlib import Path
import tempfile
import unittest
import csv
from unittest.mock import patch

from report_fixed_pairs import inspect
from report_fixed_pairs import main


class ReportCostsTest(unittest.TestCase):
    def test_terminal_results_keep_failures_distinct_from_success_and_running(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'summary.json'
            for status,diagnostic,terminal in [('failed',False,True),('running',False,False),
                                                ('failed',True,False)]:
                path.write_text(json.dumps({'status':'failed','diagnostic_only':diagnostic,
                    'arms':{'haven':{'status':status},'rvprobe':{'status':'completed'}}}))
                result=inspect(path)
                self.assertEqual(result['terminal'],terminal)
                self.assertFalse(result['complete'])

    def test_saved_generation_reuse_does_not_look_like_free_full_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'summary.json'
            path.write_text(json.dumps({'arms':{'rvprobe':{'costs':{
                'usage_reported':{'total_tokens':12}, 'prior_generation_usage':{'total_tokens':23},
                'cumulative_usage_reported':{'total_tokens':35}}}}}))
            result=inspect(path)['arms']['rvprobe']
            self.assertEqual(result['tokens'],12)
            self.assertEqual(result['token_scope'],'continuation_only')

    def test_missing_volatile_run_uses_durable_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            archive=Path(directory)/'archive/summary.json';archive.parent.mkdir()
            archive.write_text(json.dumps({'status':'completed','arms':{
                name:{'status':'completed','costs':{'usage_reported':{'total_tokens':7}}}
                for name in ('haven','rvprobe')}}))
            result=inspect(Path(directory)/'gone/paired/summary.json',archive)
            self.assertTrue(result['complete'])
            self.assertEqual(result['source'],str(archive))
            self.assertEqual(result['archive_source'],str(archive))

    def test_recovered_costs_are_explicitly_incremental(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'summary.json'
            path.write_text(json.dumps({'status':'completed','arms':{
                'haven':{'status':'completed','new_costs':{'usage_reported':{'total_tokens':0}}},
                'rvprobe':{'status':'completed','new_costs':{'usage_reported':{'total_tokens':12}}}}}))
            result=inspect(path)
            self.assertTrue(result['complete'])
            self.assertEqual(result['arms']['haven']['token_scope'],'continuation_only')
            self.assertEqual(result['arms']['rvprobe']['tokens'],12)

    def test_standard_costs_remain_full_attempt(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'summary.json'
            path.write_text(json.dumps({'arms':{'haven':{'costs':{'usage_reported':{'total_tokens':23}}}}}))
            result=inspect(path)
            self.assertFalse(result['complete'])
            self.assertEqual(result['arms']['haven']['token_scope'],'listed_arm_attempt')
            self.assertEqual(result['arms']['haven']['tokens'],23)


class ExportTests(unittest.TestCase):
    def test_exports_recorded_score_rounds_time_and_incremental_cost(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'summary.json'
            source.write_text(json.dumps({'status':'failed','arms':{
                'haven':{'status':'completed','rounds':[{'round':1}], 'elapsed_seconds':120,
                    'final':{'score':75,'percent':{'line':100,'toggle':50}},
                    'new_costs':{'usage_reported':{'total_tokens':0}}},
                'rvprobe':{'status':'failed','rounds':[],'failed_round':1,'elapsed_seconds':60,
                    'stop_reason':'native_witness_search_exhausted','error':'3/4 within fixed budget',
                    'costs':{'usage_reported':{'total_tokens':12}}}}}))
            before=source.read_bytes();manifest=root/'tracking.json'
            manifest.write_text(json.dumps({'attempts':{'test':[str(source)]}}))
            with patch('sys.argv',['report','--manifest',str(manifest),'--out',str(root/'export')]):main()
            self.assertEqual(source.read_bytes(),before)
            exported=json.loads((root/'export.json').read_text())
            self.assertEqual(exported['terminal_pairs'],1)
            self.assertEqual(exported['complete_pairs'],0)
            with (root/'export.csv').open(encoding='utf-8-sig') as stream:rows=list(csv.DictReader(stream))
            self.assertEqual(rows[0]['coverage_score_percent'],'75')
            self.assertEqual(rows[0]['elapsed_seconds'],'120')
            self.assertEqual(rows[0]['token_scope'],'continuation_only')
            self.assertEqual(rows[1]['accepted_rounds'],'0')
            self.assertEqual(rows[1]['coverage_score_percent'],'')
            self.assertEqual(rows[1]['status'],'failed')
            self.assertEqual(rows[1]['stop_reason'],'native_witness_search_exhausted')
            self.assertEqual(rows[1]['error'],'3/4 within fixed budget')
            self.assertIn('75.00 / —',(root/'export.md').read_text())


if __name__=='__main__': unittest.main()
