import unittest
from sv_constraint_baseline import render,decode,ConstraintError,validate_clauses
from test_directed_baselines import fixture
from directed_baselines import parse_response
import json


class ConstraintTests(unittest.TestCase):
    def test_cross_time_relations_and_fixed_environment(self):
        design,config=fixture()
        source,ports,cycles=render(design,config,{'cycles':3,'constraints':
            "foreach (data[i]) { data[i] inside {[8'h01:8'hff]}; if (i>0) data[i]==data[i-1]+1; req[i]==1; }"})
        self.assertEqual([p.name for p in ports],['data','req'])
        self.assertNotIn('rand bit [0:0] cfg',source)
        self.assertIn('data[i-1]',source)
        self.assertEqual(cycles,3)

    def test_escape_or_output_access_rejected(self):
        for clause in ('} endclass module evil; {','data[0]==ack;','$system("bad");',
                       '`include "bad"','foreach (data[i]) data[i]==cfg;',
                       'data[0]==dut.data;', 'post_randomize();','data[0] == 1; /*','data.randomize();'):
            with self.subTest(clause=clause),self.assertRaises(ConstraintError):validate_clauses(clause,['data','req'])

    def test_reductions_are_accepted(self):
        validate_clauses("data.sum() with (int'(item)) == 32;",['data'])

    def test_decode_rejects_incomplete_or_unknown(self):
        design,config=fixture();_,ports,cycles=render(design,config,{'cycles':1,'constraints':'req[0]==1;'})
        good='\n'.join(f'{i} 0 ff 1' for i in range(4))
        self.assertEqual(decode(good,ports,cycles)[0]['steps'][0]['drive']['data'],'0xff')
        for bad in (good.replace('ff','xx'),good.replace('ff','1ff'),good.split('\n')[0]):
            with self.assertRaises(ConstraintError):decode(bad,ports,cycles)

    def test_schema_requires_constraint_body_and_horizon(self):
        item={'label':'a','intent':'test','cycles':1,'constraints':'req[0]==1;'}
        self.assertEqual(parse_response(json.dumps({'intents':[item]}),'directed_sv_constraint'),[item])


if __name__=='__main__':unittest.main()
