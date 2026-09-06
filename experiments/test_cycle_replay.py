#!/usr/bin/env python3
"""Cycle transport and feedback regressions; licensed integration is opt-in."""
import copy
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cycle_replay import (CONTRACT, Replay, baseline_frames, check_drive, frame,
                          load_config, read_vcd, render_bench, validate_samples, validate_schedule, witness_frames)
from coverage_flow import compare
import coverage_flow
import sequence_experiment as loop
import sequence_framework as framework

FIXTURES = Path(__file__).resolve().parent / "tests/fixtures"


class CycleReplayTest(unittest.TestCase):
    def setUp(self):
        self.design, self.config = load_config(FIXTURES / "tiny_replay.json")

    def test_baseline_is_seeded_and_declares_protocol_not_answers(self):
        first = baseline_frames(self.design, self.config)
        self.assertEqual(first, baseline_frames(self.design, self.config))
        self.assertEqual(len(first), 6)
        self.assertEqual([row["kind"] for row in first[:2]], ["reset", "reset"])
        self.assertEqual([row["drive"]["valid"] for row in first[2:]], [1, 0, 1, 0])
        self.assertEqual(first[2]["drive"]["payload"], first[3]["drive"]["payload"])
        changed = copy.deepcopy(self.config)
        changed["baseline"]["seed"] += 1
        self.assertNotEqual(first, baseline_frames(self.design, changed))

    def test_drive_fields_are_exact_unsigned_bit_patterns(self):
        for bad in ({"payload": -1, "valid": 0}, {"payload": 256, "valid": 0},
                    {"payload": 1}, {"payload": 1, "valid": True},
                    {"payload": 1, "valid": 0, "extra": 0}):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                check_drive(self.design, bad)

    def test_rendered_bench_owns_cycles_and_never_waits_for_handshake(self):
        text = render_bench(self.design)
        self.assertIn("@(negedge vif.clk)", text)
        self.assertIn("clocking rvprobe_sample @(posedge clk)", text)
        self.assertIn("default input #1step", text)
        self.assertIn("@(vif.rvprobe_sample)", text)
        self.assertIn("txn.mask_done", text)
        self.assertNotIn("while (vif.done", text)
        self.assertIn("tiny_external dut", text)
        self.assertNotRegex(text, r"\balu\b")

    def vcd(self):
        return '''$scope module RunIntent0 $end
$var wire 1 ! clock $end
$var wire 1 r reset $end
$var wire 8 p payload $end
$var wire 1 v valid $end
$scope module dut $end
$var wire 8 o result $end
$var wire 1 d done $end
$upscope $end
$upscope $end
$enddefinitions $end
#0
0r
b00101010 p
1v
bxxxx0011 o
0d
1!
#5
0!
#10
b00000111 p
0v
b00101010 o
1d
1!
'''

    def test_vcd_preserves_unknown_masks_and_samples_pre_edge(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "witness.vcd"
            path.write_text(self.vcd())
            rows = read_vcd(path)
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0]["dut/result"], (3, 15))
        self.assertEqual(rows[1]["dut/result"], (42, 255))
        self.assertEqual(rows[0]["payload"], (42, 255))

    def test_independent_reset_exact_witness_and_explicit_drain(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            (directory / "witness.vcd").write_text(self.vcd())
            stimulus = directory / "stimulus.json"
            stimulus.write_text(json.dumps([{"payload": "42", "valid": "1"}, {"payload": "7", "valid": "0"}]))
            row = {"stimulusFile": str(stimulus), "witnessFile": str(directory / "witness.vcd"), "cycles": 2}
            frames = witness_frames(self.design, self.config, row, 1)
            self.assertEqual([r["kind"] for r in frames], ["reset", "reset", "witness", "witness", "drain", "drain"])
            self.assertEqual(frames[2]["expected"]["result"], [3, 15])
            self.assertEqual(frames[3]["drive"], {"payload": 7, "valid": 0})
            self.assertEqual(frames[4]["expected"], {})
            validate_schedule(self.design, frames, self.config["reset_cycles"])
            with self.assertRaisesRegex(ValueError, "reset preamble"):
                validate_schedule(self.design, frames[2:], self.config["reset_cycles"])
            row["cycles"] = 3
            with self.assertRaisesRegex(ValueError, "cycle counts"):
                witness_frames(self.design, self.config, row, 2)
            row["cycles"] = 2
            stimulus.write_text(json.dumps([{"payload": 99, "valid": 1}, {"payload": 7, "valid": 0}]))
            with self.assertRaisesRegex(ValueError, "disagrees with witness"):
                witness_frames(self.design, self.config, row, 2)

    def sample_log(self, frames):
        lines = ["UVM_ERROR : 0", "UVM_FATAL : 0", f"RVPROBE_REPLAY_PASS {len(frames)}"]
        for index, row in enumerate(frames):
            # tiny reset is active high.
            values = " ".join(format(row["drive"][name], "x") for name in self.design.drive_names)
            lines.append(f"RVPROBE_SAMPLE {index} {15000 + 10000 * index} {int(row['kind'] == 'reset')} {values} 0 0")
        return "\n".join(lines)

    def test_sample_validation_detects_timing_reset_data_and_completion_errors(self):
        frames = baseline_frames(self.design, self.config)
        log = self.sample_log(frames)
        self.assertTrue(validate_samples(self.design, frames, log)["passed"])
        bad = [log.replace("25000", "35000"), log.replace("0 15000 1", "0 15000 0"),
               log.replace("0 15000 1 0 0", "0 15000 1 1 0"),
               log.replace("RVPROBE_REPLAY_PASS", "MISSING"), log.replace("UVM_FATAL : 0", "UVM_FATAL : 1")]
        for changed in bad:
            with self.subTest(log=changed), self.assertRaises(ValueError):
                validate_samples(self.design, frames, changed)

    def test_output_unknown_bits_are_masked_not_treated_as_zero_evidence(self):
        frames = [frame("baseline", {"payload": 0, "valid": 0}, expected={"result": [3, 15]})]
        log = self.sample_log(frames).replace("15000 0 0 0 0 0", "15000 0 0 0 xxxx0011 0")
        self.assertTrue(validate_samples(self.design, frames, log)["passed"])
        with self.assertRaisesRegex(ValueError, "output disagrees"):
            validate_samples(self.design, frames, log.replace("xxxx0011", "xxxx00x1"))

    def test_feedback_uses_measured_residual_and_rejects_regression(self):
        before = {"bins": {"line": [10, 8]}, "uncovered": [[3, "x"], [4, "y"]], "score": 80}
        after = {"bins": {"line": [10, 9]}, "uncovered": [[4, "y"]], "score": 90}
        self.assertEqual(compare(before, after)["closed_lines"], [3])
        with self.assertRaises(ValueError):
            compare(after, before)
        prompt = loop.build_prompt([(4, "y")], self.design.sources[0], "5s", design=self.design,
                                   coverage_feedback={"closed_lines": [3], "remaining_lines": [4]})
        self.assertIn("Current run coverage feedback", prompt)
        self.assertIn('"closed_lines"', prompt)
        self.assertIn("not retrieved examples or proof results", prompt)


class CoverageFlowTest(unittest.TestCase):
    def fake_run(self, directory, *, proofs=False, failure=False):
        baseline = {"bins": {"line": [10, 8]}, "uncovered": [[3, "a"], [4, "b"]],
                    "score": 80, "percent": {"line": 80}, "modinfo": "baseline.txt"}
        candidate = {"bins": {"line": [10, 9]}, "uncovered": [[4, "b"]],
                     "score": 90, "percent": {"line": 90}, "modinfo": "candidate.txt", "replay": {"passed": True}}
        calls = []
        def run(command, **kwargs):
            if "--modinfo" in command:
                calls.append(command)
                out = Path(command[command.index("--out") + 1])
                out.mkdir(parents=True)
                (out / "summary.json").write_text(json.dumps({
                    "status": "failed" if failure else "generated", "tokens": 0, "attempts": 1,
                    "result": {"intents": [] if proofs else [{"label": "fake", "status": "generated"}],
                               "proofObligations": [{"label": "pending", "reason": "requires proof"}] if proofs else []}}))
            return subprocess.CompletedProcess(command, int(failure and "--modinfo" in command))
        with patch.object(coverage_flow.subprocess, "run", side_effect=run), \
             patch.object(coverage_flow.Replay, "compile"), \
             patch.object(coverage_flow.Replay, "simulate", side_effect=[baseline, candidate, candidate]), \
             patch.object(coverage_flow, "witness_frames", return_value=[]), \
             contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = coverage_flow.main(["--replay-config", str(FIXTURES / "tiny_replay.json"),
                "--out", str(directory / "flow"), "--rounds", "3",
                "--response-file", str(FIXTURES / "completion_intent.json"),
                "--response-file", str(FIXTURES / "completion_intent.json")])
        return code, json.loads((directory / "flow/summary.json").read_text()), calls

    def test_next_round_uses_new_measurement_then_stops_without_progress(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            code, summary, calls = self.fake_run(directory)
            self.assertEqual(code, 0)
            self.assertEqual(summary["stop_reason"], "no_line_progress")
            self.assertFalse(summary["coverage_closed"])
            self.assertEqual(len(calls), 2)
            self.assertEqual(calls[1][calls[1].index("--modinfo") + 1], "candidate.txt")
            feedback = json.loads((directory / "flow/round-2/feedback.json").read_text())
            self.assertEqual(feedback["remaining"], [[4, "b"]])
            self.assertEqual(feedback["previous_round"]["closed_lines"], [3])

    def test_pending_proof_never_removes_residual_or_becomes_coverage_success(self):
        with tempfile.TemporaryDirectory() as directory:
            code, summary, calls = self.fake_run(Path(directory), proofs=True)
            self.assertEqual(code, 0)
            self.assertEqual(summary["stop_reason"], "proof_required")
            self.assertFalse(summary["coverage_closed"])
            self.assertEqual(len(summary["final"]["uncovered"]), 2)
            self.assertEqual(summary["pending_proofs"][0]["label"], "pending")

    def test_failed_generation_is_not_a_completed_experiment(self):
        with tempfile.TemporaryDirectory() as directory:
            code, summary, calls = self.fake_run(Path(directory), failure=True)
            self.assertEqual(code, 1)
            self.assertEqual(summary["status"], "failed")
            self.assertEqual(summary["phase"], "round-1/generation")


@unittest.skipUnless(os.environ.get("RVPROBE_RUN_REPLAY_TESTS") == "1", "set RVPROBE_RUN_REPLAY_TESTS=1 for VCS/JG")
class LicensedReplayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        out = framework.ROOT / "out/experiments"
        out.mkdir(parents=True, exist_ok=True)
        cls.root = Path(tempfile.mkdtemp(prefix="cycle-replay-regression-", dir=out))

    def test_unified_goals_and_independent_reset_replay_then_negative_output_check(self):
        design, config = load_config(FIXTURES / "tiny_replay.json")
        response = framework.parse_response((FIXTURES / "tiny_intents.json").read_text())
        response["intents"] += [
            {"label": "property_goal", "expression": "!((!io.valid).S)"},
            {"label": "guarded_history", "expression":
             "(Gen.past(io.payload, 8, 2) === BigInt(5).B(8)) & "
             "(Gen.past(io.payload, 8, 1) === BigInt(7).B(8))"},
            {"label": "history_then_sequence", "expression":
             "(Gen.past(io.payload, 8, 2) === BigInt(0).B(8)).S ### io.valid.S"},
            {"label": "history_property", "expression":
             "!(!(Gen.past(io.payload, 8, 2) === BigInt(0).B(8)).S)"},
            {"label": "zero_history", "expression": "Gen.past(io.payload, 8, 2) === BigInt(0).B(8)"},
            {"label": "after_history", "expression": "io.valid"},
            {"label": "bounded_gap", "expression":
             "(io.valid & (io.payload === BigInt(5).B(8))).S.##(2, Some(4))("
             "(io.valid & (io.payload === BigInt(7).B(8))).S)"},
            {"label": "repetition", "expression": "io.valid.S.*(3)"},
        ]
        sources = framework.write_sources(self.root / "sources", design, response)
        report, log = loop.harness(sources, self.root / "solve", loop.DEFAULT_EDA_SHELL)
        (self.root / "solve.log").write_text(log)
        self.assertTrue(report["ok"], report)
        rows = {row["label"]: row for row in report["result"]["intents"]}
        for name, depth in (("history", 1), ("guarded_history", 2), ("history_property", 2), ("zero_history", 2)):
            self.assertGreaterEqual(rows[name]["cycles"], depth + 1, rows[name])
        beats = json.loads(Path(rows["guarded_history"]["stimulusFile"]).read_text())
        self.assertTrue(any(int(beats[t - 2]["payload"]) == 5 and int(beats[t - 1]["payload"]) == 7
                            for t in range(2, len(beats))), beats)
        for name in ("history_property", "zero_history"):
            beats = json.loads(Path(rows[name]["stimulusFile"]).read_text())
            self.assertTrue(any(int(beats[t - 2]["payload"]) == 0 for t in range(2, len(beats))), beats)
        beats = json.loads(Path(rows["history_then_sequence"]["stimulusFile"]).read_text())
        self.assertGreaterEqual(len(beats), 4)
        # JG may append a diagnostic cycle after a temporal match. Check existence
        # of a real start/match on the trace, not an assumed offset from its tail.
        self.assertTrue(any(int(beats[t - 2]["payload"]) == 0 and int(beats[t + 1]["valid"]) == 1
                            for t in range(2, len(beats) - 1)), beats)
        self.assertEqual(rows["after_history"]["cycles"], 1, "history guard leaked to another goal")
        beats = json.loads(Path(rows["bounded_gap"]["stimulusFile"]).read_text())
        self.assertTrue(any(int(beats[t]["valid"]) == 1 and int(beats[t]["payload"]) == 5
                            and int(beats[t + gap]["valid"]) == 1 and int(beats[t + gap]["payload"]) == 7
                            for gap in range(2, 5) for t in range(len(beats) - gap)), beats)
        beats = json.loads(Path(rows["repetition"]["stimulusFile"]).read_text())
        self.assertTrue(any(all(int(beats[t + offset]["valid"]) == 1 for offset in range(3))
                            for t in range(len(beats) - 2)), beats)
        replay = Replay(design, config, self.root, loop.DEFAULT_EDA_SHELL)
        replay.compile()
        frames = baseline_frames(design, config)
        for index, row in enumerate(report["result"]["intents"]):
            frames += witness_frames(design, config, row, index)
        measured = replay.simulate("positive", frames)
        self.assertGreater(measured["replay"]["output_checks"], 0)
        self.assertTrue(measured["replay"]["passed"])
        # Corrupt one known formal expectation. A passing simulation must become a failure.
        broken = copy.deepcopy(frames)
        row = next(row for row in broken if row["expected"])
        value = next(iter(row["expected"].values()))
        value[0] ^= value[1] & -value[1]
        with self.assertRaises((ValueError, subprocess.CalledProcessError)):
            replay.simulate("negative-output", broken)


if __name__ == "__main__":
    unittest.main()
