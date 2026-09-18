import backend_imports
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from rvprobe.backend.selection import select_witnesses, exhaustion_details, pool_target
from rvprobe.backend.failures import ReplayInfrastructureFailure


class CoverMiss(ValueError):
    diagnostics = {'kind':'formal_replay_semantics_mismatch', 'log':'retained.log',
                   'replay_checks':'replay-checks.json',
                   'model_repair_allowed':False}


class SelectionTests(unittest.TestCase):
    def test_exhaustion_exposes_auxiliary_termination_and_budget(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'encoded').mkdir()
            (root/'encoded/search.json').write_text(json.dumps(dict(status='stopped',
                stop_reason='undetermined',solve_time_limit='120s',attempts=[{'status':'undetermined'}])))
            details=exhaustion_details(0,1,1,root)['auxiliary_search']
            self.assertEqual(details['stop_reason'],'undetermined')
            self.assertEqual(details['solve_time_limit'],'120s')
            self.assertEqual(details['attempts'],1)

    def test_opt_in_auxiliary_still_requires_original_native_cover(self):
        def evaluate(row,index):
            if row['inputFingerprint'] != '4': raise CoverMiss('original cover missed')
            return row
        with tempfile.TemporaryDirectory() as directory:
            selected, report = select_witnesses([self.row(0)],1,evaluate,
                lambda budget:[self.row(i) for i in range(budget)],Path(directory),
                auxiliary=lambda budget:[self.row(4)])
        self.assertEqual(selected,[self.row(4)])
        self.assertEqual(report['budget'],8)
        self.assertEqual(report['auxiliary_candidate_policy'],'encoded-native-checked-v1')
        self.assertEqual([x['status'] for x in report['attempts']],['rejected']*4+['passed'])

    def test_observed_unknowns_escalate_without_guessing_more_initial_values(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            checks = root/'checks.json'
            checks.write_text(json.dumps({'waveform_differences':[
                {'kind':'output','mask':255,'known':15}]}))
            error = CoverMiss('miss')
            error.diagnostics = {**CoverMiss.diagnostics, 'replay_checks':str(checks)}
            extra = Mock(side_effect=AssertionError('must not resample arbitrary initial values'))
            def evaluate(row, index):
                if row['inputFingerprint']=='0': raise error
                return row
            selected, report = select_witnesses([self.row(0),self.row(1)],1,evaluate,extra,root,
                                                auxiliary=lambda budget:[self.row(2)])
            self.assertEqual(selected,[self.row(1)])
            self.assertEqual(report['attempts'][0]['unknown_output_bits'],4)
            self.assertTrue(report['known_state_escalation']['skipped_two_state_replenishment'])
            extra.assert_not_called()

    def test_auxiliary_is_lazy_and_never_handles_transport_defects(self):
        extra=Mock(side_effect=AssertionError('unexpected auxiliary solver'))
        with tempfile.TemporaryDirectory() as directory:
            select_witnesses([self.row(0)],1,lambda row,index:row,
                lambda budget:[],Path(directory),auxiliary=extra)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ReplayInfrastructureFailure):
                select_witnesses([self.row(0)],1,Mock(side_effect=ReplayInfrastructureFailure('bad pins')),
                    lambda budget:[],Path(directory),auxiliary=extra)
        extra.assert_not_called()

    def test_short_sampling_pool_is_not_an_impossible_exact_count_requirement(self):
        extra = Mock(side_effect=AssertionError('do not pad or resample a fully valid offered pool'))
        with tempfile.TemporaryDirectory() as directory:
            selected, report = select_witnesses([self.row(0),self.row(1)],pool_target(4,2),
                lambda row,index: index,extra,Path(directory))
        self.assertEqual(selected,[0,1])
        self.assertEqual(report['target'],2)
        self.assertEqual(report['status'],'passed')
        extra.assert_not_called()

    def test_short_pool_never_emits_a_rejected_candidate(self):
        def evaluate(row,index):
            if index: raise CoverMiss('original cover missed')
            return row
        with tempfile.TemporaryDirectory() as directory:
            selected,report=select_witnesses([self.row(0),self.row(1)],pool_target(4,2),evaluate,
                lambda budget: [],Path(directory))
        self.assertEqual(selected,[self.row(0)])
        self.assertEqual(report['status'],'exhausted')
        self.assertEqual(report['accepted'],1)

    def row(self, index):
        return dict(inputFingerprint=str(index), witnessFile=f'witness-{index}.vcd')

    def test_passed_initial_pool_needs_no_more_solver_calls(self):
        extra = Mock(side_effect=AssertionError('unexpected solver work'))
        with tempfile.TemporaryDirectory() as directory:
            selected, report = select_witnesses([self.row(0),self.row(1)], 2,
                lambda row,index: index, extra, Path(directory))
        self.assertEqual(selected,[0,1])
        self.assertEqual(report['status'],'passed')
        extra.assert_not_called()

    def test_retains_misses_deduplicates_and_returns_full_target(self):
        def evaluate(row,index):
            if row['inputFingerprint']=='0':raise CoverMiss('original cover did not hold')
            return row['inputFingerprint']
        with tempfile.TemporaryDirectory() as directory:
            selected, report = select_witnesses([self.row(0),self.row(1)], 2,
                evaluate, lambda budget:[self.row(0),self.row(1),self.row(2)], Path(directory))
            saved = json.loads((Path(directory)/'selection.json').read_text())
        self.assertEqual(selected,['1','2'])
        self.assertEqual(len(report['attempts']),3)
        self.assertEqual(saved['attempts'][0]['status'],'rejected')
        self.assertEqual(saved['attempts'][0]['log'],'retained.log')
        self.assertEqual(saved['attempts'][0]['replay_checks'],'replay-checks.json')

    def test_preserves_validated_subset_without_claiming_full_target(self):
        with tempfile.TemporaryDirectory() as directory:
            selected,report=select_witnesses([self.row(0)],2,lambda row,index:row,
                                           lambda budget:[],Path(directory))
            report=json.loads((Path(directory)/'selection.json').read_text())
        self.assertEqual(selected,[self.row(0)])
        self.assertEqual(report['status'],'exhausted')
        self.assertEqual(report['accepted'],1)
        self.assertEqual(report['diagnostics']['kind'],'native_witness_search_exhausted')
        self.assertFalse(report['diagnostics']['model_repair_allowed'])
        self.assertEqual(report['diagnostics']['attempted'],1)

    def test_transport_and_unknown_errors_are_not_resampled(self):
        for error in (ReplayInfrastructureFailure('bad pins'),ValueError('unknown defect')):
            extra=Mock()
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(type(error)):
                    select_witnesses([self.row(0)],1,Mock(side_effect=error),extra,Path(directory))
            extra.assert_not_called()

    def test_rejections_obey_fixed_attempt_budget(self):
        calls=[]
        def evaluate(row,index):
            calls.append(index)
            raise CoverMiss('miss')
        with tempfile.TemporaryDirectory() as directory:
            selected,report=select_witnesses([self.row(0)],1,evaluate,
                lambda budget:[self.row(i) for i in range(100)],Path(directory))
        self.assertEqual(selected,[])
        self.assertEqual(report['status'],'exhausted')
        self.assertEqual(calls,list(range(4)))
