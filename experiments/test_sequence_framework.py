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
from test_support import goal_response, goals_response

FIXTURES = Path(__file__).resolve().parent / "tests/fixtures"


class SequenceFrameworkTest(unittest.TestCase):
    def setUp(self):
        self.design = framework.load_design(FIXTURES / "tiny_design.json")
        self.response = framework.parse_response((FIXTURES / "tiny_intents.ltl").read_text())

    def changed_manifest(self, change):
        raw = json.loads((FIXTURES / "tiny_design.json").read_text())
        raw["sources"] = [str(self.design.sources[0])]
        change(raw)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "design.json"
            path.write_text(json.dumps(raw))
            return framework.load_design(path)

    def test_one_binding_and_model_authored_ut_bodies_are_not_regenerated(self):
        binding = framework.render_binding(self.design)
        program = framework.render_program(self.design, self.response)
        self.assertEqual(binding.count("extends VerilogWrapper["), 1)
        self.assertNotIn("extends VerilogWrapper[", program)
        self.assertNotIn("extends Generator[", program)
        self.assertNotIn("def architecture", program)
        self.assertNotIn("Gen((", program)
        self.assertNotIn("Sem.", program)
        self.assertNotIn("Generate((", program)
        self.assertNotIn("Txn.", program)
        self.assertNotIn("UvmSequence.concat", program)
        self.assertIn(f"UTGenerator({framework.MODEL_MODULE},", program)
        self.assertEqual(program.count("JasperGold.lower("), 1)
        self.assertNotIn("JasperGold.generate", program)
        trusted = (framework.ROOT / "experiments/src/TrustedSolver.scala").read_text()
        self.assertIn("JasperGold.requireUnconstrainedUT(model)", trusted)
        self.assertIn("JasperGold.selectGoal(model, label", trusted)
        for label in self.response["labels"]:
            self.assertIn(f'"{label}"', program)
        self.assertFalse(loop.backend_errors(binding + program))

    def test_binding_contains_only_interface_not_rtl_behavior(self):
        binding = framework.render_binding(self.design)
        self.assertIn('"tiny_external"', binding)
        self.assertIn("val `payload` = Flipped(Bits(8))", binding)
        self.assertNotIn("def architecture", binding)

    def test_port_aliases_are_manifest_derived_and_never_rewrite_ltl(self):
        response = framework.parse_response('Gen(valid ### done, "handshake")\n')
        aliases = framework.port_bindings(self.design)
        self.assertEqual(aliases, dict(clock="clock", reset="reset", payload="payload",
                                      valid="valid", result="result", done="done"))
        source = framework.render_model_ut(self.design, response)
        for name, alias in aliases.items():
            self.assertEqual(source.count(f'val `{alias}` = io.`{name}`'), 1)
        self.assertIn('    Gen(valid ### done, "handshake")\n', source)
        self.assertNotIn('Gen(valid.S', source)
        prompt = loop.build_prompt([], self.design.sources[0], "5s", design=self.design)
        self.assertIn("valid: Bool()", prompt)
        self.assertIn("payload: Bits(8)", prompt)
        self.assertNotIn("io.`valid`", prompt)

    def test_port_name_collisions_are_explicit_and_deterministic(self):
        from dataclasses import replace
        names = {"payload": "Gen", "valid": "past", "result": "port_Gen", "done": "class"}
        design = replace(self.design, ports=tuple(replace(p, name=names.get(p.name, p.name))
                                                  for p in self.design.ports))
        aliases = framework.port_bindings(design)
        self.assertEqual(aliases["Gen"], "port_port_Gen")
        self.assertEqual(aliases["port_Gen"], "port_Gen")
        self.assertEqual(aliases["past"], "port_past")
        self.assertEqual(aliases["class"], "port_class")
        self.assertEqual(len(set(aliases.values())), len(aliases))
        prompt = loop.io_contract(design)
        self.assertIn("port_port_Gen: Bits(8) (input of DUT; RTL port Gen)", prompt)
        self.assertIn("port_past: Bool() (input of DUT; RTL port past)", prompt)

    def test_multiple_include_directories_are_manifest_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            first,second=Path(directory)/'first',Path(directory)/'second'
            first.mkdir();second.mkdir()
            design=self.changed_manifest(lambda raw: raw.update(include_dirs=[str(first),str(second)]))
            self.assertEqual(design.include_dirs,(first,second))

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
        source = framework.render_model_ut(self.design, self.response)
        self.assertIn("dut.io.`rst` := io.reset.asBool", source)
        low = self.changed_manifest(lambda raw: raw["reset"].update(active_low=True))
        self.assertIn("dut.io.`rst` := !io.reset.asBool", framework.render_model_ut(low, self.response))
        self.assertNotIn("dut.io", loop.build_prompt([], low.sources[0], "5s", design=low))

    def test_invalid_interface_is_rejected(self):
        changes = [lambda r: r["ports"].append(r["ports"][0]),
                   lambda r: r["ports"][2].update(direction="inout"),
                   lambda r: r["ports"][2].update(width=0),
                   lambda r: r["ports"][2].update(width=True),
                   lambda r: r["ports"][2].update(name="clock"),
                   lambda r: r["reset"].update(active_low="false"),
                   lambda r: r.update(clock="missing")]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.changed_manifest(change)

    def test_secondary_clock_is_not_a_sequence_payload(self):
        design = self.changed_manifest(lambda r: r['ports'][2].update(kind='clock',width=1))
        self.assertNotIn(design.ports[2].name, design.drive_names)
        self.assertIn('Flipped(Clock())', framework.render_binding(design))

    def test_parameters_are_binding_metadata_not_a_new_design(self):
        design = self.changed_manifest(lambda raw: raw.update(parameters={"WIDTH": 8}))
        self.assertIn('`WIDTH`: BigInt = BigInt("8")', framework.render_binding(design))
        with self.assertRaisesRegex(ValueError, "parameter overrides"):
            framework.check_interface(design, Path("unused"))

    def test_duplicate_and_path_labels_are_rejected(self):
        for label in ("../escape", "same/slash", "a-b", "input_value"):
            text = self.response["ltl"].replace('"observed_result"', json.dumps(label))
            with self.subTest(label=label), self.assertRaises(ValueError):
                framework.parse_response(text)

    def test_only_raw_ltl_is_accepted(self):
        for raw in ('object Generated extends UTExperiment', '{}',
                    (FIXTURES / "tiny_intents.json").read_text(), 'Gen(io.valid, "a")\nprose'):
            with self.subTest(raw=raw):
                if raw.endswith("prose"):
                    # Unknown identifiers belong to real Scala type checking, not a guessed parser.
                    self.assertEqual(framework.parse_response(raw)["labels"], ["a"])
                else:
                    with self.assertRaises(ValueError):
                        framework.parse_response(raw)

    def test_ltl_lexical_diagnostics_are_bounded_and_located(self):
        for text in ('// ' + '语' * 10000 + '\nGen(io.valid, "a"', 'Gen(io.valid, "a")\n/*'):
            with self.subTest(text_length=len(text)), self.assertRaises(ValueError) as caught:
                framework.parse_response(text)
            self.assertIn("character", str(caught.exception))
            self.assertLess(len(str(caught.exception)), 400)

    def test_labels_are_extracted_not_model_metadata(self):
        self.assertEqual(self.response["labels"], ["input_value", "observed_result", "two_beats", "history"])
        self.assertEqual(set(self.response), {"ltl", "labels"})
        for text in ('Gen(io.valid, label)', 'Gen(io.valid)', 'Gen(io.valid, "a", "b")',
                     'Gen(io.valid, s"a")', 'val alias = Gen\nalias(io.valid, "a")'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                framework.parse_response(text)

    def test_comments_and_strings_do_not_create_goals(self):
        text = '// Gen(io.valid, "fake")\n/* outer /* nested */ Assume */\n' + self.response["ltl"]
        self.assertEqual(framework.parse_response(text)["labels"], self.response["labels"])
        self.assertEqual(framework.parse_response(text)["ltl"], text)

    def test_model_shell_wiring_assumptions_and_redefinitions_are_rejected(self):
        for extra in ('Assume(io.valid.I, "limit")', '@generator\nobject Other',
                      'io.valid := true.B', 'val io = null', 'def Gen = null',
                      'given ClockEvent = posedge(io.clock)', 'import unsafe.*'):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                framework.parse_response(self.response["ltl"] + "\n" + extra)

    def test_stop_and_goal_count(self):
        self.assertEqual(framework.parse_response("STOP\n"), {"stop": True})
        for text in ("", "// no goals", "\n".join(f'Gen(io.valid, "g_{i}")' for i in range(65))):
            with self.subTest(text=text), self.assertRaises(ValueError):
                framework.parse_response(text)

    def test_io_names_do_not_turn_into_framework_keywords(self):
        text = 'Gen(io.`class` & io.Wire & io.Gen, "goal")'
        self.assertEqual(framework.parse_response(text)["labels"], ["goal"])
        with self.assertRaises(ValueError):
            framework.parse_response('val x = `Wire`(Bool())\nGen(io.valid, "goal")')

    def test_rtl_override_affects_both_tool_evidence_and_solver(self):
        with tempfile.TemporaryDirectory() as directory:
            rtl = Path(directory) / "replacement.v"
            rtl.write_text(self.design.sources[0].read_text() + "\n// unique_override\n")
            design = self.design.with_rtl(rtl)
            line = len(rtl.read_text().splitlines())
            prompt = loop.build_prompt([(line, "unique_override")], rtl, "5s", design=design)
            program = framework.render_program(design, self.response)
            self.assertNotIn("unique_override", prompt)
            from task_context import TaskContext
            context = TaskContext(design)
            self.assertIn('unique_override', context.dispatch('read_rtl', {'file_id': 'rtl_0001'})['text'])
            self.assertEqual(design.record()["sources"][0]["path"], str(rtl))
            self.assertNotIn(str(rtl), program)  # Real RTL only enters the trusted solver, not model elaboration.
            self.assertNotIn(str(self.design.sources[0]), program)

    def test_sources_and_input_hashes_are_run_local(self):
        with tempfile.TemporaryDirectory() as directory:
            target = framework.write_sources(Path(directory), self.design, self.response)
            self.assertEqual({p.name for p in target.iterdir()},
                             {"DesignBinding.scala", "Generated.scala", "response.json", "design.json", "model-sources.json", "ModelUT.scala", "model.ltl"})
            self.assertEqual((target / "ModelUT.scala").read_bytes(), framework.render_model_ut(self.design, self.response).encode())
            record = json.loads((target / "design.json").read_text())
            self.assertEqual(len(record["sources"][0]["sha256"]), 64)
        self.assertFalse((framework.ROOT / "experiments/src/Generated.scala").exists())

    def test_resume_never_trusts_a_replaced_binding_or_runner(self):
        with tempfile.TemporaryDirectory() as directory:
            target = framework.write_sources(Path(directory), self.design, self.response)
            framework.check_saved_sources(target, self.design, self.response)
            for name in ("DesignBinding.scala", "Generated.scala"):
                path = target / name
                original = path.read_bytes()
                path.write_bytes(original + b"// altered\n")
                with self.assertRaisesRegex(ValueError, "saved source changed"):
                    framework.check_saved_sources(target, self.design, self.response)
                path.write_bytes(original)
            (target / "Extra.scala").write_text("object Extra\n")
            with self.assertRaisesRegex(ValueError, "inventory"):
                framework.check_saved_sources(target, self.design, self.response)

    def test_ltl_helpers_whitespace_and_generated_source_are_integrity_checked(self):
        response = framework.parse_response(self.response["ltl"] + "\n// local helper\ndef identity(p: Referable[Bool]) = p\n\n")
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            sources = framework.write_sources(directory / "sources", self.design, response)
            self.assertEqual((sources / "model.ltl").read_bytes(), response["ltl"].encode())
            self.assertEqual((sources / "ModelUT.scala").read_text(), framework.render_model_ut(self.design, response))
            for name in ("model.ltl", "ModelUT.scala", "response.json"):
                path = sources / name
                before = path.read_bytes()
                path.write_bytes(before + (b" " if name.endswith(".json") else b"// changed\n"))
                if name.endswith(".json"):
                    path.write_text('{}')
                with self.assertRaises(ValueError):
                    framework.check_saved_sources(sources, self.design, response)
                path.write_bytes(before)

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
                       "--response-file", str(FIXTURES / "tiny_intents.ltl"),
                       "--out", str(directory / "run"), "--prepare-only"]
            env = {k: v for k, v in os.environ.items() if k not in (
                "RVPROBE_LLM_API_KEY", "RVPROBE_LLM_BASE_URL", "OPENAI_API_KEY", "OPENAI_BASE_URL")}
            result = subprocess.run(command, env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            summary = json.loads(result.stdout)
            self.assertEqual(summary["contract"], "runtime-ltl-v2")
            manifest = json.loads((directory / "run/manifest.json").read_text())
            self.assertEqual(manifest["framework_context_policy"], "ltl-bare-ports-v2")
            prompt_record = json.loads((directory / "run/attempt-1/prompt.json").read_text())
            self.assertEqual(prompt_record["framework_context_policy"], "ltl-bare-ports-v2")
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
        sources = framework.write_sources(self.artifacts / name / "sources", self.design, goal_response("target", expression))
        report, log = loop.harness(sources, self.artifacts / name / "compile", loop.DEFAULT_EDA_SHELL, compile_only=True)
        (self.artifacts / name / "compile.log").write_text(log)
        return report

    def test_bare_ports_preserve_types_and_bool_temporal_overloads(self):
        expression = """{
val input: Referable[Bits] = payload
val output: Referable[Bits] = result
val request: Referable[Bool] = valid
val response: Referable[Bool] = done
val bits: Referable[Bits] = past(input, 2)
val predicate: Referable[Bool] = request & (input === BigInt(7).B(8))
val first: Sequence = predicate ### response
val next: Sequence = first.##(2)(request)
val bounded: Sequence = next.##(0, Some(2))((output === bits) & !response)
bounded
}"""
        self.assertTrue(self.compile("bare-typed", expression)["ok"])
        for name, expression in (("bare-unknown", "missing_port ### done"),
                                 ("bits-not-bool-left", "payload ### valid"),
                                 ("bits-not-bool-right", "valid ### payload"),
                                 ("property-not-sequence", "valid ### !done.S")):
            with self.subTest(name=name):
                report = self.compile(name, expression)
                self.assertFalse(report["ok"], report)
                self.assertEqual(report["phase"], "typecheck")
                self.assertTrue(any(e["file"] == "model.ltl" for e in report["errors"]))

    def test_escaped_port_aliases_compile_without_capturing_gen_or_past(self):
        from dataclasses import replace
        names = {"payload": "Gen", "valid": "past", "result": "port_Gen", "done": "class"}
        # Compiler-only binding test; does not claim that renamed ports match the original RTL.
        design = replace(self.design, ports=tuple(replace(p, name=names.get(p.name, p.name))
                                                  for p in self.design.ports))
        response = framework.parse_response(
            'Gen(port_past ### (port_class & (past(port_port_Gen) === port_Gen)), "goal")')
        root = self.artifacts / "escaped-ports"
        sources = framework.write_sources(root / "sources", design, response)
        report, log = loop.harness(sources, root / "compile", loop.DEFAULT_EDA_SHELL, compile_only=True)
        self.assertTrue(report["ok"], str(report) + log[-1500:])

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
                   "--response-file", str(FIXTURES / "tiny_intents.ltl"),
                   "--out", str(self.artifacts / "io"), "--check-io"]
        subprocess.run(command, cwd=framework.ROOT, check=True, capture_output=True, text=True)

    def test_actual_bool_predicates_and_diagnostic_hints(self):
        for name, expression in (
            ("bool-direct", "io.valid"),
            ("bool-and", "io.valid & (io.payload.asUInt === BigInt(7).U(8))"),
            ("sequence", "io.valid.S ### (!io.valid).S"),
            ("property", "!((!io.valid).S)"),
            ("history", "past(io.valid, 2)"),
            ("bool-not", "!io.valid"),
        ):
            with self.subTest(name=name):
                report = self.compile(name, expression)
                self.assertTrue(report["ok"], report)
        for name, expression, member in (
            ("bad-bool-and", "io.valid && (io.payload.asUInt === BigInt(7).U(8))", "&&"),
            ("bad-bool-cast", "io.valid.asUInt === BigInt(1).U(1)", "asUInt"),
        ):
            with self.subTest(name=name):
                report = self.compile(name, expression)
                self.assertFalse(report["ok"], report)
                self.assertEqual(report["phase"], "typecheck")
                repair = loop.feedback(report)
                self.assertEqual(repair, report["errors"])
                self.assertIn(f"Cannot resolve member '{member}' on hardware type", json.dumps(repair))
                self.assertNotIn("DynamicSubfield", json.dumps(repair))
                self.assertTrue(any(Path(error["file"]).name == "model.ltl" and
                                    error["line"] > 0 and error["col"] > 0 for error in repair))

    @unittest.skipUnless(os.environ.get("RVPROBE_RUN_JG_TESTS") == "1", "set RVPROBE_RUN_JG_TESTS=1 for licensed JasperGold")
    def test_four_intents_solve_and_export_from_original_rtl(self):
        response = framework.parse_response((FIXTURES / "tiny_intents.ltl").read_text())
        sources = framework.write_sources(self.artifacts / "four" / "sources", self.design, response)
        report, log = loop.harness(sources, self.artifacts / "four" / "solve", loop.DEFAULT_EDA_SHELL)
        (self.artifacts / "four" / "solve.log").write_text(log)
        self.assertTrue(report["ok"], report)
        self.assertEqual([r["status"] for r in report["result"]["goals"]], ["generated"] * 4)
        lowered = list((self.artifacts / "four" / "solve" / "ut" / "lowered").glob("*.sv"))
        self.assertEqual(len(lowered), 1)
        emitted = lowered[0].read_text()
        self.assertIn("input_value: cover property (@(posedge clock)", emitted)
        self.assertNotIn("assert property", emitted)
        self.assertEqual(report["result"]["utCount"], 1)
        self.assertNotIn("sequenceFile", report["result"])
        self.assertEqual(report["result"]["replayContract"], "cycle-replay-v1")
        for row in report["result"]["goals"]:
            self.assertTrue(Path(row["stimulusFile"]).is_file())
            self.assertTrue(Path(row["sequenceFile"]).is_file())

    @unittest.skipUnless(os.environ.get("RVPROBE_RUN_JG_TESTS") == "1", "set RVPROBE_RUN_JG_TESTS=1 for licensed JasperGold")
    def test_no_witness_is_exported_for_unreachable_goal(self):
        sources = framework.write_sources(self.artifacts / "unreachable" / "sources", self.design,
            goal_response("impossible", "io.valid & !io.valid"))
        report, log = loop.harness(sources, self.artifacts / "unreachable" / "solve", loop.DEFAULT_EDA_SHELL)
        self.assertEqual(report["result"]["status"], "no-witness", report)
        self.assertEqual(report["result"]["goals"][0]["status"], "infeasible", report)
        self.assertFalse(list((self.artifacts / "unreachable" / "solve").rglob("witness.vcd")))

    @unittest.skipUnless(os.environ.get("RVPROBE_RUN_JG_TESTS") == "1", "licensed JasperGold")
    def test_mutually_exclusive_goals_in_one_ut_are_solved_independently(self):
        response = goals_response([("low", "!io.valid"), ("high", "io.valid")])
        sources = framework.write_sources(self.artifacts / "independent" / "sources", self.design, response)
        report, log = loop.harness(sources, self.artifacts / "independent" / "solve", loop.DEFAULT_EDA_SHELL)
        self.assertTrue(report["ok"], report)
        for row in report["result"]["goals"]:
            beats = json.loads(Path(row["stimulusFile"]).read_text())
            self.assertTrue(any(int(beat["valid"]) == int(row["label"] == "high") for beat in beats))

    @unittest.skipUnless(os.environ.get("RVPROBE_RUN_JG_TESTS") == "1", "licensed JasperGold")
    def test_partial_success_and_verified_resume_keep_successful_goals(self):
        from run_records import totals
        response = goals_response([("good", "io.valid"), ("impossible", "io.valid & !io.valid")])
        root = self.artifacts / "partial-resume"
        sources = framework.write_sources(root / "sources", self.design, response)
        report, _ = loop.harness(sources, root / "solve", loop.DEFAULT_EDA_SHELL)
        self.assertTrue(report["ok"], report)
        self.assertEqual(report["result"]["status"], "partial")
        good, bad = report["result"]["goals"]
        self.assertEqual(bad["status"], "infeasible")
        self.assertTrue(Path(good["witnessFile"]).is_file())
        before = totals(root)["phases"]["goal-solve"]["count"]
        resumed, _ = loop.harness(sources, root / "solve", loop.DEFAULT_EDA_SHELL, resume=True)
        self.assertTrue(resumed["ok"], resumed)
        self.assertEqual(resumed["result"]["goals"][0], good)
        self.assertEqual(totals(root)["phases"]["goal-solve"]["count"], before + 1)

    @unittest.skipUnless(os.environ.get("RVPROBE_RUN_JG_TESTS") == "1", "licensed JasperGold")
    def test_explicit_replay_reset_initializes_native_past(self):
        root = self.artifacts / "reset-history"
        sources = framework.write_sources(root / "sources", self.design, goal_response("target", "past(io.valid, 5) & io.done"))
        replay = root / "replay.json"
        replay.write_text(json.dumps({"version": 1, "contract": "cycle-replay-v1",
            "design": str((FIXTURES / "tiny_design.json").resolve()),
            "reset_cycles": 16, "drain_cycles": 16, "idle": {"valid": 0, "payload": 0},
            "request": {"valid": 1}, "baseline": {"mode": "reset-only", "idle_cycles": 1}}))
        report, log = loop.harness(sources, root / "solve", loop.DEFAULT_EDA_SHELL, replay_config=replay)
        (root / "run.log").write_text(log)
        self.assertTrue(report["ok"], report)
        goal = report["result"]["goals"][0]
        self.assertEqual(goal["status"], "generated", report)
        beats = json.loads(Path(goal["stimulusFile"]).read_text())
        self.assertGreaterEqual(len(beats), 6)
        self.assertEqual(int(beats[-6]["valid"]), 1)

    def test_invalid_history_shape_is_rejected_before_solver(self):
        for name, depth, message in (
            ("zero-history", 0, "must be greater than 0"),
            ("negative-history", -1, "must be greater than 0"),
        ):
            with self.subTest(name=name):
                sources = framework.write_sources(self.artifacts / name / "sources", self.design, goal_response("target", f"past(io.valid, {depth})"))
                report, log = loop.harness(sources, self.artifacts / name / "solve", loop.DEFAULT_EDA_SHELL)
                (self.artifacts / name / "run.log").write_text(log)
                self.assertFalse(report["ok"], report)
                self.assertIn(message, log)
                self.assertFalse(list((self.artifacts / name).rglob("witness.vcd")))

    @unittest.skipUnless(os.environ.get("RVPROBE_RUN_JG_TESTS") == "1", "set RVPROBE_RUN_JG_TESTS=1 for licensed JasperGold")
    def test_alu_uses_the_same_generator_with_its_own_io(self):
        design = framework.load_design()
        response = goal_response("completion", "io.done", design)
        sources = framework.write_sources(self.artifacts / "alu" / "sources", design, response)
        report, log = loop.harness(sources, self.artifacts / "alu" / "solve", loop.DEFAULT_EDA_SHELL)
        (self.artifacts / "alu" / "solve.log").write_text(log)
        self.assertTrue(report["ok"], report)
        self.assertEqual(report["result"]["status"], "generated")


if __name__ == "__main__":
    unittest.main()
