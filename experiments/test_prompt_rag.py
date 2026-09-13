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
from test_support import goal_response

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

    def test_expression_type_ranks_first(self):
        hits = retrieve("Gen Expr Bool Sequence Property", self.documents, top_k=3)
        self.assertEqual(hits[0].id, "generation-goal-types")

    def test_runner_and_ut_contracts_are_not_model_references(self):
        for document in self.documents:
            self.assertNotIn(document.source, FRAMEWORK_EXAMPLE_SOURCES)
            self.assertNotIn("proofObligations", document.content)
            self.assertNotIn("extends Generator", document.content)
            self.assertNotIn("UTGenerator", document.content)

    def test_diverse_retrieval_preserves_expression_apis(self):
        hits = retrieve_diverse(["Gen Expr Bool Sequence Property", "past cycles"], self.documents, top_k=2)
        self.assertEqual(len(hits), 2)
        self.assertEqual(len({hit.id for hit in hits}), 2)

    def test_render_has_provenance_and_bounded_count(self):
        hits = retrieve("Gen Expr Bool Sequence Property", self.documents, top_k=1)
        rendered = render_hits(hits)
        self.assertIn("[generation-goal-types]", rendered)
        self.assertIn("Source:", rendered)
        self.assertEqual(rendered.count("Framework excerpt:"), 1)
        self.assertEqual(len(hits[0].source_sha256), 64)

    def test_prompt_has_only_io_spec_ltl_contract_and_task_tools(self):
        prompt = build_prompt([(401, "hidden_rtl_body")], DEFAULT_RTL, "120s")
        for excluded in ("DesignBinding.scala", "extends Generator", "def architecture", "generationLabels",
                         "proofObligations", "dut.io", "hidden_rtl_body"):
            self.assertNotIn(excluded, prompt)
        for included in ("raw Scala LTL", "read_rtl", "DUT IO", "DUT specification", "no Assume"):
            self.assertIn(included, prompt)

    def test_only_framework_context_differs_between_arms(self):
        rtl = DEFAULT_RTL
        hits = retrieve_diverse(retrieval_queries(), self.documents, top_k=6)
        on = build_prompt([(401, "current_task_assignment")], rtl, "120s", render_hits(hits))
        off = build_prompt([(401, "current_task_assignment")], rtl, "120s", render_hits([]))
        def outside_rag(prompt):
            before, rest = prompt.split("# Additional LTL references\n", 1)
            _, after = rest.split("# Execution boundary\n", 1)
            return before, after
        self.assertEqual(outside_rag(on), outside_rag(off))
        self.assertNotEqual(on, off)

    def test_shape_example_contains_no_design_answer(self):
        self.assertEqual(response_example(), 'Gen(p.##(gap)(q), "ordered_events")')

    def test_prompt_explains_independent_sampling_and_no_assumptions(self):
        prompt = build_prompt([], DEFAULT_RTL, "120s", sequences_per_intent=4)
        for rule in ("one fixed UT", "no Assume", "solved independently", "up to 4 distinct sequences"):
            self.assertIn(rule, prompt)
        self.assertNotIn("Assume(", prompt)

    def test_default_references_cover_expression_apis_not_scaffolding(self):
        hits = retrieve_diverse(retrieval_queries(), self.documents, top_k=6)
        self.assertEqual({hit.id for hit in hits}, {d.id for d in self.documents})
        self.assertEqual(len(hits), 5)
        self.assertTrue(all(h.kind == "reference" for h in hits))

    def test_references_are_exact_excerpts_of_current_api_documentation(self):
        for document in self.documents:
            self.assertIn(document.content, (EXPERIMENTS.parent / document.source).read_text())

    def test_skill_teaches_types_without_design_answers(self):
        from rvprobe_skill import snapshot
        text = snapshot()["content"]
        for example in ("Referable[Bits]", "value.B(width)", "BigInt(digits, radix)", "String has no"):
            self.assertIn(example, text)

    def test_default_rag_teaches_native_ltl_apis_without_categories(self):
        text = render_hits(retrieve_diverse(retrieval_queries(), self.documents, top_k=6))
        self.assertEqual(self.version, 16)
        for api in ("Sequence", "Property", "past(", "Some(hi)"):
            self.assertIn(api, text)
        for obsolete in ("Sem.", "Kinds.Value", "Generate(expression", "generationLabels"):
            self.assertNotIn(obsolete, text)

    def test_feedback_preserves_precise_compiler_diagnostics_without_guessing(self):
        for member in ("&&", "asUInt", "unknownMember"):
            errors = [{"file": "Generated.scala", "line": 1, "col": 1,
                       "message": f"Cannot resolve member '{member}' on hardware type "
                                  "me.jiuyang.zaozi.valuetpe.Bool."}]
            report = {"phase": "typecheck", "ok": False, "errors": errors}
            before = json.dumps(report)
            result = feedback(report)
            self.assertEqual(result, errors)
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

    def test_removed_ut_example_cannot_reenter_active_corpus(self):
        with self.assertRaisesRegex(ValueError, "approved framework source"):
            self.load_modified_corpus(lambda raw: raw["documents"][0].update(
                source="experiments/src/rag/FrameworkUTExample.scala", whole_source=True))

    def test_whole_source_cannot_expand_an_api_excerpt_to_a_full_file(self):
        with self.assertRaisesRegex(ValueError, "approved example source"):
            self.load_modified_corpus(lambda raw: raw["documents"][0].update(whole_source=True))

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

    def test_raw_ltl_is_inserted_into_fixed_ut_without_semantic_rewriting(self):
        fragment = 'Gen(io.done, "goal")'
        code, response_format, errors = materialize_response(fragment, "42s")
        self.assertEqual(response_format, "ltl-source")
        self.assertEqual(errors, [])
        self.assertIn("object Generated extends UTExperiment", code)
        self.assertIn("object ModelUT extends Generator", code)
        self.assertIn("    " + fragment, code)
        self.assertNotIn("Assume(", code)
        self.assertNotIn("me.jiuyang.stdlib", code)

    def test_paired_intent_limit_is_checked_before_compilation(self):
        response = "\n".join(f'Gen(io.done, "goal_{i}")' for i in range(5))
        code, _, errors = materialize_response(response, "42s", max_intents=4)
        self.assertEqual(code, "")
        self.assertTrue(any("at most 4" in error for error in errors))

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
