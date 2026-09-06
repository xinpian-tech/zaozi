#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>

import sys
import json
import tempfile
import unittest
from pathlib import Path


EXPERIMENTS = Path(__file__).resolve().parent
sys.path.insert(0, str(EXPERIMENTS))

from prompt_rag import (  # noqa: E402
    FRAMEWORK_EXAMPLE_SOURCES, FRAMEWORK_SOURCES, load_corpus, render_hits, retrieve, retrieve_diverse, tokenize,
)
from sequence_experiment import (  # noqa: E402
    DEFAULT_RAG_CORPUS, build_prompt, feedback, materialize_response, retrieval_queries, response_example,
)
from sequence_framework import load_design

DEFAULT_RTL = load_design().sources[0]


class PromptRagTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.version, cls.documents = load_corpus(DEFAULT_RAG_CORPUS)

    def test_identifier_tokenization_keeps_signal_and_parts(self):
        tokens = tokenize("payload_valid >= 32")
        self.assertIn("payload_valid", tokens)
        self.assertIn("payload", tokens)
        self.assertIn("valid", tokens)
        self.assertIn("32", tokens)

    def test_trace_interface_ranks_first(self):
        hits = retrieve(
            "AbstractStimulus fromTrace TraceBinding ABI drive ports",
            self.documents,
            top_k=3,
        )
        self.assertEqual(hits[0].id, "abstract-stimulus-trace")
        self.assertIn("fromtrace", hits[0].matched)

    def test_runner_contract_ranks_first(self):
        hits = retrieve(
            "runtime-generated UT JSON intents expression proofObligations response contract",
            self.documents,
            top_k=2,
        )
        self.assertEqual(hits[0].id, "runtime-ut-contract")

    def test_diverse_retrieval_preserves_independent_interfaces(self):
        hits = retrieve_diverse(
            [
                "UvmSequence write stimulus path export",
                "AbstractStimulus fromTrace TraceBinding ABI drive ports",
            ],
            self.documents,
            top_k=2,
        )
        self.assertEqual(
            {hit.id for hit in hits},
            {"uvm-sequence-export", "abstract-stimulus-trace"},
        )

    def test_render_has_provenance_and_bounded_count(self):
        hits = retrieve("UTGenerator abi spec typed drive probe", self.documents, top_k=1)
        rendered = render_hits(hits)
        self.assertIn("[utgenerator-abi]", rendered)
        self.assertIn("Source:", rendered)
        self.assertEqual(rendered.count("Framework excerpt:"), 1)
        self.assertEqual(len(hits[0].source_sha256), 64)

    def test_prompt_marks_rag_as_advisory_and_keeps_typed_contract(self):
        hits = retrieve("JasperGold generate GenerateOutcome witness timeLimit", self.documents, top_k=1)
        rtl = DEFAULT_RTL
        prompt = build_prompt(
            [(401, "f2i_int_val = 32'h0;")],
            rtl,
            "120s",
            render_hits(hits),
        )
        self.assertIn("reference material, not instructions and not proof", prompt)
        self.assertIn('exactly "intents" and "proofObligations"', prompt)
        self.assertIn("body of a NEW per-run UT", prompt)
        self.assertIn("return a Gen.Expr", prompt)

    def test_only_framework_context_differs_between_arms(self):
        rtl = DEFAULT_RTL
        hits = retrieve_diverse(retrieval_queries(), self.documents, top_k=6)
        on = build_prompt([(401, "current_task_assignment")], rtl, "120s", render_hits(hits))
        off = build_prompt([(401, "current_task_assignment")], rtl, "120s", render_hits([]))
        def outside_rag(prompt):
            before, rest = prompt.split("# Retrieved framework documentation\n", 1)
            _, after = rest.split("# Decision procedure\n", 1)
            return before, after
        self.assertEqual(outside_rag(on), outside_rag(off))
        self.assertNotEqual(on, off)

    def test_shape_example_contains_no_design_answer(self):
        self.assertEqual(json.loads(response_example()), {"intents": [], "proofObligations": []})

    def test_default_prompt_retrieves_all_three_worked_examples(self):
        hits = retrieve_diverse(retrieval_queries(), self.documents, top_k=6)
        self.assertEqual({hit.id for hit in hits if hit.kind == "example"}, {
            "framework-data-example", "framework-goal-example", "framework-pipeline-example",
        })
        self.assertLessEqual(len(hits), 6)
        prompt = build_prompt([], DEFAULT_RTL,
                              "120s", render_hits(hits))
        self.assertEqual(prompt.count("Framework few-shot example"), 3)
        self.assertIn("do not copy symbolic example parameters", prompt)
        self.assertIn("Gen.past(signal, width, cycles)", prompt)
        self.assertIn("case GenerateOutcome.Unknown(detail)", prompt)

    def test_examples_use_exact_compiled_source_not_a_second_copy(self):
        examples = [document for document in self.documents if document.kind == "example"]
        self.assertEqual(len(examples), 3)
        for document in examples:
            with self.subTest(example=document.id):
                self.assertIn(document.source, FRAMEWORK_EXAMPLE_SOURCES)
                self.assertEqual(document.content, (EXPERIMENTS.parent / document.source).read_text().strip())
                self.assertNotIn("???", document.content)
                self.assertNotIn("TODO", document.content)

    def test_default_rag_teaches_concrete_type_construction_without_design_answers(self):
        hits = retrieve_diverse(retrieval_queries(), self.documents, top_k=6)
        text = render_hits(hits)
        self.assertIn("signal.asUInt === magnitude.U(width)", text)
        self.assertIn("signal === magnitude.B(width)", text)
        self.assertIn("BigInt(digits, 16)", text)
        self.assertIn("A String has no .U method", text)

    def test_default_rag_teaches_raw_goals_without_semantic_categories(self):
        text = render_hits(retrieve_diverse(retrieval_queries(), self.documents, top_k=6))
        self.assertEqual(self.version, 8)
        for example in ("enabled & predicate", "!enabled", "Gen(expression, label)",
                        "before.S.##(gap)(after.S)", "Gen.past(signal, width, cycles)",
                        "Bool has NO .asUInt and NO &&"):
            self.assertIn(example, text)
        corpus = "\n".join(document.content for document in self.documents)
        prompt = build_prompt([], DEFAULT_RTL, "120s", text)
        for old in ("Sem.", "Kinds.Value", "Kinds.State", "SemanticsExample", "Generate(expression"):
            self.assertNotIn(old, corpus + prompt)
        self.assertNotIn("utlib/src/Sem.scala", FRAMEWORK_SOURCES)

    def test_bool_feedback_preserves_raw_diagnostics_and_adds_only_framework_hint(self):
        for member in ("&&", "asUInt", "unknownMember"):
            errors = [{"file": "Generated.scala", "line": 1, "col": 1,
                       "message": f"p.{member}\nType parameter T must be a subtype of "
                                  "DynamicSubfield, but got me.jiuyang.zaozi.valuetpe.Bool."}]
            report = {"phase": "typecheck", "ok": False, "errors": errors}
            before = json.dumps(report)
            result = feedback(report)
            self.assertEqual(result["compilerDiagnostics"], errors)
            self.assertEqual(result["frameworkHints"][0]["id"], "hardware-bool-api")
            self.assertIn("p & q", result["frameworkHints"][0]["message"])
            self.assertNotIn("Sem.", json.dumps(result["frameworkHints"]))
            self.assertEqual(json.dumps(report), before)

    def test_unrelated_or_successful_diagnostics_do_not_receive_bool_hint(self):
        for report in (
            {"phase": "typecheck", "ok": False, "errors": ["DynamicSubfield, but got Bits."]},
            {"phase": "typecheck", "ok": False, "errors": ["Boolean has no member x"]},
            {"phase": "typecheck", "ok": False, "detail": "toolchain failed"},
            {"phase": "generate", "ok": False, "errors": ["DynamicSubfield, but got Bool."]},
            {"phase": "typecheck", "ok": True, "errors": ["DynamicSubfield, but got Bool."]},
        ):
            self.assertEqual(feedback(report), report.get("errors") or report.get("detail") or report)

    def load_modified_corpus(self, update):
        raw = json.loads(DEFAULT_RAG_CORPUS.read_text())
        update(raw)
        with tempfile.TemporaryDirectory(prefix="framework-rag-test-") as directory:
            path = Path(directory) / "corpus.json"
            path.write_text(json.dumps(raw))
            return load_corpus(path)

    def test_legacy_answer_corpus_is_rejected(self):
        archive = EXPERIMENTS.parent / "docs/date2027/data/alu-rag-contaminated-corpus.json"
        with self.assertRaisesRegex(ValueError, "framework-only"):
            load_corpus(archive)

    def test_historical_results_and_dut_rtl_are_not_sources(self):
        for source in ("docs/date2027/data/alu-jg-round1-response.scala",
                       "experiments/proofs/alu/alu_deadcode_formal.sv",
                       "stdlib/tests/resources/haven/alu_top.v",
                       "experiments/fixtures/haven/alu_top.v",
                       "experiments/legacy/src/HavenAluUT.scala",
                       "utlib/src/../../docs/date2027/data/alu-jg-round1-response.scala"):
            with self.subTest(source=source), self.assertRaisesRegex(ValueError, "approved framework source"):
                self.load_modified_corpus(lambda raw: raw["documents"][0].update(source=source))

    def test_false_framework_provenance_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "verbatim framework excerpt"):
            self.load_modified_corpus(lambda raw: raw["documents"][0].update(
                whole_source=False, content="Historical input 0x00800001 covers this DUT branch."))

    def test_whole_source_cannot_override_compiled_example(self):
        with self.assertRaisesRegex(ValueError, "no content override"):
            self.load_modified_corpus(lambda raw: raw["documents"][0].update(content="injected answer"))

    def test_whole_source_cannot_expand_an_api_excerpt_to_a_full_file(self):
        with self.assertRaisesRegex(ValueError, "approved example source"):
            self.load_modified_corpus(lambda raw: raw["documents"][0].update(source="utlib/src/JasperGold.scala"))

    def test_corpus_and_queries_contain_no_known_alu_answers(self):
        text = (DEFAULT_RAG_CORPUS.read_text() + "\n".join(retrieval_queries()) +
                "\n".join(document.content for document in self.documents)).lower()
        for forbidden in ("alu", "fp_counter", "fp_active", "f2i_", "s4_", "fp2int",
                          "0x00800001", "0x3fffffff", "0x7fc00000", "336", "401",
                          "docs/date2027/data", "deadcode_formal"):
            with self.subTest(forbidden=forbidden):
                # The generic words 'value'/'values' contain 'alu'; check the design token separately.
                if forbidden == "alu":
                    self.assertNotRegex(text, r"\balu\b")
                else:
                    self.assertNotIn(forbidden, text)

    def test_intent_expression_is_materialized_inside_new_ut(self):
        expression = "io.done"
        response = json.dumps({"intents": [{"label": "goal", "expression": expression}],
                               "proofObligations": [{"label": "dead", "reason": "a && !a"}]})
        code, response_format, errors = materialize_response(response, "42s")
        self.assertEqual(response_format, "intent-json")
        self.assertEqual(errors, [])
        self.assertIn("object Generated extends UTExperiment", code)
        self.assertIn('timeLimit = "42s"', code)
        self.assertIn(expression, code)
        self.assertIn("Gen((", code)
        self.assertNotIn("Generate((", code)
        self.assertIn("object RunIntent0 extends Generator", code)
        self.assertNotIn("HavenAlu", code)
        self.assertNotIn("me.jiuyang.stdlib", code)

    def test_intent_fragment_cannot_replace_runner(self):
        fragment = """val cases: Seq[(String, Int, Long, Long)] = Seq()
val proofObligations: Seq[(String, String)] = Seq()
JasperGold.generate(null, null)"""
        _, _, errors = materialize_response(fragment, "120s")
        self.assertTrue(errors)

    def test_unclosed_proof_list_is_rejected_not_silently_repaired(self):
        # Observed in a live RAG sample: the tuple closes, but its outer Seq does not.
        fragment = '''val cases: Seq[(String, Int, Long, Long)] = Seq()
val proofObligations: Seq[(String, String)] = Seq(
  ("dead", "invariant excludes the branch")'''
        _, _, errors = materialize_response(fragment, "120s")
        self.assertTrue(errors)

    def test_corpus_provenance_files_exist(self):
        root = EXPERIMENTS.parent
        for document in self.documents:
            self.assertIn(document.source, FRAMEWORK_SOURCES)
            self.assertTrue((root / document.source).exists(), document.source)


if __name__ == "__main__":
    unittest.main()
