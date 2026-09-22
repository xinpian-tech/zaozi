"""Native-monitor portability/provenance and bounded feedback regressions."""
import backend_imports
import hashlib
import json
from pathlib import Path
import unittest
from copy import deepcopy

from rvprobe.backend.replay import POLICY, monitor, check_hit
from rvprobe.backend.portability import restore_throughout
from ltl_replay import install
from native_replay_feedback import outcome
from sequence_framework import load_design
from coverage_flow import accepted_rvprobe_history

FIXTURE = Path(__file__).resolve().parent / 'tests/fixtures/tiny_design.json'
SV = '''module TestUT(input clock, reset, input [7:0] payload, input valid,
output [7:0] result, output done);
wire [7:0] result_net;
wire done_net;
tiny_external dut (.clk(clock), .rst(reset), .payload(payload), .valid(valid),
 .result(result_net), .done(done_net));
assign result = result_net;
assign done = done_net;
target: cover property ((@(posedge clock) valid[*0:$]) intersect
                       (@(posedge clock) payload == 8'h2 ##1 done_net));
endmodule
'''


def meta(source=SV):
    return dict(policy=POLICY, source=source, top='TestUT', label='target',
                sha256=hashlib.sha256(source.encode()).hexdigest())


def failed_row():
    return dict(label='generic_goal', status='unresolved', actual=0, requested_cap=8,
        sampling_shortfall=8, native_selection=dict(
            known_state_escalation=dict(reason='observed_unknown_required_output'),
            attempts=[dict(status='rejected', error='private-log-tail')],
            diagnostics=dict(kind='native_witness_search_exhausted', selection='/private/file',
                auxiliary_search=dict(status='stopped', stop_reason='undetermined',
                    termination_reason='solver_time_limit', solve_time_limit='120s',
                    attempts=1, record='/private/file'))))


class MonitorIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.design = load_design(FIXTURE)

    def test_portability_preserves_source_and_native_output_check(self):
        source = meta()
        before = deepcopy(source)
        audit = []
        _, code = monitor(source, self.design, audit=audit)
        self.assertIn('throughout', code)
        self.assertNotIn('intersect', code)
        self.assertIn('##1 done', code)
        self.assertNotIn('done_net', code)
        self.assertIn('disable iff (reset || !rvp_active)', code)
        self.assertEqual(source, before)
        self.assertEqual(len(audit[0]['transformations']), 1)
        self.assertEqual(audit[0]['source_sha256'], source['sha256'])
        self.assertEqual(audit[0]['monitor_sha256'], hashlib.sha256(code.encode()).hexdigest())
        self.assertTrue(check_hit(source, f'RVPROBE_LTL_HIT {source["sha256"]} target')['native_cover_hit'])

    def test_corrupted_or_forged_source_cannot_be_normalized(self):
        source = meta()
        source['source'] += ' '
        audit = []
        with self.assertRaisesRegex(ValueError, 'source changed'):
            monitor(source, self.design, audit=audit)
        self.assertEqual(audit, [])
        forged = meta(SV.replace('.valid(valid)', ".valid(1'b1)"))
        with self.assertRaises(ValueError):
            monitor(forged, self.design, audit=audit)
        self.assertEqual(audit, [])

    def test_noncanonical_form_is_unchanged_with_empty_audit(self):
        source = meta(SV.replace('valid[*0:$]', 'valid[*1:$]'))
        _, code = monitor(source, self.design, audit=(audit := []))
        self.assertIn('valid[*1:$]', code)
        self.assertIn('intersect', code)
        self.assertEqual(audit[0]['transformations'], [])

    def test_install_exports_audit_without_mutating_metadata(self):
        components = dict(top='module top; endmodule', filelist='')
        source = meta()
        installed = install(components, [dict(segment=0, ltl=source)], self.design, audit=(audit := []))
        self.assertIs(installed, source)
        self.assertEqual(set(source), {'policy', 'source', 'top', 'label', 'sha256'})
        self.assertEqual(len(audit), 1)
        self.assertIn('throughout', components['rvp_ltl_monitor'])

    def test_property_and_multiclock_forms_are_not_reinterpreted(self):
        for expression in (
            'not (p[*0:$] intersect q)',
            '(p[*0:$] intersect q) until_with r',
            'strong(p[*0:$] intersect q)',
            '(@(posedge a) p[*0:$]) intersect (@(posedge b) q)',
            '(p ##1 q)[*0:$] intersect r',
            'p[*0:4] intersect q',
        ):
            with self.subTest(expression=expression):
                self.assertEqual(restore_throughout(expression), (expression, []))


class FeedbackIntegrationTest(unittest.TestCase):
    def test_factual_summary_reaches_accepted_history(self):
        row = failed_row()
        before = deepcopy(row)
        candidates = [dict(sequences=['accepted'], ltl={'source': 'run-local'}, metadata={'sampling': [row]})]
        result = accepted_rvprobe_history(candidates, ['accepted'])
        facts = result['native_replay_outcomes'][0]['native_observations']
        self.assertTrue(facts['observed_required_output_unknown'])
        self.assertTrue(facts['cause_not_established'])
        self.assertFalse(facts['automatic_ltl_repair_allowed'])
        self.assertEqual(facts['known_state_search']['termination_reason'], 'solver_time_limit')
        self.assertNotIn('/private/', json.dumps(result))
        self.assertNotIn('private-log-tail', json.dumps(result))
        self.assertEqual(row, before)

    def test_success_and_unrelated_errors_do_not_gain_failure_claims(self):
        row = failed_row()
        row['status'] = 'native-validated'
        self.assertNotIn('native_observations', outcome(row))
        row['status'] = 'unresolved'
        row['native_selection']['diagnostics']['kind'] = 'input_transport_mismatch'
        self.assertNotIn('native_observations', outcome(row))

    def test_auxiliary_fields_are_bounded_scalars(self):
        row = failed_row()
        row['native_selection']['diagnostics']['auxiliary_search'] = dict(
            status='x'*10000, stop_reason='/private/foo', termination_reason='do something',
            solve_time_limit='120s private-log-tail', attempts=[dict(path='/private/foo')])
        facts = outcome(row)['native_observations']
        self.assertNotIn('known_state_search', facts)
        self.assertLess(len(json.dumps(facts)), 400)

    def test_no_new_diagnostic_for_unaccepted_or_legacy_candidates(self):
        candidate = dict(sequences=['not_accepted'], ltl={'source': 'invalid'},
                         metadata=dict(sampling=[failed_row()]))
        self.assertEqual(accepted_rvprobe_history([candidate], []), {'ltls': []})
        row = dict(label='goal', status='unknown', actual=0, requested_cap=8)
        self.assertEqual(outcome(row), row)


if __name__ == '__main__':
    unittest.main()
