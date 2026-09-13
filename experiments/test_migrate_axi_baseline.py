import unittest
from migrate_axi_baseline import translate, migrate_sv, migrate_dsl, item_fields


class MigrationTests(unittest.TestCase):
    bus = {'we': 'wr', 'awaddr': 'wa', 'araddr': 'ra', 'data': 'wd', 'wstrb': 'ws'}

    def test_aliases_preserve_values_and_outside_checks(self):
        before = "assert(txn.randomize() with { kind == item::WRITE; addr == 10'h12; data == 32'h42; strb == 4'h3; }); check(original_output);"
        after = migrate_sv(before, self.bus)
        self.assertEqual(after, "assert(txn.randomize() with { wr == 1; wa == 10'h12; wd == 32'h42; ws == 4'h3; }); check(original_output);")
        self.assertEqual(translate('kind == READ; addr == 7', self.bus), 'wr == 0; ra == 7')
        with self.assertRaises(ValueError):
            translate('addr == 7', self.bus)

    def test_dsl_is_same_mapping_and_checks_are_untouched(self):
        before = {'constraints': ['kind == READ', 'addr == 7'], 'expected': {'output': 42}}
        after = migrate_dsl(before, self.bus)
        self.assertEqual(after, {'constraints': ['wr == 0', 'ra == 7'], 'expected': {'output': 42}})

    def test_item_ownership_and_width_are_from_contract(self):
        code = 'rand bit [31:0] ws; rand bit valid; rand kind_e kind; `uvm_object_utils(item)'
        result = item_fields(code, [{'name':'ws','width':4,'is_rand':True},
                                   {'name':'valid','width':1,'is_rand':False},
                                   {'name':'out','width':8,'is_rand':False}])
        self.assertIn('rand logic [3:0] ws;', result)
        self.assertIn('logic valid;', result)
        self.assertIn('logic [7:0] out;', result)
        self.assertNotIn('rand kind_e', result)
