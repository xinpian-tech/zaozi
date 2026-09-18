"""Capability feedback must not convert infrastructure errors into paid repairs."""
import copy
import unittest

from repair_policy import model_repair_allowed
from ut_harness import solver_diagnostic


class SolverDiagnosticTests(unittest.TestCase):
    def setUp(self):
        self.goal = dict(label='transfer', status='error',
                         failureKind='unsupported_liveness_cover', detail='EOBS012: Liveness cover')

    def test_only_known_model_goals_are_repairable(self):
        report = solver_diagnostic({'goals': [self.goal]}, ['transfer'])
        self.assertTrue(model_repair_allowed(report))
        self.assertEqual(report['errors'][0]['backend_detail'], self.goal['detail'])
        self.assertFalse(model_repair_allowed({**report, 'model_repair_allowed': False}))
        for field, value in [('file', 'dut.sv'), ('code', 'unknown'), ('goal', '')]:
            changed = copy.deepcopy(report)
            changed['errors'][0][field] = value
            self.assertFalse(model_repair_allowed(changed))
        self.assertFalse(model_repair_allowed(solver_diagnostic({'goals': [self.goal]}, ['other'])))

    def test_goal_local_compile_timeout_is_an_unresolved_shortfall(self):
        timeout = {**self.goal, 'failureKind': 'property_compile_timeout'}
        self.assertIsNone(solver_diagnostic({'goals': [timeout]}, ['transfer']))
        mixed = solver_diagnostic({'goals': [self.goal, timeout]}, ['transfer'])
        self.assertTrue(model_repair_allowed(mixed))
        self.assertEqual([error['goal'] for error in mixed['errors']], ['transfer'])

    def test_other_execution_errors_fail_closed(self):
        for kind in ('jg_execution_failure', 'unknown'):
            other = {**self.goal, 'label': 'other', 'failureKind': kind}
            for goals in ([other], [self.goal, other]):
                report = solver_diagnostic({'goals': goals}, ['transfer', 'other'])
                self.assertFalse(model_repair_allowed(report))
        self.assertIsNone(solver_diagnostic({'goals': []}, []))
        self.assertIsNone(solver_diagnostic({'goals': [{**self.goal, 'status': 'unknown'}]}, ['transfer']))


if __name__ == '__main__': unittest.main()
