import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import patch
from sweep_rq1_stimuli import (extra_time, instrument_encoded_tcl, measured_encoding,
                               recover_properties)
from run_records import save


class RQ1SweepTests(unittest.TestCase):
    def test_instrumentation_preserves_commands_and_limits(self):
        source = ('set_prove_time_limit 120s\nprove -property ModelUT.encoded_goal\n'
                  'set replot_status [visualize -replot -force -silent -proof_time 120s]\nexit\n')
        timed = instrument_encoded_tcl(source)
        for line in source.splitlines():
            self.assertIn(line, timed.splitlines())
        self.assertEqual(timed.count('puts "RQP_SOLVE_US'), 2)
        self.assertNotIn('clock milliseconds', timed)

    def test_refuse_unknown_tcl_structure(self):
        with self.assertRaises(ValueError):
            instrument_encoded_tcl('puts "covered"\n')

    def test_historical_cost_not_fabricated_as_new_work(self):
        prop = {'historical_count': 4, 'accepted': [{}]*4, 'new_solver_seconds': 9.0}
        self.assertEqual(extra_time(prop, 4), 0.0)
        self.assertEqual(extra_time(prop, 5), 9.0)

    def test_short_history_does_not_get_filled(self):
        self.assertEqual(extra_time({'historical_count': 1}, 8), 0.0)

    def test_prefix_and_missing_time(self):
        prop = {'historical_count': 4, 'accepted': [{}]*4+[{'new_solver_prefix_seconds': 2.0}],
                'new_solver_seconds': None}
        self.assertEqual(extra_time(prop, 5), 2.0)
        self.assertIsNone(extra_time(prop, 6))

    def test_recovery_does_not_retry_solver_or_native_failures(self):
        prop = dict(round=1,label='g',ltl_sha256='a',historical_count=0,extension_route='encoded',
                    source_summary='s',source_solve='d',accepted=[])
        prior = dict(properties=[dict(prop,status='extension-shortfall',stop_reason='native-replay-rejected')])
        recover_properties([(prop,[],None,None)],prior,None,None)
        self.assertEqual(prop['status'],'extension-shortfall')

    def test_recovery_only_retries_explicit_infrastructure_failure(self):
        prop = dict(round=1,label='g',ltl_sha256='a',historical_count=0,extension_route='soft',
                    source_summary='s',source_solve='d',accepted=[])
        prior = dict(properties=[dict(prop,status='extension-failed',error='VCS SFCOR',traceback='trace')])
        recover_properties([(prop,[],None,None)],prior,None,None)
        self.assertEqual(prop['status'],'pending')
        self.assertEqual(prop['infrastructure_attempt']['error'],'VCS SFCOR')

    def test_recovery_refuses_changed_property(self):
        prop = dict(round=1,label='g',ltl_sha256='a',historical_count=0,extension_route='soft',
                    source_summary='s',source_solve='d',accepted=[])
        prior = dict(properties=[dict(prop,ltl_sha256='changed')])
        with self.assertRaisesRegex(ValueError,'provenance changed'):
            recover_properties([(prop,[],None,None)],prior,None,None)

    def test_completed_encoding_is_reused_without_solving_and_patch_restored(self):
        from rvprobe.backend import candidates, encoding
        original_run = encoding.run
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            old = root/'old/candidate-0'
            old.mkdir(parents=True)
            (root/'new').mkdir()
            job,goal = dict(fingerprint='source'),dict(label='g')
            opts = SimpleNamespace(out=root/'new/candidate-0',trace_cycles=None,trace_preference='soft',
                trace_seed=None,engine_mode='auto',jg_time_limit='120s',noncontending_tristates=True)
            save(old/'identity.json',dict(source_job='source',original_goal=goal,trace_cycles=None,
                trace_preference='soft',trace_seed=None,engine_mode='auto',solve_time_limit='120s',
                noncontending_tristates=True,implementation_sha256={}))
            save(old/'summary.json',dict(source_job='source',label='g',status='covered'))
            (old/'witness.vcd').write_text('frozen witness')
            with patch.object(candidates,'solve') as solver:
                with measured_encoding(root/'old'):
                    result = candidates.solve(None,None,job,goal,[],opts)
                    self.assertEqual(result['status'],'covered')
                self.assertIs(candidates.solve,solver)
                solver.assert_not_called()
            self.assertIs(encoding.run,original_run)
            self.assertEqual(opts.out.resolve(),old.resolve())

    def test_incomplete_encoding_is_not_claimed_as_cached_success(self):
        from rvprobe.backend import candidates
        with TemporaryDirectory() as tmp:
            with patch.object(candidates,'solve',return_value={'status':'covered'}) as solver:
                with measured_encoding(Path(tmp)):
                    result = candidates.solve(None,None,{},[],[],SimpleNamespace(out=Path(tmp)/'candidate-2'))
                solver.assert_called_once()
                self.assertEqual(result['status'],'covered')


if __name__ == '__main__':
    unittest.main()
