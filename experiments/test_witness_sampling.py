import tempfile
import unittest
import argparse
from types import SimpleNamespace
from unittest.mock import patch
from pathlib import Path

from witness_sampling import (expand_goals, import_sample, render_sampling, select_cover, tcl_word,
                              validate_sample_config, sampling_options, sampling_policy)
from sequence_framework import ROOT, load_design
from sequence_experiment import residual, build_prompt


class SamplingTests(unittest.TestCase):
    def test_partial_line_coverage_is_not_mistaken_for_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.txt"
            path.write_text("=" * 75 + "\nModule : tiny\n" + "=" * 75 +
                            "\n17 1/2 ==> if (valid) result <= payload;\n18 1/1 done <= valid;\n19 0/1 missing;\n")
            self.assertEqual(residual(path, "tiny"), [(17, "if (valid) result <= payload;"), (19, "missing;")])

    def test_prompt_describes_four_samples_not_four_gen_calls(self):
        design = load_design(ROOT / "experiments/tests/fixtures/tiny_design.json")
        prompt = build_prompt([], design.sources[0], "1s", design=design, sequences_per_intent=4)
        self.assertIn("up to 4 distinct sequences per Gen", prompt)
        self.assertIn("do not duplicate Gen calls", prompt)

    def test_default_budget_is_four_and_invalid_budgets_are_rejected(self):
        parser = argparse.ArgumentParser()
        sampling_options(parser)
        args = parser.parse_args([])
        self.assertEqual(sampling_policy(args)["sequences_per_intent"], 4)
        for budget in (0, -1, 257):
            args.sequences_per_intent = budget
            with self.assertRaises(ValueError):
                sampling_policy(args)

    def test_sampling_failure_keeps_original_and_does_not_discard_other_intents(self):
        goals = [{"label": "bad", "status": "generated"}, {"label": "good", "status": "generated"},
                 {"label": "impossible", "status": "infeasible"}]
        args = SimpleNamespace(sequences_per_intent=4, sampling_seed=1, sampling_time_limit="1s",
                               eda_shell=Path("/tmp/shell"), resume=False)
        with tempfile.TemporaryDirectory() as directory, \
             patch("witness_sampling.frozen_inputs", return_value=(None, {}, {}, goals)), \
             patch("witness_sampling.sample_goal", side_effect=[ValueError("no export"), [goals[1]] * 4]):
            rows = expand_goals({"sources": "/tmp/sources", "result": {"goals": goals}},
                                Path("/tmp/config"), Path(directory), args)
            self.assertEqual(rows[0]["sequences"], [goals[0]])
            self.assertEqual(rows[0]["sampling_status"], "failed")
            self.assertEqual(rows[1]["sampling_status"], "complete")
            self.assertEqual(rows[2]["sequences"], [])

    def test_insufficient_samples_are_not_padded(self):
        goal = {"label": "fixed", "status": "generated"}
        args = SimpleNamespace(sequences_per_intent=4, sampling_seed=1, sampling_time_limit="1s",
                               eda_shell=Path("/tmp/shell"), resume=False)
        with tempfile.TemporaryDirectory() as directory, \
             patch("witness_sampling.frozen_inputs", return_value=(None, {}, {}, [goal])), \
             patch("witness_sampling.sample_goal", return_value=[goal]):
            rows = expand_goals({"sources": "/tmp/sources", "result": {"goals": [goal]}},
                                Path("/tmp/config"), Path(directory), args)
            self.assertEqual(rows[0]["sampling_status"], "partial")
            self.assertEqual(len(rows[0]["sequences"]), 1)

    def test_preserves_selected_goal_and_removes_other_goals(self):
        sv = "module m;\ng: assert property (not ((a) ##2 (done)));\nh:\n assert property (~start);\nendmodule"
        selected = select_cover(sv, ["g", "h"], "g")
        self.assertIn("g: cover property (not (not ((a) ##2 (done))));", selected)
        self.assertNotIn("h:", selected)
        self.assertNotIn("assume", selected)
        self.assertNotIn("assert property", selected)

    def test_missing_duplicate_or_extra_labels_fail_closed(self):
        for sv in ("g: assert property (a);", "g: assert property (a);\ng: assert property (b);",
                   "g: assert property (a);\nh: assert property (b);\nx: assert property (c);"):
            with self.assertRaises(ValueError):
                select_cover(sv, ["g", "h"], "g")

    def script(self, count=4, seed=11):
        return render_sampling({"rtl": ["/tmp/d.v"], "include": None, "top": "m"}, "g",
            Path("/tmp/m.sv"), Path("/tmp/samples"), [{"name": "a", "width": 8}], 3, count, seed, "2s")

    def test_deterministic_soft_preferences_and_nested_prefixes(self):
        small, large = self.script(), self.script(16)
        self.assertEqual(small, self.script())
        self.assertNotEqual(small, self.script(seed=12))
        soft = lambda s: [x for x in s.splitlines() if x.startswith("visualize -force -soft")]
        self.assertEqual(soft(small), soft(large)[:len(soft(small))])
        self.assertNotIn("assume", large)
        self.assertIn("visualize -min_length 3", large)
        self.assertIn("visualize -max_length 3", large)
        self.assertIn("set_trace_optimization standard", large)
        self.assertNotIn("-clear_conf", large)
        self.assertIn('if {$status != "covered"}', large)
        self.assertIn("dict exists $seen $signature", large)

    def test_one_sample_needs_no_new_preferences(self):
        self.assertNotIn("visualize -force -soft", self.script(1))

    def test_reject_tcl_injection(self):
        for word in ("a}\nexit", "a\\b", "a\rb"):
            with self.assertRaises(ValueError):
                tcl_word(word)
        self.assertEqual(tcl_word("/tmp/space path"), "{/tmp/space path}")

    def test_saved_configuration_keeps_cover_and_only_soft_preferences(self):
        valid = "\n".join(["proc visualize_save {} {", "visualize -new_window", "task -set <embedded>",
            "visualize -set_target -cover -property {<embedded>::m.g}",
            "visualize -min_length 3", "visualize -max_length 3",
            "visualize -force -soft {a == 8'hfe} 1:1 -name rvprobe_soft_0", "visualize -replot", "}", "visualize_save"])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "conf.tcl"
            path.write_text(valid)
            validate_sample_config(path, "m", "g", 3)
            for bad in (valid.replace("m.g", "m.h"), valid.replace("-force -soft", "-force"),
                        valid + "\nassume {a == 0}", valid.replace("-max_length 3", "-max_length 4")):
                path.write_text(bad)
                with self.assertRaises(ValueError):
                    validate_sample_config(path, "m", "g", 3)

    def test_jg_omits_default_minimum_for_single_cycle_config(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "conf.tcl"
            path.write_text("visualize -set_target -cover -property {<embedded>::m.g}\nvisualize -max_length 1\n")
            validate_sample_config(path, "m", "g", 1)
            path.write_text(path.read_text().replace("max_length 1", "max_length 2"))
            with self.assertRaises(ValueError):
                validate_sample_config(path, "m", "g", 2)

    def test_import_requires_known_inputs_and_fingerprints_input_only(self):
        design = load_design(ROOT / "experiments/tests/fixtures/tiny_design.json")
        vcd = """$scope module top $end
$var wire 1 ! clock $end
$var wire 8 a payload $end
$var wire 1 v valid $end
$var wire 8 y result $end
$upscope $end
$enddefinitions $end
#0
0!
b00010101 a
1v
b00000000 y
#1
1!
"""
        with tempfile.TemporaryDirectory() as directory:
            first = Path(directory) / "one.vcd"
            second = Path(directory) / "two.vcd"
            first.write_text(vcd)
            second.write_text(vcd.replace("b00000000 y", "b11111111 y"))
            a, b = import_sample(first, "g", design), import_sample(second, "g", design)
            self.assertEqual(a["cycles"], 1)
            self.assertEqual(a["inputFingerprint"], b["inputFingerprint"])
            self.assertNotEqual(a["witnessSha256"], b["witnessSha256"])
            second.write_text(vcd.replace("b00010101 a", "b000x0101 a"))
            with self.assertRaises(ValueError):
                import_sample(second, "g", design)


if __name__ == "__main__":
    unittest.main()
