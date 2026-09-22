"""Source-aware solver feedback, without changing expressions or solver results."""
import backend_imports
from copy import deepcopy
import unittest

from ltl_diagnostics import solver_source_notes
from sequence_experiment import goal_shortfall_feedback


def error(label='target', code='jg_goal_infeasible'):
    return dict(file='model.ltl', goal=label, code=code, status='infeasible')


class SourceNotesTest(unittest.TestCase):
    def test_actual_join_structure_with_scoped_source_location(self):
        source = 'val done = a & b\nGen(start ### done ### (done.##(1, Some(20))(end)), "target")'
        errors = [error()]
        before = deepcopy(errors)
        result = solver_source_notes(errors, source)[0]
        self.assertEqual((result['line'], result['column']), (2, 1))
        self.assertEqual(len(result['source_facts']), 1)
        note = result['source_facts'][0]
        self.assertEqual(note['predicate'], 'done')
        self.assertEqual(note['line'], 2)
        self.assertIn('If it is a one-cycle', note['message'])
        self.assertIn('not a type diagnosis', note['message'])
        self.assertEqual(errors, before)

    def test_no_global_or_successful_goal_note(self):
        source = 'val other = x ### (x.##(1)(y))\nGen(other, "success")\nGen(a ### b, "target")'
        result = solver_source_notes([error()], source)[0]
        self.assertEqual(result['line'], 3)
        self.assertNotIn('source_facts', result)

    def test_comments_strings_and_unrelated_labels_are_not_code(self):
        source = '// Gen(a ### (a.##(1)(b)), "target")\nGen(a ### b, "target")'
        result = solver_source_notes([error()], source)[0]
        self.assertEqual(result['line'], 2)
        self.assertNotIn('source_facts', result)
        self.assertEqual(solver_source_notes([error('missing')], source), [error('missing')])

    def test_compound_and_negated_operands_are_not_false_repeats(self):
        for expr in ('!p ### (p.##(1)(q))', 'a & p ### (p.##(1)(q))',
                     '(!p) ### (p.##(1)(q))', '(a & p) ### (p.##(1)(q))',
                     'io.p ### (p.##(1)(q))', 'p ### (other.##(1)(q))',
                     '(a ### p).##(1)(q)', 'p ### ((q ### r).##(1)(s))'):
            with self.subTest(expr=expr):
                self.assertNotIn('source_facts', solver_source_notes([error()], f'Gen({expr}, "target")')[0])

    def test_parenthesized_single_predicate_is_still_a_named_endpoint(self):
        for expr in ('(p) ### (p.##(1)(q))', 'a ### ((p)) ### (p.##(1)(q))'):
            with self.subTest(expr=expr):
                facts = solver_source_notes([error()], f'Gen({expr}, "target")')[0]['source_facts']
                self.assertEqual(len(facts), 1)

    def test_no_type_inference_for_a_sequence_valued_name(self):
        source = 'val run = a ### b\nGen(run ### (run.##(1)(c)), "target")'
        facts = solver_source_notes([error()], source)[0]['source_facts']
        self.assertIn('If it is a one-cycle predicate', facts[0]['message'])

    def test_unknown_has_location_but_no_unsat_note(self):
        result = solver_source_notes([error(code='jg_goal_unknown')],
                                     'Gen(p ### (p.##(1)(q)), "target")')[0]
        self.assertEqual(result['line'], 1)
        self.assertNotIn('source_facts', result)

    def test_zero_provider_calls_and_original_solver_classification_kept(self):
        report = dict(phase='solve', ok=True, result=dict(status='partial', goals=[
            dict(label='target', status='infeasible'), dict(label='ok', status='generated')]))
        source = 'Gen(p ### (p.##(1)(q)), "target")\nGen(p, "ok")'
        result = goal_shortfall_feedback(report, ['target', 'ok'], source)
        self.assertEqual(result['result'], report['result'])
        self.assertEqual(result['errors'][0]['code'], 'jg_goal_infeasible')
        self.assertEqual(result['errors'][0]['source_facts'][0]['predicate'], 'p')
        self.assertNotIn('source_facts', goal_shortfall_feedback(report, ['target', 'ok'])['errors'][0])



if __name__ == '__main__':
    unittest.main()
