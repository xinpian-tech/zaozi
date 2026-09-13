"""Offline evidence parity and prompt projection tests; no model or EDA calls."""
from copy import deepcopy
import json
from pathlib import Path
import unittest

from coverage_flow import build_haven_prompt
from prompt_context import common_context, paired_feedback, BATCH_INSTRUCTION, RVPROBE_BATCH_INSTRUCTION, MAX_INTENTS, public_bfm_parameters
from run_records import fingerprint
from sequence_experiment import io_contract, build_prompt, design_evidence, prompt_json
from sequence_framework import load_design, render_binding
from task_context import TaskContext, INSTRUCTION


class CommonContextTests(unittest.TestCase):
    def setUp(self):
        self.design = load_design(Path(__file__).parent / "tests/fixtures/tiny_design.json")
        self.bundle = {
            "native_seq_item": "NATIVE_ITEM_DECLARATION",
            "components": {"seq_item": "NATIVE_ITEM_DECLARATION bit rvp_drive_payload;"},
            "fingerprint": "frozen", "sequences": ["COMPILED_BASELINE"],
            "structured_spec": {"notes": "DERIVED_SPEC"},
            "blueprint": {"component_tasks": "STAGE1_PLAN", "data_contracts": {
                "seq_item_fields": [{"name": "payload", "is_rand": True}]},
                "transaction_contract": {"timing": "DRIVER_OWNS_TIMING"}},
            "protocol_flows": {"bus_field_mapping": None, "init_flow": "EXACT_PROTOCOL"},
            "initial_dsl": {"module_name": self.design.top, "sequences": [{"name": "EXACT_BASELINE"}]},
        }
        self.feedback = {"gaps": [{"type": "toggle", "direction": "1->0", "signal": "payload"}],
                         "bins": {"toggle": [16, 15]}, "formal_environment": {"past_history_supported": False}}

    def test_projection_is_immutable_and_preserves_protocol_baseline_and_all_gaps(self):
        original = deepcopy((self.feedback, self.bundle))
        feedback = paired_feedback(self.feedback, self.bundle)
        self.assertEqual((self.feedback, self.bundle), original)
        self.assertEqual({k: feedback[k] for k in self.feedback}, self.feedback)
        shared = feedback["shared_context"]
        self.assertEqual(shared['actual_seq_item_declaration'], 'NATIVE_ITEM_DECLARATION')
        self.assertNotIn('rvp_drive_payload', json.dumps(feedback))
        for key in ("protocol_flows", "initial_dsl"):
            self.assertEqual(shared[key], self.bundle[key])
        for key in ("sequences", "structured_spec", "blueprint"):
            self.assertEqual(shared["omitted_derived_artifacts"][key], fingerprint(self.bundle[key]))
        for omitted in ("COMPILED_BASELINE", "DERIVED_SPEC", "STAGE1_PLAN"):
            self.assertNotIn(omitted, json.dumps(feedback))
        shared["initial_dsl"]["sequences"].clear()
        self.assertEqual((self.feedback, self.bundle), original)

    def test_haven_stays_inline_while_rvprobe_reads_same_evidence_on_demand(self):
        feedback = paired_feedback(self.feedback, self.bundle)
        template = "\n".join("{" + key + "}" for key in (
            "module_name", "existing_dsl_json", "protocol_flows_json", "coverage_gaps_json",
            "bus_field_mapping_json", "seq_item_fields_json"))
        existing = deepcopy(self.bundle["initial_dsl"])
        existing["sequences"].append({"name": "HAVEN_NEW_HISTORY"})
        haven = build_haven_prompt(template, self.design, feedback, existing, self.bundle["initial_dsl"], {})
        rvprobe = build_prompt([], self.design.sources[0], "30s", design=self.design,
                               coverage_feedback=feedback, skill_context=True)
        for prompt in (haven, rvprobe):
            self.assertIn(self.design.context, prompt)
        self.assertIn(BATCH_INSTRUCTION, haven)
        self.assertIn(RVPROBE_BATCH_INSTRUCTION, rvprobe)
        self.assertNotIn("complete UT/DSL", rvprobe)
        self.assertIn(prompt_json(feedback), haven)
        self.assertIn(design_evidence(self.design), haven)
        self.assertNotIn(INSTRUCTION, haven)
        self.assertNotIn('read_context', haven)
        self.assertNotIn(prompt_json(feedback), rvprobe)
        self.assertNotIn(design_evidence(self.design), rvprobe)
        self.assertIn(INSTRUCTION, rvprobe)
        for sentinel in ("EXACT_BASELINE", "EXACT_PROTOCOL", "DRIVER_OWNS_TIMING"):
            self.assertEqual(haven.count(sentinel), 1)
            self.assertNotIn(sentinel, rvprobe)
        self.assertIn(io_contract(self.design), rvprobe)
        self.assertEqual(haven.count("HAVEN_NEW_HISTORY"), 1)
        self.assertNotIn("HAVEN_NEW_HISTORY", rvprobe)
        contexts = [TaskContext(self.design, feedback, {'sequences': existing['sequences'][1:]}),
                    TaskContext(self.design, feedback)]
        for topic in ('coverage', 'environment', 'baseline'):
            self.assertEqual(*(c.dispatch('read_context', {'topic': topic}) for c in contexts))
        self.assertEqual(*(c.dispatch('list_rtl', {}) for c in contexts))
        self.assertIn('HAVEN_NEW_HISTORY', contexts[0].topics['history'])
        self.assertNotIn('HAVEN_NEW_HISTORY', contexts[1].topics['history'])
        self.assertNotIn('EXACT_PROTOCOL', contexts[0].topics['environment'])
        self.assertNotIn('DRIVER_OWNS_TIMING', contexts[0].topics['environment'])
        self.assertNotIn('EXACT_BASELINE', contexts[0].topics['baseline'])
        self.assertIn('bundle_fingerprint', contexts[0].topics['baseline'])
        existing["sequences"].reverse()
        with self.assertRaisesRegex(ValueError, "shared baseline"):
            build_haven_prompt(template, self.design, feedback, existing, self.bundle["initial_dsl"], {})

    def test_no_design_name_heuristics(self):
        changed = deepcopy(self.bundle)
        changed["blueprint"]["module_name"] = "arbitrary_other_design"
        left, right = common_context(self.bundle), common_context(changed)
        left.pop("omitted_derived_artifacts")
        right.pop("omitted_derived_artifacts")
        self.assertEqual(left, right)

    def test_initialization_and_handshake_guidance_has_no_benchmark_solution(self):
        self.assertIn('uninitialized storage',BATCH_INSTRUCTION)
        self.assertIn('actual handshake',BATCH_INSTRUCTION)
        self.assertIn('Do not replace',BATCH_INSTRUCTION)
        for answer in ('0x63','UART','FIFO','mosi','lsr_','timer'):
            self.assertNotIn(answer,BATCH_INSTRUCTION)

    def test_same_small_intent_batch_budget_for_both_backends(self):
        feedback=paired_feedback(self.feedback,self.bundle)
        self.assertEqual(feedback['intent_batch_limit'],MAX_INTENTS)
        self.assertEqual(MAX_INTENTS,4)
        self.assertIn('one Gen label per intent',BATCH_INSTRUCTION)
        self.assertIn('one DSL sequence per intent',BATCH_INSTRUCTION)

    def test_fixed_memory_configuration_is_not_hidden_from_either_author(self):
        self.bundle['blueprint']['bfm_configs']=[{'protocol':'wishbone_slave','signals':{},
            'params':{'mem_depth':1024,'addr_lsb':2}}]
        context=common_context(self.bundle)
        self.assertEqual(context['bfm_configuration'],self.bundle['blueprint']['bfm_configs'])
        context['bfm_configuration'][0]['params']['mem_depth']=1
        self.assertEqual(self.bundle['blueprint']['bfm_configs'][0]['params']['mem_depth'],1024)

    def test_only_public_parameters_not_bfm_body_are_exposed(self):
        source='interface memory_bfm #(parameter DATA_WIDTH=32, parameter MEM_DEPTH=1024)(input clk); initial secret_scenario(); endinterface'
        parameters=public_bfm_parameters({'bfm_memory':source})
        self.assertIn('MEM_DEPTH=1024',parameters['bfm_memory'])
        self.assertNotIn('secret_scenario',str(parameters))

    def test_environment_metadata_is_shared_without_scenario_answers(self):
        self.bundle['replay'] = {'environment':{'clocks':[{'port':'clock','period_ps':10000}]}}
        self.bundle['blueprint']['sequence_dispatch'] = {'primary':'bus','secondary':['side']}
        result = common_context(self.bundle)
        self.assertEqual(result['trusted_environment'],self.bundle['replay']['environment'])
        self.assertEqual(result['sequence_dispatch'],self.bundle['blueprint']['sequence_dispatch'])
