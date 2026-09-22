import backend_imports
import unittest
from rvprobe.backend.portability import restore_throughout


class ThroughoutPortabilityTest(unittest.TestCase):
    def test_clocked_generated_form_preserves_both_clocks_and_delay(self):
        raw='(@(posedge clock) a[*]) intersect (@(posedge clock) b) ##0 (@(posedge clock) ##[1:2] c)'
        text,edits=restore_throughout(raw)
        self.assertEqual(len(edits),1)
        self.assertIn('(a) throughout',text)
        self.assertEqual(text.count('@(posedge clock)'),3)
        self.assertIn('##[1:2]',text)
        self.assertNotIn('[*1',text)

    def test_rewrite_inside_concat_retains_nullable_right_operand(self):
        raw='((a[*0:$]) intersect (b[*0])) ##1 c'
        text,edits=restore_throughout(raw)
        self.assertEqual(len(edits),1)
        self.assertIn('(b[*0])',text)
        self.assertTrue(text.endswith('##1 c'))
        self.assertNotIn('[*1',text)

    def test_do_not_capture_low_precedence_sequence_and_or(self):
        raw='(a[*]) intersect (b ##1 c) and d or e'
        text,edits=restore_throughout(raw)
        self.assertEqual(len(edits),1)
        self.assertTrue(text.endswith(' and d or e'))
        self.assertNotIn(' and d',edits[0]['after'])

    def test_multiple_intersections_keep_their_operands(self):
        raw='(a[*]) intersect (b ##1 c) intersect d'
        text,edits=restore_throughout(raw)
        self.assertEqual(len(edits),1)
        self.assertTrue(text.endswith(' intersect d'))

    def test_common_root_clock_and_complex_boolean_are_supported(self):
        raw='@(posedge clock) (((x & y) | (~x & ~y))[*0:$]) intersect (b ##1 c)'
        text,edits=restore_throughout(raw)
        self.assertEqual(len(edits),1)
        self.assertTrue(text.startswith('@(posedge clock)'))
        self.assertIn('(x & y) | (~x & ~y)',text)

    def test_unsupported_and_already_portable_forms_remain_byte_identical(self):
        for raw in ('a throughout (b ##1 c)', '(a[*1:$]) intersect b',
                    '((a ##1 b)[*]) intersect c', '(a[*0:4]) intersect b',
                    '(@(posedge c1) a[*]) intersect (@(posedge c2) b)',
                    '(@(posedge clock) a[*]) intersect (@(negedge clock) b)',
                    '(a[*]) intersect b /* comment */', '(a[*]) intersect "and"',
                    '(a[*]) intersect @(a or b) c', '(a[*]) intersect b |-> c'):
            with self.subTest(raw=raw):
                self.assertEqual(restore_throughout(raw),(raw,[]))

    def test_disable_gate_is_unchanged(self):
        raw='disable iff (reset || !active) (a[*]) intersect (@(posedge clock) b ##1 c)'
        text,edits=restore_throughout(raw)
        self.assertEqual(len(edits),1)
        self.assertTrue(text.startswith('disable iff (reset || !active) '))

    def test_balanced_boundary(self):
        with self.assertRaisesRegex(ValueError,'unbalanced'):
            restore_throughout('(a[*]) intersect (b')


if __name__=='__main__':unittest.main()
