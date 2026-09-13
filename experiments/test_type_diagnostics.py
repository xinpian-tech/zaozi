import unittest
from ut_harness import parse_type_errors


class TypeDiagnosticsTest(unittest.TestCase):
    def test_generated_ut_locations_map_back_to_original_ltl(self):
        import tempfile
        from pathlib import Path
        from sequence_framework import ROOT, load_design, parse_response, write_sources
        from ut_harness import ltl_diagnostics
        design = load_design(ROOT / "experiments/tests/fixtures/tiny_design.json")
        response = parse_response('val p = io.missing\nGen(p, "goal")\n')
        with tempfile.TemporaryDirectory() as temporary:
            source = write_sources(Path(temporary), design, response)
            line = (source / "ModelUT.scala").read_text().splitlines().index("    val p = io.missing") + 1
            error = {"file": str(source / "ModelUT.scala"), "line": line, "col": 9,
                     "message": f"{line} |    val p = io.missing\nNot found: missing"}
            mapped = ltl_diagnostics([error], source)[0]
            self.assertEqual((mapped["file"], mapped["line"], mapped["col"]), ("model.ltl", 1, 5))
            self.assertIn("1 |", mapped["message"])
            self.assertEqual(error["line"], line)

    def test_generic_lexer_error_is_prioritized_without_fabricating_compiler_kind(self):
        from repair_diagnostics import project
        errors=parse_type_errors('-- Error: /tmp/Example.scala:3:9\n3 | io.`valid\n  |         ^\n  |         unclosed quoted identifier\n1 error found\n')
        self.assertEqual(errors[0]['kind'],'Error')
        self.assertIsNone(errors[0]['code'])
        self.assertNotIn('error found',errors[0]['message'])
        self.assertEqual(project(errors)['selection'],'syntax-first')

    def test_crash_metadata_is_not_a_source_error(self):
        raw='''-- [E040] Syntax Error: /tmp/Example.scala:8:4
8 | p.S)))),
  |        ^
  | unindent expected, but ',' found
  exception occurred while parser /tmp/Example.scala
  An unhandled exception was thrown in the compiler.
  settings: -classpath enormous-provider-independent-path
java.lang.AssertionError
  at compiler.Parser.parse(Parser.scala:123)
'''
        errors=parse_type_errors(raw)
        self.assertEqual(len(errors),1)
        self.assertIn("unindent expected",errors[0]['message'])
        self.assertNotIn('classpath',errors[0]['message'])
        self.assertNotIn('AssertionError',errors[0]['message'])
        self.assertEqual(errors[0]['line'],8)
        self.assertEqual(errors[0]['kind'],'Syntax Error')
        self.assertEqual(errors[0]['code'],'E040')

    def test_type_details_and_later_diagnostics_survive(self):
        raw='''-- [E007] Type Mismatch Error: /tmp/One.scala:3:2
  Found: Node[Bool]
  Required: Ref[Bool]
  longer explanation available with -explain
An unhandled exception was thrown in the compiler.
  settings: ignored
-- [E006] Not Found Error: /tmp/Two.scala:5:6
  Not found: missing
'''
        errors=parse_type_errors(raw)
        self.assertEqual(len(errors),2)
        self.assertIn('Required: Ref[Bool]',errors[0]['message'])
        self.assertIn('longer explanation',errors[0]['message'])
        self.assertIn('Not found: missing',errors[1]['message'])


if __name__=='__main__': unittest.main()
