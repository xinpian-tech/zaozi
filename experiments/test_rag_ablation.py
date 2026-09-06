#!/usr/bin/env python3
"""Paired RAG accounting and orchestration; no provider or EDA calls."""
import contextlib
import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import rag_ablation as ablation

FIXTURES = Path(__file__).resolve().parent / "tests/fixtures"


class AblationTest(unittest.TestCase):
    def setUp(self):
        self.rows = ablation.jobs(2)
        self.manifest = {"contract": ablation.CONTRACT, "generation_contract": ablation.GENERATION_CONTRACT,
                         "replay_contract": ablation.REPLAY_CONTRACT, "corpus_scope": "framework-only",
                         "offline": False, "jobs": copy.deepcopy(self.rows)}

    def summary(self):
        return ablation.summarize(self.manifest, self.rows)

    def test_pending_is_not_zero_measurement(self):
        summary = self.summary()
        self.assertFalse(summary["complete"])
        self.assertEqual(summary["cells"][0]["samples_planned"], 2)
        self.assertIsNone(summary["cells"][0]["reported_tokens"])
        self.assertIsNone(summary["cells"][0]["closed_lines"])

    def test_alternating_order_and_identical_budget(self):
        self.assertEqual([row["mode"] for row in self.rows], ["off", "local", "local", "off"])
        self.assertEqual([row["sample"] for row in self.rows], [1, 1, 2, 2])

    def test_failure_stays_in_denominator_without_inventing_tokens(self):
        self.rows[0]["status"] = "failed"
        cell = self.summary()["cells"][0]
        self.assertEqual(cell["samples_planned"], 2)
        self.assertEqual(cell["samples_finished"], 1)
        self.assertEqual(cell["failures"], 1)
        self.assertEqual(cell["replays"], 0)
        self.assertIsNone(cell["reported_tokens"])

    def test_paired_statistics_only_use_checked_replays(self):
        for row, count in zip(self.rows[:2], (1, 3)):
            row.update(status="replayed", tokens=10, delta={"closed_lines": list(range(count)), "score_gain": count})
        self.assertEqual(self.summary()["paired"], [{"sample": 1, "closed_line_advantage": 2, "score_gain_advantage": 2}])
        self.rows[1]["status"] = "failed"
        self.assertEqual(self.summary()["paired"], [])

    def test_pending_proof_is_not_a_measurement_or_proof(self):
        for row in self.rows:
            row.update(status="proof-required", tokens=0)
        summary = self.summary()
        self.assertTrue(summary["complete"])
        self.assertEqual(summary["cells"][0]["proof_only"], 2)
        self.assertEqual(summary["cells"][0]["replays"], 0)
        self.assertIsNone(summary["cells"][0]["score_gain"])

    def test_saved_response_is_not_model_evaluation(self):
        self.manifest["offline"] = True
        self.assertEqual(self.summary()["evaluation"], "offline-framework-regression")

    def test_old_contracts_and_changed_plan_are_rejected(self):
        for key in ("contract", "generation_contract", "replay_contract", "corpus_scope"):
            with self.subTest(key=key):
                changed = {**self.manifest, key: "old"}
                with self.assertRaises(ValueError):
                    ablation.summarize(changed, self.rows)
        with self.assertRaises(ValueError):
            ablation.summarize(self.manifest, self.rows[:-1])

    def run_flow(self, *, failure=False, proof_only=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "run"
            baseline = {"bins": {"line": [10, 8]}, "uncovered": [[3, "a"], [4, "b"]],
                        "score": 80, "percent": {"line": 80}, "modinfo": "baseline.txt"}
            measured = {**baseline, "bins": {"line": [10, 9]}, "uncovered": [[4, "b"]], "score": 90,
                        "percent": {"line": 90}, "replay": {"passed": True}}
            def generate(args, design, task, row, directory):
                self.assertEqual(task, baseline)
                if failure and row["mode"] == "off":
                    raise ValueError("provider failure")
                return {"tokens": 0, "attempts": 1,
                        "result": {"status": "proof-required" if proof_only else "generated",
                                   "proofObligations": [{"label": "pending", "reason": "requires proof"}] if proof_only else [],
                                   "intents": [] if proof_only else [{"label": "target", "status": "generated", "ms": "3"}]}}
            with patch.object(ablation, "preflight"), patch.object(ablation.Replay, "compile") as compile, \
                 patch.object(ablation.Replay, "simulate", side_effect=[baseline, measured, measured]) as simulate, \
                 patch.object(ablation, "run_generation", side_effect=generate), \
                 patch.object(ablation, "witness_frames", return_value=[]) as frames, \
                 contextlib.redirect_stdout(io.StringIO()):
                code = ablation.main(["--replay-config", str(FIXTURES / "tiny_replay.json"), "--out", str(root),
                    "--samples", "1", "--response-file", str(FIXTURES / "completion_intent.json")])
            self.assertEqual(compile.call_count, 1)
            return code, json.loads((root / "summary.json").read_text()), simulate.call_count, frames.call_count

    def test_both_arms_share_one_build_and_fixed_baseline(self):
        code, summary, simulations, frames = self.run_flow()
        self.assertEqual(code, 0)
        self.assertEqual(simulations, 3)
        self.assertEqual(frames, 2)
        self.assertTrue(summary["complete"])
        self.assertEqual(summary["paired"][0]["score_gain_advantage"], 0)
        self.assertEqual(summary["samples"][0]["candidate_solver_ms"], 3)

    def test_failed_arm_does_not_block_measurement_of_the_other(self):
        code, summary, simulations, frames = self.run_flow(failure=True)
        self.assertEqual(code, 1)
        self.assertEqual(simulations, 2)
        self.assertTrue(summary["complete"])
        self.assertEqual(summary["cells"][0]["failures"], 1)
        self.assertEqual(summary["cells"][1]["replays"], 1)

    def test_proof_only_does_not_replay_or_exclude_residual(self):
        code, summary, simulations, frames = self.run_flow(proof_only=True)
        self.assertEqual(code, 0)
        self.assertEqual(simulations, 1)
        self.assertEqual(frames, 0)
        self.assertEqual(summary["cells"][0]["proof_only"], 1)


if __name__ == "__main__":
    unittest.main()
