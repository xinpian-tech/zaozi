import unittest
from item_contract import normalize, scenario_constraints, sequence_fields


class ItemTests(unittest.TestCase):
    def bp(self):
        return {'protocol_flows':{'bus_field_mapping':{'cyc':'cyc','stb':'stb','ack':'ack','addr':'address','data':'payload','we':'write'}}}

    def test_remove_hidden_payload_scenario_not_structural_packet_constraint(self):
        source='class item; rand bit [7:0] address; constraint hidden {address inside {[0:31]};} constraint packet {data.size()==length;} endclass'
        code,removed=normalize(source,self.bp())
        self.assertEqual([r['name'] for r in removed],['hidden'])
        self.assertIn('rand bit [7:0] address;',code)
        self.assertIn('constraint packet',code)
        self.assertNotIn('[0:31]',code)

    def test_comments_and_strings_do_not_create_constraints(self):
        code='// constraint fake {address==0;}\nstring s="constraint fake { address == 0; }"; constraint real {other == 1;}'
        self.assertEqual(scenario_constraints(code,{'address'}),[])

    def test_nested_constraints_and_multiple_blocks(self):
        code='constraint a {if (write) {payload inside {1,2};}} constraint b {address > 0;}'
        cleaned,rows=normalize(code,self.bp())
        self.assertEqual(cleaned.strip(),'')
        self.assertEqual(len(rows),2)

    def test_unterminated_constraint_is_not_silently_removed(self):
        with self.assertRaisesRegex(ValueError,'unterminated'):
            scenario_constraints('constraint bad {address==0;',{'address'})
