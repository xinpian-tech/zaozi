#!/usr/bin/env python3
"""Summary accounting regressions; no provider or EDA tools required."""
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import alu_rag_ablation as ablation


class AblationSummaryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="alu-rag-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.sample = self.root / "round1-off-1"
        self.sample.mkdir()
        ablation.save(self.root / "manifest.json", {
            "model": "test", "temperature": 0.3, "limitation": "fixed residuals",
            "corpus_scope": "framework-only",
            "jobs": [{"name": self.sample.name, "round": 1, "mode": "off", "sample": 1}],
        })
        ablation.save(self.sample / "residual.json", [[10, "a"], [20, "b"], [30, "c"]])

    def summary(self):
        with contextlib.redirect_stdout(io.StringIO()):
            ablation.summarize(SimpleNamespace(out=self.root))
        return json.loads((self.root / "summary.json").read_text())

    def generation(self, ok=True):
        ablation.save(self.sample / "generation.json", {
            "ok": ok, "seconds": 2, **({"tokens": 100} if ok else {}),
        })

    def harness(self, ok=True, proof_only=False):
        ablation.save(self.sample / "harness.json", {
            "ok": ok, "result": {
                "status": "proof-required" if proof_only else "generated",
                "intents": [] if proof_only else [{"ms": "123"}, {"ms": "456"}],
                "proofObligations": [{"label": "c", "reason": "test"}] if proof_only else [],
            } if ok else {},
        })

    def replay(self):
        ablation.save(self.root / "coverage.json", [
            {"name": "baseline-round1", "score": 90, "uncovered_lines": [10, 30]},
            {"name": self.sample.name, "score": 92, "uncovered_lines": [30], "percent": {}},
        ])

    def test_unreturned_sample_is_pending_not_a_zero_measurement(self):
        summary = self.summary()
        cell = summary["cells"][0]
        self.assertFalse(summary["complete"])
        self.assertEqual(cell["samples_planned"], 1)
        self.assertEqual(cell["samples_returned"], 0)
        self.assertIsNone(cell["tokens"])

    def test_provider_failure_remains_in_sample_denominator(self):
        self.generation(ok=False)
        summary = self.summary()
        self.assertTrue(summary["complete"])
        self.assertEqual(summary["cells"][0]["samples_returned"], 1)
        self.assertEqual(summary["cells"][0]["generation_passes"], 0)
        self.assertIsNone(summary["cells"][0]["tokens"])

    def test_generated_and_solved_are_not_replay_completion(self):
        self.generation()
        self.assertFalse(self.summary()["complete"])
        self.harness()
        self.assertFalse(self.summary()["complete"])

    def test_compile_failure_is_terminal_but_not_a_success(self):
        self.generation()
        self.harness(ok=False)
        summary = self.summary()
        self.assertTrue(summary["complete"])
        self.assertEqual(summary["cells"][0]["harness_passes"], 0)
        self.assertEqual(summary["cells"][0]["replays"], 0)

    def test_only_newly_closed_baseline_targets_are_attributed(self):
        self.generation()
        self.harness()
        self.replay()
        summary = self.summary()
        row = summary["samples"][0]
        self.assertTrue(summary["complete"])
        self.assertEqual(row["candidate_solver_ms"], 579)
        self.assertEqual(row["closed_target_lines"], [10])
        self.assertEqual(row["remaining_target_lines"], [30])
        self.assertEqual(row["targets_already_covered_in_baseline"], [20])
        self.assertEqual(summary["cells"][0]["closed_target_count"]["mean"], 1)

    def test_proof_only_does_not_mean_proven(self):
        self.generation()
        self.harness(proof_only=True)
        self.replay()
        cell = self.summary()["cells"][0]
        self.assertEqual(cell["proof_only_samples"], 1)
        self.assertEqual(cell["candidate_solver_ms"]["mean"], 0)
        self.assertNotIn("proven", cell)

    def test_old_run_is_not_relabelled_as_framework_rag(self):
        manifest = json.loads((self.root / "manifest.json").read_text())
        del manifest["corpus_scope"]
        ablation.save(self.root / "manifest.json", manifest)
        self.assertEqual(self.summary()["evaluation_status"], "invalid-for-framework-rag")


if __name__ == "__main__":
    unittest.main()
