import unittest
from encoded_past import lower_past


class PastLoweringTest(unittest.TestCase):
    def test_explicit_clock_and_unknown_history(self):
        code, info = lower_past('module top(input clock, input [7:0] a); '
            'wire [7:0] b = $past(a, 3, , @(posedge clock)); endmodule', {'clock'})
        self.assertNotIn('$past', code)
        self.assertEqual(info[0]['ticks'], 3)
        self.assertEqual(code.count('always @(posedge clock)'), 3)
        self.assertIn('reg [$bits(a)-1:0]', code)
        self.assertNotIn('initial ', code)
        self.assertNotIn('reset', code)

    def test_nested_and_comma_in_concatenation(self):
        code, info = lower_past('module top; wire b = '
            '$past({a[3:0], $past(c, 2)}, 1, , @(posedge clock)); endmodule', {'clock'})
        self.assertEqual([p['ticks'] for p in info], [2, 1])
        self.assertIn('rvp_past_history_0_1', info[1]['expression'])
        self.assertNotIn('$past', code)

    def test_unsupported_forms_fail_closed(self):
        for expr in ('$past(a, n)', '$past(a, 2, enable, @(posedge clock))',
                     '$past(a, 1, , @(negedge clock))', '$past(a, 1, , @(posedge other))',
                     '$past(a, 4097)', '$past(a, 1'):
            with self.subTest(expr=expr), self.assertRaises(ValueError):
                lower_past('module top; wire b = '+expr+'; endmodule', {'clock'})

    def test_zero_depth_is_current_value(self):
        code, info = lower_past('module top; wire b = $past(a, 0); endmodule', {'clock'})
        self.assertEqual(info[0]['registers'], [])
        self.assertNotIn('always', code)
        self.assertIn('wire b = (a)', code)

    def test_history_width_is_after_internal_wire_before_consumer(self):
        code, _ = lower_past('module top(input clock); wire [7:0] dut_data; '
            'wire [7:0] h = $past(dut_data, 2, , @(posedge clock)); '
            'wire [7:0] h2 = $past(h, 1, , @(posedge clock)); endmodule', {'clock'})
        self.assertLess(code.index('wire [7:0] dut_data'), code.index('$bits(dut_data)'))
        self.assertLess(code.index('$bits(dut_data)'), code.index('wire [7:0] h ='))
        self.assertLess(code.index('wire [7:0] h ='), code.index('$bits(h)'))


if __name__ == '__main__':
    unittest.main()
