import unittest
from rtl_environment import parse_roles


class RtlEnvironmentTests(unittest.TestCase):
    def parse(self, clocks='wb_clk', resets='~arst'):
        return parse_roles({'clocks_raw':clocks,'resets_raw':resets},
                           [{'name':n,'width':1} for n in ('wb_clk','core_clk','rst','arst')],
                           'wb_clk',{'name':'rst','level':'high'})

    def test_secondary_reset_polarity_is_not_guessed_from_name(self):
        self.assertEqual(self.parse()['extra_resets'],[{'signal':'arst','level':'low'}])
        self.assertEqual(self.parse(resets='arst')['extra_resets'],[{'signal':'arst','level':'high'}])

    def test_two_clocks_are_retained(self):
        self.assertEqual(self.parse(clocks='wb_clk core_clk')['clocks'],['core_clk','wb_clk'])

    def test_internal_fifo_clear_stays_in_rtl_not_environment(self):
        result = self.parse(resets='rst {{(fifo.wr && (~fifo.full))}}')
        self.assertEqual(result['extra_resets'],[])
        self.assertEqual(result['internal_reset_conditions'],['{{(fifo.wr && (~fifo.full))}}'])

    def test_complex_or_missing_roles_fail_closed(self):
        for clocks,resets in [('dut/clk','~arst'),('core_clk','~arst'),('wb_clk','~rst'),
                              ('wb_clk','{~arst | rst}'),('wb_clk','~internal_rst')]:
            with self.subTest(clocks=clocks,resets=resets),self.assertRaises(ValueError):
                self.parse(clocks,resets)

    def test_mixed_data_and_internal_clear_is_not_external_reset(self):
        result = self.parse(resets="rst {{((fifo_count != 2'b10) && core_clk)}}")
        self.assertEqual(result['extra_resets'], [])
        self.assertEqual(len(result['internal_reset_conditions']), 1)
        with self.assertRaises(ValueError):
            self.parse(resets='rst {{(fifo_clear || rst)}}')
