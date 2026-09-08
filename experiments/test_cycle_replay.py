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
from cycle_replay import (CONTRACT, WITNESS_CONTRACT, Replay, baseline_frames, check_drive, digest, frame,
                          load_config, read_vcd, render_bench, validate_samples, validate_schedule, witness_frames)
from cycle_diagnostic import compare
import cycle_diagnostic as coverage_flow
import sequence_experiment as loop
import sequence_framework as framework
from test_support import full_ut, append_goals

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

    def test_alu_initialization_has_no_random_inputs_or_requests(self):
        design, config = load_config(framework.ROOT / "experiments/designs/alu_replay.json")
        with patch("cycle_replay.random.Random", side_effect=AssertionError("random must not be used")):
            rows = baseline_frames(design, config)
        self.assertEqual([row["kind"] for row in rows], ["reset", "reset", "baseline"])
        self.assertTrue(all(row["drive"] == config["idle"] for row in rows))

    def test_baseline_mode_and_fields_are_explicit(self):
        for baseline in ({"mode": "reset-only", "idle_cycles": 0},
                         {"mode": "reset-only", "idle_cycles": True},
                         {"mode": "reset-only", "idle_cycles": 1, "samples": 1024},
                         {"mode": "random"}, {"mode": "unknown"}):
            config = copy.deepcopy(self.config)
            config["design"] = str(FIXTURES / "tiny_design.json")
            config["baseline"] = baseline
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "replay.json"
                path.write_text(json.dumps(config))
                with self.subTest(baseline=baseline), self.assertRaises(ValueError):
                    load_config(path)

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
            row = {"stimulusFile": str(stimulus), "witnessFile": str(directory / "witness.vcd"), "cycles": 2,
                   "witnessContract": WITNESS_CONTRACT, "witnessSha256": digest(directory / "witness.vcd")}
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

    def test_replay_rejects_legacy_coi_trace_or_modified_vcd(self):
        with tempfile.TemporaryDirectory() as directory:
            witness = Path(directory) / "witness.vcd"
            witness.write_text(self.vcd())
            row = {"witnessFile": str(witness), "stimulusFile": str(Path(directory) / "stimulus.json"),
                   "witnessSha256": digest(witness)}
            for contract in (None, "jg-coi-v1"):
                row["witnessContract"] = contract
                with self.assertRaisesRegex(ValueError, "full-design witness"):
                    witness_frames(self.design, self.config, row, 0)
            row["witnessContract"] = WITNESS_CONTRACT
            witness.write_text(self.vcd() + "\n")
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                witness_frames(self.design, self.config, row, 0)
            del row["witnessSha256"]
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                witness_frames(self.design, self.config, row, 0)

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
                    "result": {"utCount": 1, "utModule": "FakeUT", "goals": [{"label": "fake", "status": "generated"}],
                               "proofObligations": [{"label": "pending", "reason": "requires proof"}] if proofs else []}}))
            return subprocess.CompletedProcess(command, int(failure and "--modinfo" in command))
        with patch.object(coverage_flow, "run_process", side_effect=run), \
             patch.object(coverage_flow, "preflight"), \
             patch.object(coverage_flow, "expand_goals", side_effect=lambda r, *a: r["result"]["goals"]), \
             patch.object(coverage_flow.Replay, "compile"), \
             patch.object(coverage_flow.Replay, "simulate", side_effect=[baseline, candidate, candidate, candidate, candidate]), \
             patch.object(coverage_flow, "measure_goals", return_value=([{"status": "replayed"}], [{"kind": "witness"}])), \
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
            self.assertEqual(summary["stop_reason"], "no_line_progress")
            self.assertFalse(summary["coverage_closed"])
            self.assertEqual(len(summary["final"]["uncovered"]), 1)
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

    def test_split_output_assignments_reconstruct_bits_outside_goal(self):
        design, config = load_config(FIXTURES / "tiny_replay.json")
        design = design.with_rtl(FIXTURES / "split_output.v")
        root = self.root / "split-output"
        response = {"ut": full_ut("split_bits",
                    "(io.valid & (io.payload === BigInt(1).B(8))).S ### (io.done & io.result(0)).S", design),
                    "proofObligations": []}
        sources = framework.write_sources(root / "sources", design, response)
        report, log = loop.harness(sources, root / "solve", loop.DEFAULT_EDA_SHELL)
        (root / "solve.log").write_text(log)
        self.assertTrue(report["ok"], report)
        frames = witness_frames(design, config, report["result"]["goals"][0], 0)
        # Independently assigned result[1] must be 1, although the goal only uses bit 0.
        match = next(row for row in frames if row["expected"].get("done") == [1, 1])
        self.assertEqual(match["expected"]["result"], [3, 255])
        replay = Replay(design, config, root, loop.DEFAULT_EDA_SHELL)
        replay.compile()
        self.assertTrue(replay.simulate("positive", frames)["replay"]["passed"])
        match["expected"]["result"][0] ^= 2
        with self.assertRaises((ValueError, subprocess.CalledProcessError)):
            replay.simulate("negative-output", frames)
        self.assertIn("output result disagrees with formal pre-edge sample: expected=01 mask=ff actual=03",
                      (root / "negative-output/sim.log").read_text())

    def test_unified_goals_and_independent_reset_replay_then_negative_output_check(self):
        design, config = load_config(FIXTURES / "tiny_replay.json")
        response = framework.parse_response((FIXTURES / "tiny_intents.json").read_text())
        additional = [
            {"label": "property_goal", "expression": "!((!io.valid).S)"},
            {"label": "explicit_history", "expression":
             "(io.valid | !io.valid).S.##(2)((past(io.payload === BigInt(5).B(8), 2) & "
             "past(io.payload === BigInt(7).B(8), 1)).S)"},
            {"label": "history_then_sequence", "expression":
             "(io.valid | !io.valid).S.##(2)(past(io.payload === BigInt(0).B(8), 2).S) ### io.valid.S"},
            {"label": "history_property", "expression":
             "!(!((io.valid | !io.valid).S.##(2)(past(io.payload === BigInt(0).B(8), 2).S)))"},
            {"label": "zero_history", "expression": "(io.valid | !io.valid).S.##(2)(past(io.payload === BigInt(0).B(8), 2).S)"},
            {"label": "after_history", "expression": "io.valid"},
            {"label": "bounded_gap", "expression":
             "(io.valid & (io.payload === BigInt(5).B(8))).S.##(2, Some(4))("
             "(io.valid & (io.payload === BigInt(7).B(8))).S)"},
            {"label": "repetition", "expression": "io.valid.S.*(3)"},
            {"label": "partial_output", "expression":
             "(io.valid & (io.payload === BigInt(165).B(8))).S ### (io.done & io.result(0)).S"},
        ]
        append_goals(response["ut"], [(item["label"], item["expression"]) for item in additional])
        sources = framework.write_sources(self.root / "sources", design, response)
        report, log = loop.harness(sources, self.root / "solve", loop.DEFAULT_EDA_SHELL)
        (self.root / "solve.log").write_text(log)
        self.assertTrue(report["ok"], report)
        rows = {row["label"]: row for row in report["result"]["goals"]}
        # Only result[0] occurs in the goal; full trace reconstruction must still
        # recover result[7:1], rather than trust COI placeholders as expected bits.
        partial = witness_frames(design, config, rows["partial_output"], 99)
        self.assertTrue(any(row["expected"].get("result") == [165, 255]
                            and row["expected"].get("done") == [1, 1] for row in partial), partial)
        for name, depth in (("history", 1), ("explicit_history", 2), ("history_property", 2), ("zero_history", 2)):
            self.assertGreaterEqual(rows[name]["cycles"], depth + 1, rows[name])
        beats = json.loads(Path(rows["explicit_history"]["stimulusFile"]).read_text())
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
        self.assertEqual(rows["after_history"]["cycles"], 1, "independent immediate goal should need only one cycle")
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
        for index, row in enumerate(report["result"]["goals"]):
            frames += witness_frames(design, config, row, index)
        measured = replay.simulate("positive", frames)
        self.assertGreater(measured["replay"]["output_checks"], 0)
        self.assertTrue(measured["replay"]["passed"])
        # Corrupt one known formal expectation. A passing simulation must become a failure.
        broken = copy.deepcopy(frames)
        row = next(row for row in broken if row["segment"] == len(rows) - 1
                   and row["expected"].get("result") == [165, 255])
        # A wrong bit OUTSIDE the goal's COI must still fail checked replay.
        row["expected"]["result"][0] ^= 128
        with self.assertRaises((ValueError, subprocess.CalledProcessError)):
            replay.simulate("negative-output", broken)
        self.assertIn("output result disagrees with formal pre-edge sample: expected=25 mask=ff actual=a5",
                      (self.root / "negative-output/sim.log").read_text())


if __name__ == "__main__":
    unittest.main()
