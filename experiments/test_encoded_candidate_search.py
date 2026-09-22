import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import backend_imports
import rvprobe.backend.candidates as search


class CandidateSearchTests(unittest.TestCase):
    def test_auxiliary_capability_shortfall_does_not_discard_native_valid_subset(self):
        from rvprobe.backend.selection import select_witnesses
        replies=[{'status':'covered'},search.UnsupportedTemporalForm('auxiliary limit')]
        with tempfile.TemporaryDirectory() as temp, patch.object(search,'solve',side_effect=replies) as solver:
            root=Path(temp);design,config,job,goals=self.frozen()
            importer=Mock(return_value={'inputFingerprint':'valid','cycles':12})
            auxiliary=lambda count: search.candidates(design,config,job,goals[0],[],root/'encoded',
                Path('yosys'),Path('eda'),count,23,importer)
            selected,report=select_witnesses([],2,lambda row,index:row,
                lambda count:[],root,auxiliary=auxiliary)
            self.assertEqual(len(selected),1)
            self.assertEqual(report['status'],'exhausted')
            self.assertEqual(report['diagnostics']['auxiliary_search']['termination_reason'],
                             'unsupported_temporal_form')
            self.assertEqual(solver.call_count,2)
            importer.assert_called_once()
            audit=json.loads((root/'encoded/search.json').read_text())
            self.assertFalse(audit['unreachability_proven'])
            self.assertEqual(audit['attempts'][-1]['kind'],'auxiliary_temporal_unsupported')

    def test_unclassified_value_error_is_not_a_capability_shortfall(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(search,'solve',
                side_effect=ValueError('bad trace width')):
            with self.assertRaisesRegex(ValueError,'bad trace width'):
                self.generate(Path(temp)/'search',4,Mock())

    def test_no_free_input_shortfall_keeps_valid_subset_and_does_not_retry(self):
        from rvprobe.backend.selection import select_witnesses
        replies=[{'status':'covered'},{'status':'resampling_exhausted',
                  'termination_reason':'no_single_cell_candidate','solver_invoked':False}]
        with tempfile.TemporaryDirectory() as temp, patch.object(search,'solve',side_effect=replies) as solver:
            root=Path(temp);design,config,job,goals=self.frozen()
            importer=Mock(return_value={'inputFingerprint':'valid','cycles':12})
            auxiliary=lambda count: search.candidates(design,config,job,goals[0],[],root/'encoded',
                Path('yosys'),Path('eda'),count,23,importer)
            selected,report=select_witnesses([],2,lambda row,index:row,
                lambda count:[],root,auxiliary=auxiliary)
            self.assertEqual(len(selected),1)
            self.assertEqual(report['status'],'exhausted')
            self.assertEqual(report['diagnostics']['auxiliary_search']['termination_reason'],
                             'no_single_cell_candidate')
            self.assertEqual(solver.call_count,2)
            importer.assert_called_once()
            self.assertFalse(selected[0]['original_goal_proven'])

    def test_resampling_exhaustion_preserves_native_validated_subset(self):
        from rvprobe.backend.selection import select_witnesses
        replies = [{'status':'covered'}, {'status':'resampling_exhausted',
            'solver_status':'covered','termination_reason':'trace_resampling_undetermined'}]
        with tempfile.TemporaryDirectory() as temp, patch.object(search,'solve',side_effect=replies):
            root=Path(temp);design,config,job,goals=self.frozen()
            importer=Mock(return_value={'inputFingerprint':'valid','cycles':12})
            auxiliary=lambda count: search.candidates(design,config,job,goals[0],[],root/'encoded',
                Path('yosys'),Path('eda'),count,23,importer)
            selected,report=select_witnesses([],2,lambda row,index:row,
                lambda count:[],root,auxiliary=auxiliary)
            self.assertEqual(len(selected),1)
            self.assertEqual(report['status'],'exhausted')
            self.assertEqual(report['diagnostics']['auxiliary_search']['termination_reason'],
                             'trace_resampling_undetermined')
            self.assertFalse(selected[0]['original_goal_proven'])
            importer.assert_called_once()

    def test_real_process_failure_remains_fatal(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(search,'solve',
                side_effect=subprocess.CalledProcessError(1,['jg'])):
            importer=Mock()
            with self.assertRaises(subprocess.CalledProcessError):
                self.generate(Path(temp)/'search',4,importer)
            importer.assert_not_called()

    def frozen(self):
        return object(), {'environment': {'boundary': 'independent-dut-v1'}}, {'fingerprint': 'job'}, [{
            'label': 'intent', 'cycles': 9, 'generationLabel': 'intent', 'utModule': 'OriginalUT',
            'utSourceSha256': 'frozen-sha', 'fingerprint': 'job', 'engine': 'jaspergold'}]

    def generate(self, directory, count, importer):
        design, config, job, goals = self.frozen()
        return list(search.candidates(design, config, job, goals[0], [], directory,
                                      Path('yosys'), Path('eda'), count, 23, importer))

    def test_candidates_keep_original_identity_and_are_not_proofs(self):
        commands=[]
        def solve(design, config, job, goal, environment, options):
            commands.append(options)
            out=options.out;out.mkdir()
            return {'status':'covered'}
        with tempfile.TemporaryDirectory() as temp, patch.object(search,'solve',side_effect=solve):
            importer=lambda *args: {'inputFingerprint':'candidate', 'witnessFile':'witness', 'origin':'sampling', 'cycles':12}
            rows=self.generate(Path(temp)/'search',2,importer)
        self.assertEqual(len(rows),2)
        self.assertEqual(rows[0]['utSourceSha256'],'frozen-sha')
        self.assertFalse(rows[0]['original_goal_proven'])
        self.assertEqual(rows[0]['origin'],'encoded-native-candidate-v1')
        self.assertIsNone(commands[0].trace_seed)
        self.assertIsNone(commands[0].trace_cycles)
        self.assertEqual(commands[1].trace_seed,24)
        self.assertEqual(commands[1].trace_cycles,12)

    def test_inconsistent_model_is_not_imported_or_retried(self):
        def solve(design, config, job, goal, environment, options):
            out=options.out;out.mkdir()
            return {'status':'inconsistent_environment'}
        with tempfile.TemporaryDirectory() as temp, patch.object(search,'solve',side_effect=solve) as solver:
            importer = Mock()
            rows=self.generate(Path(temp)/'search',4,importer)
            self.assertEqual(rows,[])
            self.assertEqual(solver.call_count,1)
            importer.assert_not_called()

    def test_undetermined_retains_budget_and_is_not_unreachability(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(search,'solve',return_value={
                'status':'undetermined','solver_status':'undetermined','elapsed_seconds':120}) as solver:
            directory=Path(temp)/'search'
            importer=Mock()
            design, config, job, goals=self.frozen()
            rows=list(search.candidates(design,config,job,goals[0],[],directory,
                Path('yosys'),Path('eda'),4,23,importer,time_limit='600s'))
            record=json.loads((directory/'search.json').read_text())
            self.assertEqual(rows,[])
            self.assertEqual(record['stop_reason'],'undetermined')
            self.assertEqual(record['solve_time_limit'],'600s')
            self.assertFalse(record['unreachability_proven'])
            self.assertEqual(solver.call_args.args[-1].jg_time_limit,'600s')
            solver.assert_called_once()
            importer.assert_not_called()

    def test_closed_consumer_does_not_leave_a_running_search(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(search,'solve',return_value={'status':'covered'}):
            root=Path(temp)/'search'
            design,config,job,goals=self.frozen()
            stream=search.candidates(design,config,job,goals[0],[],root,Path('yosys'),Path('eda'),4,23,
                lambda *args: {'inputFingerprint':'a','cycles':12})
            next(stream)
            stream.close()
            record=json.loads((root/'search.json').read_text())
            self.assertEqual(record['status'],'stopped')
            self.assertEqual(record['stop_reason'],'consumer_closed')
            self.assertEqual(record['attempts'][0]['status'],'covered')

    def test_duplicate_enables_subset_and_failed_subset_consumes_same_budget(self):
        commands=[]
        def solve(design, config, job, goal, environment, options):
            commands.append(options)
            out=options.out;out.mkdir()
            status='no_distinct_candidate' if len(commands)==3 else 'covered'
            return {'status':status}
        samples=[{'inputFingerprint':fp,'cycles':12} for fp in ('a','a','b')]
        with tempfile.TemporaryDirectory() as temp, patch.object(search,'solve',side_effect=solve):
            importer = Mock(side_effect=samples)
            rows=self.generate(Path(temp)/'search',4,importer)
            self.assertEqual(len(commands),4)
            self.assertEqual(importer.call_count,3)
            self.assertEqual([r['inputFingerprint'] for r in rows],['a','a','b'])
            self.assertFalse(commands[1].avoid_stimulus)
            self.assertTrue(commands[2].avoid_stimulus)
            self.assertTrue(commands[3].avoid_stimulus)


if __name__=='__main__':unittest.main()
