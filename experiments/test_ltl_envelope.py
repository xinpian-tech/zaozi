"""No-provider regressions for exact envelope handling and compact evidence."""
from contextlib import redirect_stdout, redirect_stderr
from copy import deepcopy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from coverage_table import unpack
from ltl_source import parse, unwrap, normalize, OutputFormatError
import sequence_experiment as generation
from sequence_framework import ROOT, load_design, render_model_ut
from task_context import TaskContext, RepairContext
from run_records import save


class EnvelopeTests(unittest.TestCase):
    def setUp(self):
        self.design = load_design(ROOT / 'experiments/tests/fixtures/tiny_design.json')
        self.body = '// 中文\nval p = valid & !done\nGen(p ### done, "ordered")\n'

    def test_single_fence_preserves_body_bytes_and_source_map(self):
        for newline in ('\n', '\r\n'):
            body = self.body.replace('\n', newline)
            for tag in ('', 'scala'):
                raw = '\n  \n```' + tag + newline + body + '```\n \t'
                with self.subTest(newline=newline, tag=tag):
                    extracted, audit = unwrap(raw)
                    self.assertEqual(extracted.encode(), body.encode())
                    self.assertEqual(parse(raw), parse(body))
                    self.assertEqual(raw[audit['body_start_character']:audit['body_end_character']], body)
                    self.assertEqual(audit['body_first_line'], 4)
                    self.assertEqual(audit['raw_sha256'], hashlib.sha256(raw.encode()).hexdigest())
                    self.assertEqual(audit['ltl_sha256'], hashlib.sha256(body.encode()).hexdigest())
                    self.assertEqual(render_model_ut(self.design, parse(raw)), render_model_ut(self.design, parse(body)))

    def test_plain_source_is_not_trimmed(self):
        raw = ' \n' + self.body + '\n\t'
        body, audit = unwrap(raw)
        self.assertEqual(body, raw)
        self.assertEqual(audit['kind'], 'raw')
        self.assertEqual(audit['raw_sha256'], audit['ltl_sha256'])

    def test_whitespace_bounded_delay_is_canonicalized_and_audited(self):
        raw='val ordered = valid ##(1, Some(4))(!done)\nGen(ordered, "ordered")\n'
        parsed=parse(raw)
        self.assertIn('valid.##(1, Some(4))(!done)',parsed['ltl'])
        normalized,audit=normalize(raw)
        self.assertEqual(normalized,parsed['ltl'])
        self.assertEqual(len(audit['canonical_edits']),1)
        self.assertEqual(audit['canonical_edits'][0]['kind'],'bounded_delay_receiver_dot')
        self.assertEqual(audit['raw_sha256'],hashlib.sha256(raw.encode()).hexdigest())
        self.assertEqual(audit['normalized_ltl_sha256'],hashlib.sha256(normalized.encode()).hexdigest())
        # Existing canonical syntax, comments and string contents are untouched.
        canonical='val text = "valid ##(1)(done)"\n// valid ##(1)(done)\nGen(valid.##(1)(done), "ordered")\n'
        self.assertEqual(normalize(canonical)[0],canonical)
        self.assertEqual(normalize(canonical)[1]['canonical_edits'],[])

    def test_ambiguous_truncated_or_multiple_envelopes_are_rejected(self):
        block = '```scala\n' + self.body + '```'
        for raw in (block + '\nexplanation', 'Here is code:\n' + block, block + '\n' + block,
                    '```scala\n' + self.body, '```scala\n' + self.body + '``` trailing',
                    '```python\n' + self.body + '```', '```scala\n```\n' + self.body + '```',
                    '```scala ' + self.body + '```', '{"ltl": "answer"}'):
            with self.subTest(raw=raw[:40]), self.assertRaises(OutputFormatError):
                parse(raw)

    def test_fence_does_not_bypass_contract_or_goal_budget(self):
        for body in ('Assume(valid.I, "bad")\n' + self.body,
                     self.body + 'Gen(done, "ordered")\n', '```scala\n'):
            with self.subTest(body=body), self.assertRaises(ValueError):
                parse('```scala\n' + body + '```')
        code, _, errors = generation.materialize_response('```scala\n' + self.body + '```', '5s', self.design, max_intents=0)
        self.assertFalse(code)
        self.assertTrue(errors)
        self.assertEqual(parse('```scala\nSTOP\n```'), {'stop': True})

    @staticmethod
    def reply(raw):
        return {'model':'test', 'choices':[{'message':{'role':'assistant','content':raw}, 'finish_reason':'stop'}],
                'usage':{'prompt_tokens':10,'completion_tokens':2,'total_tokens':12}}

    def test_fenced_model_response_uses_one_request_and_preserves_audit(self):
        raw = '```scala\r\n' + self.body.replace('\n', '\r\n') + '```\r\n'
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            modinfo = root / 'modinfo.txt'
            divider = '=' * 80
            modinfo.write_text(f'{divider}\nModule : tiny_external\n{divider}\n  10 0/1 result <= payload;\n')
            def checked_harness(sources, *args, **kwargs):
                self.assertEqual((sources/'model.ltl').read_bytes(), unwrap(raw)[0].encode())
                return {'phase':'solve','ok':True,'result':{'status':'generated','goals':[]}}, ''
            with patch.object(generation, 'send_completion', return_value=self.reply(raw)) as send, \
                    patch.object(generation, 'harness', side_effect=checked_harness), \
                    redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                result = generation.main(['--design',str(ROOT/'experiments/tests/fixtures/tiny_design.json'),
                    '--modinfo',str(modinfo),'--out',str(root/'run')])
            self.assertEqual(result, 0)
            self.assertEqual(send.call_count, 1)
            attempt = root/'run/attempt-1'
            self.assertEqual((attempt/'response.txt').read_bytes(), raw.encode())
            self.assertEqual(json.loads((attempt/'response-normalization.json').read_text()), normalize(raw)[1])
            self.assertFalse((root/'run/attempt-2').exists())

    def test_format_only_repair_does_not_expose_or_dispatch_framework(self):
        _, _, errors = generation.materialize_response('Explanation\n```scala\n'+self.body+'```', '5s', self.design)
        task = TaskContext(self.design)
        repair = RepairContext(task, errors)
        self.assertTrue(repair.format_only)
        self.assertEqual([t['function']['name'] for t in repair.tools], ['read_diagnostics'])
        self.assertNotIn('framework', repair.initial_evidence())
        self.assertNotIn('environment', repair.initial_evidence())
        for tool in ('read_framework','read_rtl','read_context'):
            with self.subTest(tool=tool), self.assertRaises(ValueError):
                repair.dispatch(tool, {})
        self.assertEqual(json.loads(repair.dispatch('read_diagnostics', {})['text']), errors)
        # A mixture of syntax/format diagnostics must retain the compiler repair APIs.
        mixed = RepairContext(task, errors + [{'kind':'Syntax Error', 'message':'unclosed'}])
        self.assertFalse(mixed.format_only)
        self.assertIn('read_framework', [t['function']['name'] for t in mixed.tools])

    def test_compact_feedback_roundtrips_all_rows_and_metadata(self):
        feedback = {'bins':{'line':[400,1]},'score':12,'percent':{'line':0.25},
            'gaps':[{'type':'line','module':'same','context':'  中文\t', 'line':n,
                     'extra':{'unknown_field':[None, False, n]}} for n in range(300)]}
        original = deepcopy(feedback)
        context = TaskContext(self.design, feedback, {'coverage_round':2})
        packet = context.initial_evidence()
        compact = packet['current_feedback']['coverage']
        self.assertEqual({**compact,'gaps':unpack(compact['gaps'])}, original)
        self.assertLess(len(json.dumps(compact)), len(json.dumps(feedback)))
        self.assertNotIn('bins', packet['coverage'])
        self.assertNotIn('percent', packet['coverage'])
        self.assertEqual(feedback, original)
        self.assertEqual(json.loads(context.dispatch('read_context',{'topic':'coverage'})['text']), original)

    def test_saved_profile_preserves_artifacts_and_checks_lossless_evidence(self):
        from profile_ltl_evidence import profile
        feedback = {'gaps':[{'type':'line','module':'same','line':n} for n in range(100)], 'score':12}
        history = {'coverage_round':2}
        old = TaskContext(self.design, feedback, history).initial_evidence()
        old['current_feedback'] = {'complete':True, 'coverage':feedback}
        old['coverage']['score'] = 12
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            generation = root/'round-2/generation'
            save(generation/'manifest.json', {'design':self.design.record(), 'feedback':feedback})
            save(generation.parent/'accepted-history.json', history)
            save(generation/'attempt-1/initial-evidence.json', old)
            (generation/'attempt-1/response.txt').write_text('```scala\n'+self.body+'```')
            before = {p:p.read_bytes() for p in root.rglob('*') if p.is_file()}
            report = profile(generation)
            self.assertTrue(report['coverage_reconstruction_checked'])
            self.assertTrue(report['non_coverage_evidence_unchanged'])
            self.assertGreater(report['reduction_percent'], 0)
            self.assertEqual(report['first_response']['kind'], 'single-scala-fence')
            self.assertEqual(before, {p:p.read_bytes() for p in root.rglob('*') if p.is_file()})
            old['environment'] = {'invented':'wrong run'}
            save(generation/'attempt-1/initial-evidence.json', old)
            with self.assertRaisesRegex(ValueError, 'non-coverage evidence changed'):
                profile(generation)


if __name__ == '__main__':
    unittest.main()
