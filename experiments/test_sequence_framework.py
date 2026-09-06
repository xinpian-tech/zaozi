#!/usr/bin/env python3
"""Runtime code generation regressions. Tool tests are opt-in; no provider requests."""
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sequence_framework as framework
import sequence_experiment as loop

FIXTURES = Path(__file__).resolve().parent / "tests/fixtures"


class SequenceFrameworkTest(unittest.TestCase):
    def setUp(self):
        self.design = framework.load_design(FIXTURES / "tiny_design.json")
        self.response = framework.parse_response((FIXTURES / "tiny_intents.json").read_text())

    def changed_manifest(self, change):
        raw = json.loads((FIXTURES / "tiny_design.json").read_text())
        raw["sources"] = [str(self.design.sources[0])]
        change(raw)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "design.json"
            path.write_text(json.dumps(raw))
            return framework.load_design(path)

    def test_one_binding_and_four_new_ut_bodies(self):
        binding = framework.render_binding(self.design)
        program = framework.render_program(self.design, self.response)
        self.assertEqual(binding.count("extends VerilogWrapper["), 1)
        self.assertNotIn("extends VerilogWrapper[", program)
        self.assertEqual(program.count("extends Generator["), 4)
        self.assertEqual(program.count("val dut = ImportedDut.instantiate"), 4)
        self.assertEqual(program.count("Gen(("), 4)
        self.assertNotIn("Sem.", program)
        self.assertNotIn("Generate((", program)
        self.assertNotIn("Txn.", program)
        self.assertNotIn("UvmSequence.concat", program)
        for intent in self.response["intents"]:
            self.assertIn(intent["expression"], program)
        self.assertFalse(loop.backend_errors(binding + program))

    def test_binding_contains_only_interface_not_rtl_behavior(self):
        binding = framework.render_binding(self.design)
        self.assertIn('"tiny_external"', binding)
        self.assertIn("val `payload` = Flipped(Bits(8))", binding)
        self.assertNotIn("def architecture", binding)

    def test_no_legacy_cli_or_build_module(self):
        for path in ("utlib/src/Sem.scala", "utlib/src/Txn.scala", "experiments/alu_residual_loop.py",
                     "experiments/alu_rag_ablation.py"):
            self.assertFalse((framework.ROOT / path).exists(), path)
        self.assertFalse(list((framework.ROOT / "experiments/legacy").rglob("*.scala")))
        mill = (framework.ROOT / "experiments/package.mill").read_text()
        self.assertNotIn("object legacy", mill)
        self.assertNotIn("stdlib", mill.split("import build.")[1].split("\n")[0])
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(framework.ROOT / "experiments/ut_harness.py"),
                "unused.scala", "--out", directory, "--legacy"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertIn("unrecognized arguments: --legacy", result.stderr)

    def test_explicit_reset_polarity(self):
        program = framework.render_program(self.design, self.response)
        self.assertIn("dut.io.`rst` := io.reset.asBool", program)
        low = self.changed_manifest(lambda raw: raw["reset"].update(active_low=True))
        self.assertIn("dut.io.`rst` := !io.reset.asBool", framework.render_program(low, self.response))

    def test_invalid_interface_is_rejected(self):
        changes = [lambda r: r["ports"].append(r["ports"][0]),
                   lambda r: r["ports"][2].update(direction="inout"),
                   lambda r: r["ports"][2].update(width=0),
                   lambda r: r["ports"][2].update(width=True),
                   lambda r: r["ports"][2].update(name="clock"),
                   lambda r: r["ports"][2].update(kind="clock", width=1),
                   lambda r: r["reset"].update(active_low="false"),
                   lambda r: r.update(clock="missing")]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.changed_manifest(change)

    def test_parameters_are_binding_metadata_not_a_new_design(self):
        design = self.changed_manifest(lambda raw: raw.update(parameters={"WIDTH": 8}))
        self.assertIn('`WIDTH`: BigInt = BigInt("8")', framework.render_binding(design))
        with self.assertRaisesRegex(ValueError, "parameter overrides"):
            framework.check_interface(design, Path("unused"))

    def test_duplicate_and_path_labels_are_rejected(self):
        for label in ("../escape", "same/slash", "a-b", "input_value"):
            raw = copy.deepcopy(self.response)
            raw["intents"][1]["label"] = label
            with self.subTest(label=label), self.assertRaises(ValueError):
                framework.parse_response(json.dumps(raw))

    def test_only_json_envelope_is_accepted(self):
        for raw in ('object Generated extends UTExperiment', '{}',
                    '{"intents": [], "proofObligations": [], "runner": "override"}'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                framework.parse_response(raw)

    def test_empty_and_proof_only_are_not_fabricated_witnesses(self):
        for proofs in ([], [{"label": "pending", "reason": "requires a separate check"}]):
            program = framework.render_program(self.design, {"intents": [], "proofObligations": proofs})
            self.assertNotIn("JasperGold.generate(", program)
            self.assertNotIn("extends Generator[", program)
            self.assertIn('"proof-required"', program)
            self.assertIn('"no-candidates"', program)

    def test_rtl_override_affects_both_prompt_and_solver(self):
        with tempfile.TemporaryDirectory() as directory:
            rtl = Path(directory) / "replacement.v"
            rtl.write_text(self.design.sources[0].read_text() + "\n// unique_override\n")
            design = self.design.with_rtl(rtl)
            line = len(rtl.read_text().splitlines())
            prompt = loop.build_prompt([(line, "unique_override")], rtl, "5s", design=design)
            program = framework.render_program(design, self.response)
            self.assertIn("unique_override", prompt)
            self.assertIn(str(rtl), program)
            self.assertNotIn(str(self.design.sources[0]), program)

    def test_sources_and_input_hashes_are_run_local(self):
        with tempfile.TemporaryDirectory() as directory:
            target = framework.write_sources(Path(directory), self.design, self.response)
            self.assertEqual({p.name for p in target.iterdir()},
                             {"DesignBinding.scala", "Generated.scala", "intent.json", "design.json"})
            record = json.loads((target / "design.json").read_text())
            self.assertEqual(len(record["sources"][0]["sha256"]), 64)
        self.assertFalse((framework.ROOT / "experiments/src/Generated.scala").exists())

    def test_circt_import_checks_manifest_against_elaborated_ports(self):
        header = "hw.module @tiny_external(" + ", ".join(
            f"{'in %' if p.direction == 'input' else 'out '}{p.name}: i{p.width}"
            for p in self.design.ports) + ") { }"
        with tempfile.TemporaryDirectory() as directory, patch.object(framework.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 0, header, "")
            framework.check_interface(self.design, Path(directory))
            self.assertIn("--top=tiny_external", run.call_args.args[0])
            run.return_value.stdout = header.replace("payload: i8", "payload: i16")
            with self.assertRaisesRegex(ValueError, "disagrees"):
                framework.check_interface(self.design, Path(directory))

    def test_changed_rtl_is_rejected_before_compiling(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            rtl = directory / "dut.v"
            rtl.write_text(self.design.sources[0].read_text())
            sources = framework.write_sources(directory / "sources", self.design.with_rtl(rtl), self.response)
            rtl.write_text(rtl.read_text() + "\n// changed\n")
            command = [sys.executable, str(framework.ROOT / "experiments/ut_harness.py"),
                       str(sources), "--out", str(directory / "solve"), "--compile-only"]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(json.loads(result.stdout)["phase"], "input-check")

    def test_generic_cli_prepares_a_saved_response_without_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            modinfo = directory / "modinfo.txt"
            divider = "=" * 72
            modinfo.write_text(f"{divider}\nModule : tiny_external\n{divider}\n  10 0/1 result <= payload;\n")
            command = [sys.executable, str(framework.ROOT / "experiments/sequence_experiment.py"),
                       "--design", str(FIXTURES / "tiny_design.json"), "--modinfo", str(modinfo),
                       "--response-file", str(FIXTURES / "tiny_intents.json"),
                       "--out", str(directory / "run"), "--prepare-only"]
            env = {k: v for k, v in os.environ.items() if k not in (
                "RVPROBE_LLM_API_KEY", "RVPROBE_LLM_BASE_URL", "OPENAI_API_KEY", "OPENAI_BASE_URL")}
            result = subprocess.run(command, env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            summary = json.loads(result.stdout)
            self.assertEqual(summary["contract"], "runtime-ut-v2")
            self.assertEqual(summary["status"], "prepare")
            self.assertTrue((Path(summary["sources"]) / "DesignBinding.scala").is_file())


@unittest.skipUnless(os.environ.get("RVPROBE_RUN_TOOL_TESTS") == "1", "set RVPROBE_RUN_TOOL_TESTS=1 for CIRCT/scalac")
class RuntimeToolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        out = framework.ROOT / "out/experiments"
        out.mkdir(parents=True, exist_ok=True)
        cls.artifacts = Path(tempfile.mkdtemp(prefix="runtime-ut-regression-", dir=out))
        cls.design = framework.load_design(FIXTURES / "tiny_design.json")

    def compile(self, name, expression):
        sources = framework.write_sources(self.artifacts / name / "sources", self.design, {
            "intents": [{"label": "target", "expression": expression}], "proofObligations": []})
        report, log = loop.harness(sources, self.artifacts / name / "compile", loop.DEFAULT_EDA_SHELL, compile_only=True)
        (self.artifacts / name / "compile.log").write_text(log)
        return report

    def test_actual_compiler_accepts_new_ports_and_rejects_bad_expressions(self):
        good = self.compile("valid", "io.done")
        self.assertTrue(good["ok"], good)
        for name, expression in (("bad-port", "io.noSuchPort"), ("bad-type", "42"),
                                 ("old-category", "Sem.state(io.done)"), ("bad-bits", "io.payload")):
            with self.subTest(name=name):
                report = self.compile(name, expression)
                self.assertFalse(report["ok"], report)
                self.assertEqual(report["phase"], "typecheck")
                self.assertTrue(report.get("errors"), report)
        # Switching explicit source inputs must also recover after failed compilations.
        self.assertTrue(self.compile("valid-again", "io.valid")["ok"])

    def test_actual_circt_import(self):
        command = ["nix", "develop", ".", "-c", "python3", "experiments/sequence_framework.py",
                   "--design", str(FIXTURES / "tiny_design.json"),
                   "--response-file", str(FIXTURES / "tiny_intents.json"),
                   "--out", str(self.artifacts / "io"), "--check-io"]
        subprocess.run(command, cwd=framework.ROOT, check=True, capture_output=True, text=True)

    def test_actual_bool_predicates_and_diagnostic_hints(self):
        for name, expression in (
            ("bool-direct", "io.valid"),
            ("bool-and", "io.valid & (io.payload.asUInt === BigInt(7).U(8))"),
            ("sequence", "io.valid.S ### (!io.valid).S"),
            ("property", "!((!io.valid).S)"),
            ("history", "Gen.past(io.payload, 8, 2) === io.payload"),
            ("bool-not", "!io.valid"),
        ):
            with self.subTest(name=name):
                report = self.compile(name, expression)
                self.assertTrue(report["ok"], report)
        for name, expression in (
            ("bad-bool-and", "io.valid && (io.payload.asUInt === BigInt(7).U(8))"),
            ("bad-bool-cast", "io.valid.asUInt === BigInt(1).U(1)"),
        ):
            with self.subTest(name=name):
                report = self.compile(name, expression)
                self.assertFalse(report["ok"], report)
                self.assertEqual(report["phase"], "typecheck")
                repair = loop.feedback(report)
                self.assertEqual(repair["compilerDiagnostics"], report["errors"])
                self.assertEqual(repair["frameworkHints"][0]["id"], "hardware-bool-api")

    @unittest.skipUnless(os.environ.get("RVPROBE_RUN_JG_TESTS") == "1", "set RVPROBE_RUN_JG_TESTS=1 for licensed JasperGold")
    def test_four_intents_solve_and_export_from_original_rtl(self):
        response = framework.parse_response((FIXTURES / "tiny_intents.json").read_text())
        sources = framework.write_sources(self.artifacts / "four" / "sources", self.design, response)
        report, log = loop.harness(sources, self.artifacts / "four" / "solve", loop.DEFAULT_EDA_SHELL)
        (self.artifacts / "four" / "solve.log").write_text(log)
        self.assertTrue(report["ok"], report)
        self.assertEqual([r["status"] for r in report["result"]["intents"]], ["generated"] * 4)
        self.assertNotIn("sequenceFile", report["result"])
        self.assertEqual(report["result"]["replayContract"], "cycle-replay-v1")
        for row in report["result"]["intents"]:
            self.assertTrue(Path(row["stimulusFile"]).is_file())
            self.assertTrue(Path(row["sequenceFile"]).is_file())

    @unittest.skipUnless(os.environ.get("RVPROBE_RUN_JG_TESTS") == "1", "set RVPROBE_RUN_JG_TESTS=1 for licensed JasperGold")
    def test_no_witness_is_exported_for_empty_proof_or_unreachable_goal(self):
        for name, intents, proofs, status in (
            ("empty", [], [], "no-candidates"),
            ("pending", [], [{"label": "pending", "reason": "requires separate proof"}], "proof-required"),
            ("unreachable", [{"label": "impossible", "expression": "io.valid & !io.valid"}], [], "infeasible"),
        ):
            with self.subTest(name=name):
                sources = framework.write_sources(self.artifacts / name / "sources", self.design,
                    {"intents": intents, "proofObligations": proofs})
                report, log = loop.harness(sources, self.artifacts / name / "solve", loop.DEFAULT_EDA_SHELL)
                (self.artifacts / name / "solve.log").write_text(log)
                self.assertIn("result", report, report)
                self.assertEqual(report["result"]["status"], status, report)
                self.assertNotIn("sequenceFile", report["result"])
                self.assertFalse(list((self.artifacts / name / "solve").rglob("witness.vcd")))

    def test_invalid_history_shape_is_rejected_before_solver(self):
        for name, width, depth, message in (
            ("wrong-history-width", 7, 1, "history width must match the signal width"),
            ("empty-history", 8, 0, "history depth must be positive"),
        ):
            with self.subTest(name=name):
                sources = framework.write_sources(self.artifacts / name / "sources", self.design, {
                    "intents": [{"label": "target", "expression":
                                 f"Gen.past(io.payload, {width}, {depth}) === io.payload"}],
                    "proofObligations": []})
                report, log = loop.harness(sources, self.artifacts / name / "solve", loop.DEFAULT_EDA_SHELL)
                (self.artifacts / name / "run.log").write_text(log)
                self.assertFalse(report["ok"], report)
                self.assertIn(message, log)
                self.assertFalse(list((self.artifacts / name).rglob("witness.vcd")))

    @unittest.skipUnless(os.environ.get("RVPROBE_RUN_JG_TESTS") == "1", "set RVPROBE_RUN_JG_TESTS=1 for licensed JasperGold")
    def test_alu_uses_the_same_generator_with_its_own_io(self):
        design = framework.load_design()
        response = {"intents": [{"label": "completion", "expression": "io.done"}],
                    "proofObligations": []}
        sources = framework.write_sources(self.artifacts / "alu" / "sources", design, response)
        report, log = loop.harness(sources, self.artifacts / "alu" / "solve", loop.DEFAULT_EDA_SHELL)
        (self.artifacts / "alu" / "solve.log").write_text(log)
        self.assertTrue(report["ok"], report)
        self.assertEqual(report["result"]["status"], "generated")


if __name__ == "__main__":
    unittest.main()
