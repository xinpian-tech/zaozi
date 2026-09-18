import json
from pathlib import Path
import tempfile
import unittest

from repair_policy import model_repair_allowed
from run_records import save
from sequence_framework import load_design, parse_response, write_sources
from ut_harness import elaboration_diagnostic


class ElaborationDiagnosticsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root/'source'
        design = load_design(Path(__file__).parent/'tests/fixtures/tiny_design.json')
        write_sources(self.source, design, parse_response('Gen(Ltl.isOnes(payload), "pattern")'), '120s')
        lines = (self.source/'ModelUT.scala').read_text().splitlines()
        self.line = lines.index('    // BEGIN MODEL LTL') + 2
        self.work = self.root/'work'
        self.record = self.work/'artifacts/ltl-error.json'
        self.error = dict(schema='ltl-argument-v1',code='ltl_unsigned_range',
                          file=str(self.source/'ModelUT.scala'),line=self.line,col=1,
                          message='constant does not fit unsigned width; use BigInt(digits, radix)')

    def test_known_typed_error_maps_to_model_and_allows_repair(self):
        for code in ('ltl_unsigned_range','ltl_signed_range'):
            save(self.record,{**self.error,'code':code})
            report=elaboration_diagnostic(self.work,self.source)
            self.assertTrue(model_repair_allowed(report))
            self.assertEqual(report['errors'][0]['file'],'model.ltl')
            self.assertEqual(report['errors'][0]['line'],1)
            self.assertEqual(report['errors'][0]['message'],self.error['message'])

    def test_framework_unknown_malformed_and_outside_source_errors_fail_closed(self):
        for changes in ({'code':'ltl_width'},{'code':'unknown'}, {'schema':'unknown'},
                        {'file':str(self.source/'DesignBinding.scala')},
                        {'file':str(self.root/'other/ModelUT.scala')},
                        {'line':1},{'line':self.line+100},{'line':True}, {'message':None}):
            with self.subTest(changes=changes):
                save(self.record,{**self.error,**changes})
                self.assertIsNone(elaboration_diagnostic(self.work,self.source))
        for data in ('[]','{','{}'):
            self.record.write_text(data)
            self.assertIsNone(elaboration_diagnostic(self.work,self.source))
        self.assertFalse(model_repair_allowed({'phase':'lower','ok':False,'errors':[self.error]}))
        self.assertFalse(model_repair_allowed({'phase':'elaboration-check','ok':False,'kind':'model_argument_error',
                                              'errors':[{'file':'model.ltl','code':'unknown'}]}))

    def test_missing_and_escaping_diagnostics_do_not_permit_repair(self):
        self.assertIsNone(elaboration_diagnostic(self.work,self.source))
        outside=self.root/'outside.json'
        save(outside,self.error)
        self.record.parent.mkdir(parents=True)
        self.record.symlink_to(outside)
        self.assertIsNone(elaboration_diagnostic(self.work,self.source))


if __name__ == '__main__': unittest.main()
