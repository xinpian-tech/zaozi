"""Policy and recovery regressions; no provider credentials or paid requests."""
import backend_imports
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import urllib.error
from dataclasses import replace
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import isolation
import sequence_experiment as generation
from sequence_framework import load_design, parse_response
from rvprobe.backend.validation import validate
from goal_coverage import measure_goals
from run_records import Records, begin, finish, totals, utc
from compare_runs import compare_runs

FIXTURES = Path(__file__).resolve().parent / "tests/fixtures"
SV = '''module TestUT(input clock, reset, input [7:0] payload, input valid,
output [7:0] result, output done);
wire [7:0] result_net;
wire done_net;
tiny_external dut (.clk(clock), .rst(reset), .payload(payload), .valid(valid),
 .result(result_net), .done(done_net));
assign result = result_net;
assign done = done_net;
target: cover property (@(posedge clock) valid & payload <= 8'h7);
endmodule
'''


class WiringTest(unittest.TestCase):
    def setUp(self):
        self.design = load_design(FIXTURES / "tiny_design.json")

    def test_direct_connections_aliases_and_comparison_are_accepted(self):
        self.assertTrue(validate(SV, self.design, "TestUT", ["target"])["passed"])
        aliased = SV.replace(".payload(payload)", ".payload(copy)").replace("wire done_net;", "wire done_net;\nassign copy = payload;")
        self.assertTrue(validate(aliased, self.design, "TestUT", ["target"])["passed"])
        low = replace(self.design, reset_active_low=True)
        self.assertTrue(validate(SV.replace(".rst(reset)", ".rst(~reset)"), low, "TestUT", ["target"])["passed"])

    def test_restrictions_missing_dut_and_corrupt_wiring_fail_closed(self):
        variants = [SV.replace(".payload(payload)", ".payload(8'h0)"),
            SV.replace(".clk(clock)", ".clk(valid)"), SV.replace(".rst(reset)", ".rst(~reset)"),
            SV.replace("assign result = result_net;", "assign result = payload;"),
            SV.replace(".result(result_net)", ".result(payload)").replace("assign result = result_net;", "assign result = payload;"),
            SV.replace(".done(done_net)", ".done(result_net)").replace("assign done = done_net;", "assign done = result_net;"),
            SV.replace("tiny_external dut", "Other dut"),
            SV.replace("wire done_net;", "wire done_net; Other helper (.x(payload));"),
            SV.replace("endmodule", "endmodule\nmodule tiny_external(); endmodule"),
            SV.replace("target: cover", "target: assume"),
            SV.replace("wire done_net;", "`define altered\nwire done_net;"),
            SV.replace("assign done = done_net;", "assign done = done_net;\nalways @(posedge clock) done_net <= valid;"),
            SV.replace("wire done_net;", "wire done_net = valid;"),
            SV.replace("assign done = done_net;", "assign done = done_net;\nalways @(posedge clock) payload[0] <= valid;"),
            SV.replace(".valid(valid)", ".valid(payload)"),
            SV.replace("target:", "extra: assert property(valid);\ntarget:")]
        for text in variants:
            with self.subTest(text=text), self.assertRaises(ValueError):
                validate(text, self.design, "TestUT", ["target"])


class CompilerPathTest(unittest.TestCase):
    def test_external_sources_keep_semanticdb_and_plugin_inside_work(self):
        original = ["-Xsemanticdb", "-sourceroot", "/workspace",
                    "-semanticdb-target", "/workspace/out", "-Xplugin:/tool/plugin.jar",
                    "-experimental"]
        config = {"options": list(original)}
        options = isolation.compiler_options(config, Path('/dev/shm/run/sources'), Path('/dev/shm/run/solve'))
        self.assertEqual(options, ["-Xsemanticdb", "-Xplugin:/tool/plugin.jar", "-experimental",
                                  "-sourceroot", "/dev/shm/run/sources",
                                  "-semanticdb-target", "/dev/shm/run/solve/semanticdb"])
        self.assertEqual(config['options'], original)

    def test_inline_and_repeated_old_path_options_are_removed(self):
        config = {'options':['-sourceroot:/old', '-semanticdb-target=/old/out',
                             '-sourceroot','/another', '-Xsemanticdb']}
        options = isolation.compiler_options(config, Path('/new/src'), Path('/new/work'))
        self.assertEqual(options, ['-Xsemanticdb', '-sourceroot', '/new/src',
                                   '-semanticdb-target', '/new/work/semanticdb'])

    def test_incomplete_inherited_option_fails_closed(self):
        for options in (['-sourceroot'], ['-semanticdb-target', '-Xsemanticdb']):
            with self.assertRaisesRegex(ValueError, 'missing value'):
                isolation.compiler_options({'options':options}, Path('/src'), Path('/work'))


@unittest.skipUnless(shutil.which("bwrap") and Path("/nix/store").is_dir(), "Linux bubblewrap/Nix required")
class IsolationTest(unittest.TestCase):
    def test_no_host_secret_no_host_write_no_network(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            secret = root / "sentinel"
            secret.write_text("synthetic-test-only")
            readonly = root / "readonly-source"
            readonly.write_text("synthetic-source")
            work = root / "private"
            work.mkdir()
            script = f'''import os, socket
from pathlib import Path
assert "RVPROBE_LLM_API_KEY" not in os.environ
assert not Path({str(secret)!r}).exists()
try:
    Path({str(readonly)!r}).write_text("bad")
except OSError:
    pass
else:
    raise AssertionError("host write permitted")
assert socket.if_nameindex() == [(1, "lo")]
Path("sandbox-output").write_text("allowed")
'''
            executable = str(Path(sys.executable).resolve())
            command = isolation.command([executable, "-c", script], readonly=[readonly], writable=work, env={})
            env = {**os.environ, "RVPROBE_LLM_API_KEY": "synthetic-secret"}
            result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((work / "sandbox-output").read_text(), "allowed")
            self.assertFalse((root / "outside-write").exists())
            self.assertEqual(readonly.read_text(), "synthetic-source")

    def test_no_unsandboxed_fallback(self):
        with patch.object(isolation.shutil, "which", return_value=None), self.assertRaisesRegex(RuntimeError, "no unsandboxed fallback"):
            isolation.command(["true"], readonly=[], writable="/tmp/private", env={})


class AccountingTest(unittest.TestCase):
    def test_unknown_usage_is_not_reported_as_zero_cost(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(totals(directory)['usage_reported']['total_tokens'],0)
            Records(directory).append({'id':'manual','phase':'model-request','status':'completed',
                                       'usage':{'total_tokens':None}})
            cost=totals(directory)
            self.assertIsNone(cost['usage_reported']['total_tokens'])
            self.assertFalse(cost['token_accounting_complete'])
            self.assertEqual(cost['requests_without_usage'],1)

    def test_failed_requests_interrupted_events_and_nested_records_count_once(self):
        with tempfile.TemporaryDirectory() as directory:
            records = Records(directory)
            with records.phase("model-request") as event:
                event["usage"] = {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            with self.assertRaises(TimeoutError), records.phase("model-request"):
                raise TimeoutError()
            records.append({"id": "interrupted", "phase": "model-request", "status": "running"})
            result = totals(directory)
            self.assertEqual(result["requests"], 3)
            self.assertEqual(result["usage_reported"]["total_tokens"], 15)
            self.assertEqual(result["requests_without_usage"], 2)
            self.assertFalse(result["token_accounting_complete"])
            self.assertEqual(result["phases"]["model-request"]["failures"], 2)

    def test_manifest_resume_identity_and_comparison(self):
        import time
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "run"
            comparison = {"model": "saved-response", "design": {"top": "tiny"}}
            started = begin(root, comparison)
            self.assertEqual(begin(root, comparison, True), started)
            with self.assertRaisesRegex(ValueError, "fingerprint changed"):
                begin(root, {**comparison, "model": "changed"}, True)
            summary = {"status": "completed"}
            finish(root, summary, started, time.monotonic(), **comparison)
            result = compare_runs([root, root])
            self.assertTrue(result["same_comparison_basis"])
            self.assertEqual(result["runs"][0]["requests"], 0)
            self.assertGreaterEqual(summary["elapsed_seconds"], 0)

    def test_retry_counts_failure_without_changing_model(self):
        args = SimpleNamespace(request_retries=3, model="fixed-model", temperature=0.3, timeout=1)
        info = {"usage": {"prompt_tokens": 2, "completion_tokens": 1, "total_tokens": 3}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(generation, "invoke", side_effect=[urllib.error.HTTPError('local', 429, 'busy', {}, None), ("response", info)]) as invoke, \
                 patch.object(generation.time, "sleep"):
                self.assertEqual(generation.request_model(args, "prompt", root, Records(root))[0], "response")
                self.assertEqual([call.args[1] for call in invoke.call_args_list], ["fixed-model", "fixed-model"])
            self.assertEqual(totals(root)["requests"], 2)
            self.assertEqual(totals(root)["requests_without_usage"], 1)
            self.assertEqual(totals(root)["usage_reported"]["total_tokens"], 3)

    def test_auth_errors_are_not_retried(self):
        args = SimpleNamespace(request_retries=3, model="fixed-model", temperature=0.3, timeout=1)
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(generation, "invoke", side_effect=urllib.error.HTTPError("local", 401, "auth", {}, None)) as invoke:
                with self.assertRaises(RuntimeError):
                    generation.request_model(args, "prompt", Path(directory), Records(directory))
                self.assertEqual(invoke.call_count, 1)


class GoalFeedbackTest(unittest.TestCase):
    def test_one_failed_sequence_preserves_other_sequences_of_same_intent(self):
        def coverage():
            return {"bins": {"line": [1, 1]}, "uncovered": [], "percent": {"line": 100},
                    "replay": {"passed": True}, "modinfo": "fixture"}
        replay = SimpleNamespace(simulate=lambda *args: coverage())
        goal = {"label": "one_intent", "status": "generated"}
        with tempfile.TemporaryDirectory() as directory, patch("goal_coverage.witness_frames", side_effect=[
                [{"kind": "witness"}], ValueError("bad sample"), [{"kind": "witness"}], [{"kind": "witness"}]]):
            rows, frames = measure_goals(replay, None, None, [], coverage(),
                [{**goal, "sequences": [goal] * 4}], 1, directory)
            self.assertEqual(len(rows), 4)
            self.assertEqual(len(frames), 3)
            self.assertEqual({r["intent_label"] for r in rows}, {"one_intent"})
            self.assertEqual([r["sample_index"] for r in rows], [1, 2, 3, 4])
            self.assertEqual(rows[1]["status"], "replay-failed")

    def test_independent_witness_and_drain_and_partial_failures(self):
        def coverage(lines):
            return {"bins": {"line": [10, 10-len(lines)]}, "uncovered": [[x, "code"] for x in lines],
                    "percent": {"line": 100-10*len(lines)}, "replay": {"passed": True}, "modinfo": "fixture"}
        replay = SimpleNamespace(simulate=lambda name, frames: coverage([2]) if name.endswith("witness") else coverage([]))
        goals = [{"label": "bad", "status": "error"}, {"label": "good", "status": "generated"},
                 {"label": "broken", "status": "generated"}]
        with tempfile.TemporaryDirectory() as directory, patch("goal_coverage.witness_frames", side_effect=[
                [{"kind": "witness"}, {"kind": "drain"}], ValueError("bad trace")]):
            rows, frames = measure_goals(replay, None, None, [], coverage([1, 2]), goals, 1, directory)
            self.assertEqual([r["status"] for r in rows], ["not-generated", "replayed", "replay-failed"])
            self.assertEqual(rows[1]["witness_closed_lines"], [1])
            self.assertEqual(rows[1]["drain_added_lines"], [2])
            self.assertEqual(len(frames), 2)
            self.assertEqual(json.loads((Path(directory) / "goal-coverage.json").read_text()), rows)

    def test_stop_is_not_an_empty_ut_or_proof(self):
        data = {"stop": {"reason": "no useful target"}, "proofObligations": []}
        self.assertEqual(parse_response("STOP"), {"stop": True})
        for bad in ({"stop": {"reason": ""}, "proofObligations": []}, {**data, "ut": {}}):
            with self.assertRaises(ValueError):
                parse_response(json.dumps(bad))

    def test_full_rtl_evidence_preserves_distant_and_included_context(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rtl, includes = root / "design.v", root / "include"
            includes.mkdir()
            rtl.write_text("// distant antecedent\n" + "\n" * 100 + "// target\n")
            (includes / "constants.vh").write_text("// task-specific constants\n")
            design = replace(load_design(FIXTURES / "tiny_design.json"), sources=(rtl,), include_dirs=(includes,))
            evidence = generation.design_evidence(design)
            self.assertIn("1: // distant antecedent", evidence)
            self.assertIn("102: // target", evidence)
            self.assertIn("constants.vh", evidence)
            self.assertEqual(evidence.count("SHA-256:"), 2)


class GenerationRecoveryTest(unittest.TestCase):
    def test_missing_nix_stops_before_paid_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch('framework_runtime.shutil.which', return_value=None), \
                    patch.object(generation, 'send_completion') as send, \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(generation.main(self.arguments(root)), 1)
                send.assert_not_called()
            summary = json.loads((root/'run/summary.json').read_text())
            self.assertEqual(summary['failure_kind'], 'framework_infrastructure_failure')
            self.assertFalse(summary['model_repair_allowed'])
            self.assertEqual(summary['costs']['requests'], 0)
            self.assertIn('missing from PATH', summary['error'])

    def test_prompt_only_does_not_require_nix(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(generation, 'runtime_commands', side_effect=RuntimeError('unavailable')) as check, \
                    patch.object(generation, 'send_completion') as send, \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(generation.main([*self.arguments(root), '--prompt-only']), 0)
                check.assert_not_called()
                send.assert_not_called()

    def test_missing_runtime_is_detected_before_any_provider_request(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(generation,'runtime_hashes',side_effect=FileNotFoundError('missing trusted file')), \
                    patch.object(generation,'send_completion') as send, \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(generation.main(self.arguments(root)),1)
                send.assert_not_called()
            summary = json.loads((root/'run/summary.json').read_text())
            self.assertEqual(summary['costs']['requests'],0)
            self.assertIn('missing trusted file',summary['error'])

    def test_input_check_and_unknown_failures_never_request_model_repair(self):
        response = (FIXTURES/'tiny_intents.ltl').read_text()
        for phase in ('input-check','initial-state','wiring-check','lower','unknown-phase'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                reply = {'usage':{'prompt_tokens':10,'completion_tokens':20,'total_tokens':30},
                         'choices':[{'message':{'role':'assistant','content':response}}]}
                report = {'phase':phase,'ok':False,'detail':'trusted input missing'}
                with patch.object(generation,'send_completion',return_value=reply) as send, \
                        patch.object(generation,'harness',return_value=(report,'')), \
                        contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(generation.main(self.arguments(root)),1)
                    self.assertEqual(send.call_count,1)
                self.assertFalse((root/'run/attempt-2').exists())
                summary = json.loads((root/'run/summary.json').read_text())
                self.assertFalse(summary['model_repair_allowed'])

    def arguments(self, root):
        divider = "=" * 72
        modinfo = root / "modinfo.txt"
        modinfo.write_text(f"{divider}\nModule : tiny_external\n{divider}\n  10 0/1 result <= payload;\n")
        return ["--design", str(FIXTURES / "tiny_design.json"), "--modinfo", str(modinfo),
                "--out", str(root / "run"), "--attempts", "2"]

    def test_typed_model_argument_error_uses_existing_repair_budget(self):
        response = (FIXTURES/'tiny_intents.ltl').read_text()
        reply = {'usage':{'prompt_tokens':10,'completion_tokens':20,'total_tokens':30},
                 'choices':[{'message':{'role':'assistant','content':response}}]}
        bad = {'phase':'elaboration-check','ok':False,'kind':'model_argument_error',
               'errors':[{'file':'model.ltl','line':1,'col':1,'code':'ltl_unsigned_range',
                          'message':'constant does not fit unsigned width'}]}
        ok = {'phase':'solve','ok':True,'result':{'status':'generated','goals':[]}}
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            with patch.object(generation,'send_completion',return_value=reply) as send, \
                    patch.object(generation,'harness',side_effect=[(bad,''),(ok,'')]), \
                    contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(generation.main(self.arguments(root)),0)
            self.assertEqual(send.call_count,2)
            self.assertIn('constant does not fit unsigned width',(root/'run/attempt-2/prompt.txt').read_text())
            summary=json.loads((root/'run/summary.json').read_text())
            self.assertEqual(summary['attempts'],2)
            self.assertEqual(summary['costs']['usage_reported']['total_tokens'],60)

    def test_resume_reuses_response_after_infrastructure_failure_without_another_request(self):
        response = (FIXTURES / "tiny_intents.ltl").read_text()
        info = {"usage": {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30}}
        ok = {"phase": "solve", "ok": True, "result": {"status": "generated", "utCount": 1,
              "goals": [{"label": label, "status": "generated"}
                        for label in parse_response(response)['labels']]}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = self.arguments(root)
            replies = [{**info, "choices": [{"message": {"role": "assistant", "content": response}}]}]
            with patch.object(generation, "send_completion", side_effect=replies) as invoke, \
                 patch.object(generation, "harness", side_effect=[({"phase": "toolchain", "ok": False, "detail": "transient infrastructure"}, ""), (ok, "")]) as harness, \
                 contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(generation.main(args), 1)
                self.assertEqual(generation.main([*args, "--resume"]), 0)
                self.assertEqual(invoke.call_count, 1)
                self.assertEqual(harness.call_count, 2)
            summary = json.loads((root / "run/summary.json").read_text())
            self.assertEqual(summary["status"], "generated")
            self.assertEqual(summary["tokens"], 30)
            self.assertEqual(summary["costs"]["requests"], 1)
            self.assertEqual(summary["costs"]["phases"]["generation-session"]["count"], 2)

    def test_partial_solve_gets_goal_local_feedback_and_can_be_repaired(self):
        response = (FIXTURES / 'tiny_intents.ltl').read_text()
        labels = parse_response(response)['labels']
        info = {'usage': {'prompt_tokens': 10, 'completion_tokens': 20, 'total_tokens': 30}}
        reply = {**info, 'choices': [{'message': {'role': 'assistant', 'content': response}}]}
        partial = {'phase':'solve','ok':True,'result':{'status':'partial','utCount':1,
            'goals':[{'label':labels[0],'status':'generated'},
                     *[{'label':label,'status':'infeasible'} for label in labels[1:]]]}}
        complete = {'phase':'solve','ok':True,'result':{'status':'generated','utCount':1,
            'goals':[{'label':label,'status':'generated'} for label in labels]}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(generation,'send_completion',return_value=reply) as send, \
                    patch.object(generation,'harness',side_effect=[(partial,''),(complete,'')]) as harness, \
                    contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(generation.main(self.arguments(root)),0)
            self.assertEqual(send.call_count,2)
            self.assertEqual(harness.call_count,2)
            prompt=(root/'run/attempt-2/prompt.txt').read_text()
            self.assertIn('jg_goal_infeasible',prompt)
            self.assertIn('Keep every already-generated goal expression',prompt)
            saved=json.loads((root/'run/attempt-1/goal-shortfall-feedback.json').read_text())
            self.assertEqual([row['goal'] for row in saved['errors']],labels[1:])
            summary=json.loads((root/'run/summary.json').read_text())
            self.assertEqual(summary['status'],'generated')
            self.assertEqual(summary['costs']['usage_reported']['total_tokens'],60)

    def test_failed_partial_repair_falls_back_to_best_verified_partial(self):
        response = (FIXTURES / 'tiny_intents.ltl').read_text()
        labels = parse_response(response)['labels']
        info = {'usage': {'prompt_tokens': 10, 'completion_tokens': 20, 'total_tokens': 30}}
        replies = [{**info,'choices':[{'message':{'role':'assistant','content':text}}]}
                   for text in (response,'STOP')]
        partial = {'phase':'solve','ok':True,'result':{'status':'partial','utCount':1,
            'goals':[{'label':labels[0],'status':'generated'},
                     *[{'label':label,'status':'unknown'} for label in labels[1:]]]}}
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            with patch.object(generation,'send_completion',side_effect=replies) as send, \
                    patch.object(generation,'harness',return_value=(partial,'')) as harness, \
                    contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(generation.main(self.arguments(root)),0)
            self.assertEqual(send.call_count,2)
            self.assertEqual(harness.call_count,1)
            summary=json.loads((root/'run/summary.json').read_text())
            self.assertEqual(summary['status'],'partial')
            self.assertEqual(summary['partial_fallback']['selected_attempt'],1)
            self.assertEqual(summary['result'],partial['result'])
            self.assertEqual(summary['costs']['usage_reported']['total_tokens'],60)

    def partial_report(self, statuses):
        labels = parse_response((FIXTURES / 'tiny_intents.ltl').read_text())['labels']
        self.assertEqual(len(statuses), len(labels))
        goals = [{'label':label, 'status':'error' if status == 'compile_timeout' else status,
                  **({'failureKind':'property_compile_timeout'} if status == 'compile_timeout' else {})}
                 for label, status in zip(labels, statuses)]
        status = ('generated' if all(s == 'generated' for s in statuses) else
                  'partial' if 'generated' in statuses else 'no-witness')
        return {'phase':'solve', 'ok':True, 'result':{'status':status, 'utCount':1, 'goals':goals}}

    def test_resource_only_partial_stops_after_one_no_progress_repair(self):
        response = (FIXTURES / 'tiny_intents.ltl').read_text()
        reply = {'usage':{'prompt_tokens':10,'completion_tokens':20,'total_tokens':30},
                 'choices':[{'message':{'role':'assistant','content':response}}]}
        for resource in ('unknown', 'compile_timeout'):
            with self.subTest(resource=resource), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                partial = self.partial_report(['generated', resource, resource, resource])
                with patch.object(generation, 'send_completion', return_value=reply) as send, \
                        patch.object(generation, 'harness', return_value=(partial, '')) as harness, \
                        contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(generation.main([*self.arguments(root), '--attempts', '3']), 0)
                self.assertEqual(send.call_count, 2)
                self.assertEqual(harness.call_count, 2)
                self.assertFalse((root / 'run/attempt-3').exists())
                summary = json.loads((root / 'run/summary.json').read_text())
                self.assertEqual(summary['status'], 'partial')
                self.assertEqual(summary['result'], partial['result'])
                self.assertEqual(summary['partial_fallback']['selected_attempt'], 1)
                stop = summary['resource_repair_stop']
                self.assertEqual(stop['reason'], 'resource-repair-stalled')
                self.assertEqual(len(stop['unresolved_goals']), 3)
                self.assertFalse(summary['history'][-1]['repairScheduled'])
                self.assertEqual(summary['costs']['usage_reported']['total_tokens'], 60)
                self.assertEqual(summary['costs']['requests'], 2)
                manifest = json.loads((root / 'run/manifest.json').read_text())
                self.assertEqual(manifest['resource_repair_policy'], stop['policy'])
                self.assertEqual(json.loads((root / 'run/attempt-2/resource-repair-stop.json').read_text()), stop)
                self.assertTrue((root / 'run/attempt-2/goal-shortfall-feedback.json').is_file())

    def test_nonresource_no_witness_progress_and_changed_failures_keep_repair_budget(self):
        response = (FIXTURES / 'tiny_intents.ltl').read_text()
        reply = {'usage':{'prompt_tokens':10,'completion_tokens':20,'total_tokens':30},
                 'choices':[{'message':{'role':'assistant','content':response}}]}
        initial = ['generated', 'unknown', 'unknown', 'unknown']
        scenarios = {
            'infeasible': (['generated', 'infeasible', 'infeasible', 'infeasible'],) * 2,
            'no-witness': (['unknown'] * 4,) * 2,
            'mixed-failure': (['generated', 'unknown', 'unknown', 'infeasible'],) * 2,
            'new-generated-goal': (initial, ['generated', 'generated', 'unknown', 'unknown']),
            'different-generated-goal': (initial, ['unknown', 'generated', 'unknown', 'unknown']),
            'changed-to-infeasible': (initial, ['generated', 'unknown', 'unknown', 'infeasible']),
            'changed-resource-class': (initial, ['generated', 'unknown', 'unknown', 'compile_timeout']),
        }
        for name, (first, second) in scenarios.items():
            with self.subTest(scenario=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                reports = [(self.partial_report(states), '') for states in (first, second, ['generated'] * 4)]
                with patch.object(generation, 'send_completion', return_value=reply) as send, \
                        patch.object(generation, 'harness', side_effect=reports) as harness, \
                        contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(generation.main([*self.arguments(root), '--attempts', '3']), 0)
                self.assertEqual(send.call_count, 3)
                self.assertEqual(harness.call_count, 3)
                summary = json.loads((root / 'run/summary.json').read_text())
                self.assertEqual(summary['status'], 'generated')
                self.assertNotIn('resource_repair_stop', summary)
                self.assertEqual(summary['costs']['usage_reported']['total_tokens'], 90)

    def test_source_error_breaks_consecutive_resource_stall_detection(self):
        response = (FIXTURES / 'tiny_intents.ltl').read_text()
        reply = {'usage':{'prompt_tokens':10,'completion_tokens':20,'total_tokens':30},
                 'choices':[{'message':{'role':'assistant','content':response}}]}
        partial = self.partial_report(['generated', 'unknown', 'unknown', 'unknown'])
        typed = {'phase':'typecheck', 'ok':False,
                 'errors':[{'file':'model.ltl', 'line':1, 'col':1, 'message':'type mismatch'}]}
        reports = [(report, '') for report in
                   (partial, typed, partial, self.partial_report(['generated'] * 4))]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(generation, 'send_completion', return_value=reply) as send, \
                    patch.object(generation, 'harness', side_effect=reports) as harness, \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(generation.main([*self.arguments(root), '--attempts', '4']), 0)
            self.assertEqual(send.call_count, 4)
            self.assertEqual(harness.call_count, 4)
            summary = json.loads((root / 'run/summary.json').read_text())
            self.assertEqual(summary['status'], 'generated')
            self.assertNotIn('resource_repair_stop', summary)
            self.assertEqual(summary['costs']['usage_reported']['total_tokens'], 120)

    def test_liveness_capability_feedback_uses_budget_and_preserves_labels(self):
        from ut_harness import solver_diagnostic
        from rvprobe_skill import journal_repairs
        response = (FIXTURES / 'tiny_intents.ltl').read_text()
        bad = solver_diagnostic({'goals': [dict(label='input_value', status='error',
            failureKind='unsupported_liveness_cover', detail='Not supported: Liveness cover.')]},
            parse_response(response)['labels'])
        ok = {'phase': 'solve', 'ok': True, 'result': {'status': 'generated', 'goals': []}}
        for repaired in (response, 'STOP', response.replace('input_value', 'different_goal')):
            with self.subTest(repaired=repaired), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                replies = [{'usage': {'prompt_tokens': 10, 'completion_tokens': 20, 'total_tokens': 30},
                    'choices': [{'message': {'role': 'assistant', 'content': text}}]} for text in (response, repaired)]
                with patch.object(generation, 'send_completion', side_effect=replies) as send, \
                        patch.object(generation, 'harness', side_effect=[(bad, ''), (ok, '')]) as harness, \
                        contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(generation.main(self.arguments(root)), 0 if repaired == response else 1)
                self.assertEqual(send.call_count, 2)
                self.assertEqual(harness.call_count, 2 if repaired == response else 1)
                prompt = (root / 'run/attempt-2/prompt.txt').read_text()
                self.assertIn('jg_unsupported_liveness_cover', prompt)
                self.assertIn('# DUT specification', prompt)
                summary = json.loads((root / 'run/summary.json').read_text())
                self.assertEqual(summary['costs']['usage_reported']['total_tokens'], 60)
                repair = journal_repairs(root / 'run')['repairs'][0]
                self.assertEqual(repair['status'], 'backend-accepted' if repaired == response else 'changed-unverified')

    def test_empty_generation_never_enters_source_repair_or_compilation(self):
        replies = [{"usage": {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30},
                    "choices": [{"message": message, "finish_reason": reason}]}
                   for message, reason in (({"role": "assistant", "content": ""}, "length"),)]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = self.arguments(root)
            with patch.object(generation, "send_completion", side_effect=replies) as send, \
                 patch.object(generation, "harness") as harness, \
                 contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(generation.main(args), 1)
                self.assertEqual(generation.main([*args, "--resume"]), 1)
            self.assertEqual(send.call_count, 1)
            harness.assert_not_called()
            self.assertFalse((root / "run/attempt-2").exists())
            self.assertFalse((root / "run/attempt-1/harness.json").exists())
            summary = json.loads((root / "run/summary.json").read_text())
            self.assertEqual(summary["status"], "failed")
            self.assertEqual(summary["tokens"], 30)
            self.assertEqual(summary["costs"]["requests"], 1)

    def test_stop_never_calls_compiler_and_changed_resume_does_not_overwrite_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = [*self.arguments(root), "--response-file", str(FIXTURES / "stop_response.ltl")]
            with patch.object(generation, "invoke", side_effect=AssertionError("no model")), \
                 patch.object(generation, "harness", side_effect=AssertionError("no UT")), \
                 contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(generation.main(args), 0)
                original = (root / "run/summary.json").read_bytes()
                self.assertEqual(generation.main([*args, "--resume", "--rag", "off"]), 1)
                self.assertEqual((root / "run/summary.json").read_bytes(), original)
            summary = json.loads(original)
            self.assertEqual(summary["status"], "stopped")
            self.assertEqual(summary["result"]["utCount"], 0)
            self.assertEqual(summary["costs"]["requests"], 0)


class ProcessCleanupTest(unittest.TestCase):
    def test_timeout_also_kills_descendant_in_a_separate_session(self):
        from rvprobe.backend.process import run
        import time
        with tempfile.TemporaryDirectory() as directory:
            pid_file = Path(directory) / "child.pid"
            script = ("import subprocess,sys,time; from pathlib import Path; "
                      "child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)'],start_new_session=True); "
                      f"Path({str(pid_file)!r}).write_text(str(child.pid)); time.sleep(30)")
            with self.assertRaises(subprocess.TimeoutExpired):
                run([sys.executable, "-c", script], timeout=0.5, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            pid = pid_file.read_text()
            stat = Path("/proc") / pid / "stat"
            def state():
                try:
                    return stat.read_text().rsplit(")", 1)[1].split()[0]
                except (FileNotFoundError, ProcessLookupError):
                    return None
            # SIGKILL delivery/reaping is asynchronous, especially under parallel EDA load.
            for _ in range(100):
                if state() in (None, "Z"):
                    break
                time.sleep(0.01)
            self.assertIn(state(), (None, "Z"))
