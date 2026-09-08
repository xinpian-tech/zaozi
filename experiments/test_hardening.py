"""Policy and recovery regressions; no provider credentials or paid requests."""
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
from ut_validation import validate
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
target: assert property (~(valid & payload <= 8'h7));
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
            SV.replace("target: assert", "target: assume"),
            SV.replace("wire done_net;", "`define altered\nwire done_net;"),
            SV.replace("assign done = done_net;", "assign done = done_net;\nalways @(posedge clock) done_net <= valid;"),
            SV.replace("wire done_net;", "wire done_net = valid;"),
            SV.replace("assign done = done_net;", "assign done = done_net;\nalways @(posedge clock) payload[0] <= valid;"),
            SV.replace(".valid(valid)", ".valid(payload)"),
            SV.replace("target:", "extra: assert property(valid);\ntarget:")]
        for text in variants:
            with self.subTest(text=text), self.assertRaises(ValueError):
                validate(text, self.design, "TestUT", ["target"])


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
            with patch.object(generation, "invoke", side_effect=[TimeoutError(), ("response", info)]) as invoke, \
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
        self.assertEqual(parse_response(json.dumps(data)), data)
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
    def arguments(self, root):
        divider = "=" * 72
        modinfo = root / "modinfo.txt"
        modinfo.write_text(f"{divider}\nModule : tiny_external\n{divider}\n  10 0/1 result <= payload;\n")
        return ["--design", str(FIXTURES / "tiny_design.json"), "--modinfo", str(modinfo),
                "--out", str(root / "run"), "--attempts", "2"]

    def test_resume_reuses_response_after_infrastructure_failure_without_another_request(self):
        response = (FIXTURES / "tiny_intents.json").read_text()
        info = {"usage": {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30}}
        ok = {"phase": "solve", "ok": True, "result": {"status": "partial", "utCount": 1,
              "goals": [{"label": "good", "status": "generated"}, {"label": "bad", "status": "unknown"}]}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = self.arguments(root)
            with patch.object(generation, "invoke", return_value=(response, info)) as invoke, \
                 patch.object(generation, "harness", side_effect=[({"phase": "toolchain", "ok": False, "detail": "transient infrastructure"}, ""), (ok, "")]) as harness, \
                 contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(generation.main(args), 1)
                self.assertEqual(generation.main([*args, "--resume"]), 0)
                self.assertEqual(invoke.call_count, 1)
                self.assertEqual(harness.call_count, 2)
            summary = json.loads((root / "run/summary.json").read_text())
            self.assertEqual(summary["status"], "partial")
            self.assertEqual(summary["tokens"], 30)
            self.assertEqual(summary["costs"]["requests"], 1)
            self.assertEqual(summary["costs"]["phases"]["generation-session"]["count"], 2)

    def test_stop_never_calls_compiler_and_changed_resume_does_not_overwrite_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = [*self.arguments(root), "--response-file", str(FIXTURES / "stop_response.json")]
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
        from process_runner import run
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
