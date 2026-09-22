import unittest
from pathlib import Path
from sweep_frozen_stimuli import timed_script, curve_time


class SweepTests(unittest.TestCase):
    def script(self, count):
        return timed_script({'rtl': ['/tmp/d.v'], 'includeDirs': [], 'top': 'm'},
            {'label': 'g', 'cycles': 3}, Path('/tmp/m.sv'), Path('/tmp/samples'),
            [{'name': 'a', 'width': 8}], count, 11, '2s')

    def test_measured_prefixes_and_fail_fast(self):
        text = self.script(8)
        self.assertEqual(text.count('puts "RVP_ACCEPT initial'), 1)
        self.assertEqual(text.count('puts "RVP_ATTEMPT'), 14)
        self.assertEqual(text.count('puts "RVP_ACCEPT '), 15)
        self.assertIn('if {$status != "covered"}', text)
        self.assertNotIn('assume', text)
        self.assertIn('visualize -max_length 3', text)
        self.assertNotIn('clock milliseconds', text)

    def test_preference_prefix_invariant(self):
        prefs = lambda n: [s for s in self.script(n).splitlines() if s.startswith('visualize -force -soft')]
        self.assertEqual(prefs(3), prefs(8)[:len(prefs(3))])

    def test_proof_budget_separate_from_resampling_budget(self):
        text = timed_script({'rtl': ['/tmp/d.v'], 'includeDirs': [], 'top': 'm'},
            {'label': 'g', 'cycles': 3}, Path('/tmp/m.sv'), Path('/tmp/samples'),
            [{'name': 'a', 'width': 8}], 8, 11, '30s', prove_limit='120s')
        self.assertIn('set_prove_time_limit 120s', text)
        self.assertNotIn('set_prove_time_limit 30s', text)
        self.assertIn('-proof_time 30s', text)

    def test_timing_shortfalls_and_skips(self):
        p = {'original_status': 'generated', 'accepted': [{'solver_prefix_seconds': 2.}],
             'sampling': {'solver_seconds': 5.}}
        self.assertEqual(curve_time(p, 1, 'solver_prefix_seconds'), 2.)
        self.assertEqual(curve_time(p, 2, 'solver_prefix_seconds'), 5.)
        self.assertEqual(curve_time({'original_status': 'infeasible'}, 1, 'solver_prefix_seconds'), 0.)


if __name__ == '__main__': unittest.main()
