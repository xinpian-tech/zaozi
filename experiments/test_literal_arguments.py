"""Invalid constants are local source repairs, without guessing a replacement."""
from contextlib import redirect_stdout,redirect_stderr
import io
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from ltl_source import parse,LiteralArgumentError
import sequence_experiment as generation
from sequence_framework import ROOT,load_design
from repair_policy import model_repair_allowed


class LiteralArgumentsTests(unittest.TestCase):
    def test_all_invalid_literals_are_returned_in_one_repair_feedback(self):
        source='val x = BigInt("9", 2)\nval y = BigInt("f", 4)\nGen(valid, "goal")'
        design=load_design(ROOT/'experiments/tests/fixtures/tiny_design.json')
        _,_,errors=generation.materialize_response(source,'120s',design)
        self.assertEqual([r['line'] for r in errors],[1,2])
        self.assertTrue(all(r['code']=='ltl_bigint_radix' for r in errors))

    def test_bad_radix_and_digits_report_exact_fragment_location(self):
        for literal in ('BigInt("9", 2)','BigInt("f", 4)','BigInt("10", 1)',
                        'BigInt("1", 37)','BigInt("1", -2)','BigInt("", 10)',
                        'BigInt("0xff", 16)','BigInt("0b1", 2)'):
            source='// model constant\nval x = '+literal+'\nGen(valid, "goal")'
            with self.subTest(literal=literal),self.assertRaises(LiteralArgumentError) as failure:
                parse(source)
            diagnostic=failure.exception.diagnostic
            self.assertEqual((diagnostic['line'],diagnostic['col']),(2,9))
            self.assertEqual(diagnostic['file'],'model.ltl')
            self.assertIn('not signal width',diagnostic['message'])

    def test_valid_and_nonliteral_expressions_are_unchanged(self):
        for expression in ('BigInt("89abcdef", 16)','BigInt("-101", 2)',
                           'BigInt("123", 4)','BigInt("z", 36)','BigInt("+01", 10)',
                           'BigInt("9", radix)','BigInt(text, 4)',
                           'BigInt("\\u0031", 2)','BigInt(s"$digits", 4)',
                           'scala.math.BigInt("9", 4)'):
            source='val x = '+expression+'\nGen(valid, "goal")'
            with self.subTest(expression=expression):
                self.assertEqual(parse(source)['ltl'],source)
        source='// BigInt("9", 4)\nval text = "BigInt(\\"9\\", 4)"\nGen(valid, "goal")'
        self.assertEqual(parse(source)['ltl'],source)

    def test_huge_valid_literal_is_not_evaluated_or_rejected_by_python_int_limit(self):
        source='val x = BigInt("'+('9'*5000)+'", 10)\nGen(valid, "goal")'
        self.assertEqual(parse(source)['ltl'],source)

    def test_bad_literal_uses_local_repair_before_compiler_or_solver(self):
        design=load_design(ROOT/'experiments/tests/fixtures/tiny_design.json')
        bad='Gen(Ltl.is(payload, BigInt("9", 4)), "check_value")'
        good='Gen(Ltl.is(payload, BigInt("9", 16)), "check_value")'
        _,_,errors=generation.materialize_response(bad,'120s',design)
        self.assertEqual(errors[0]['code'],'ltl_bigint_radix')
        self.assertTrue(model_repair_allowed({'phase':'response-check','ok':False,'errors':errors}))
        replies=[{'model':'test','choices':[{'message':{'content':source},'finish_reason':'stop'}],
                  'usage':{'prompt_tokens':10,'completion_tokens':2,'total_tokens':12}} for source in (bad,good)]
        with TemporaryDirectory() as tmp:
            root=Path(tmp);divider='='*80
            modinfo=root/'modinfo.txt'
            modinfo.write_text(f'{divider}\nModule : tiny_external\n{divider}\n  10 0/1 result <= payload;\n')
            result={'phase':'solve','ok':True,'result':{'status':'generated','goals':[]}}
            with patch.object(generation,'send_completion',side_effect=replies) as send, \
                    patch.object(generation,'harness',return_value=(result,'')) as harness, \
                    redirect_stdout(io.StringIO()),redirect_stderr(io.StringIO()):
                rc=generation.main(['--design',str(ROOT/'experiments/tests/fixtures/tiny_design.json'),
                    '--modinfo',str(modinfo),'--out',str(root/'run')])
            self.assertEqual(rc,0)
            self.assertEqual(send.call_count,2)
            self.assertEqual(harness.call_count,1)
            self.assertEqual((root/'run/attempt-1/response.txt').read_text(),bad)
            self.assertEqual((root/'run/attempt-2/response.txt').read_text(),good)
            self.assertFalse((root/'run/attempt-1/solve').exists())
            summary=json.loads((root/'run/summary.json').read_text())
            self.assertEqual(summary['costs']['usage_reported']['total_tokens'],24)
            self.assertIn('ltl_bigint_radix',(root/'run/attempt-2/prompt.txt').read_text())


if __name__=='__main__':unittest.main()
