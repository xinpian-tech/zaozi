"""Offline contract regressions: no model, subprocess, solver or simulation calls."""
from copy import deepcopy
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

from cycle_replay import frame, load_config
import coverage_flow as paired
from haven_shared import (check_sequence_set, checkout_hashes, compact_feedback,
                          coverage_progress, coverage_score, install_cycle_transport,
                          repair_components, repair_direct_handshake, render_witness_sequence, stop_reason)

FIXTURES = Path(__file__).parent / "tests/fixtures"


def coverage(line=90, toggle=60, cond=80, branch=80):
    bins = {"line": [100, line], "toggle": [100, toggle], "cond": [100, cond], "branch": [100, branch]}
    percent, score = coverage_score(bins)
    return {"bins": bins, "percent": percent, "score": score, "modules": ["tiny_external"],
            "uncovered": [{"type": "toggle", "signal": "payload", "direction": "0->1"}]}


def sequence(name):
    return f"class {name} extends uvm_sequence #(tiny_external_seq_item); endclass"


def components():
    return {
        "seq_item": "class tiny_external_seq_item extends uvm_sequence_item;\nbit [7:0] payload;\nbit valid;\nendclass",
        "interface": "interface tiny_external_if(input logic clk, input logic rst);\nlogic [7:0] payload,result;\nlogic valid,done;\nendinterface",
        "top": "module tiny_external_top;\nlogic clk;\nlogic rst;\ntiny_external_if vif(clk, rst);\ntiny_external dut(.clk(clk), .rst(rst));\nendmodule",
        "driver": """class tiny_external_driver extends uvm_driver #(tiny_external_seq_item);
  task run_phase(uvm_phase phase);
    tiny_external_seq_item item;
    forever begin
      seq_item_port.get_next_item(item);
      drive_item(item);
      seq_item_port.item_done();
    end
  endtask
  task drive_item(tiny_external_seq_item item);
    @(posedge vif.clk);
    vif.payload <= item.payload;
    vif.valid <= item.valid;
    begin
      int _hs_cnt = 0;
      while (vif.done !== 1'b1 && _hs_cnt < 500) begin
        @(posedge vif.clk);
        _hs_cnt++;
      end
    end
  endtask
endclass""",
        "monitor": "class monitor;\ntask run(); txn.result = vif.result; txn.done = vif.done; endtask\nendclass",
        "pkg": "package tiny_external_pkg; endpackage",
        "test": 'class tiny_external_test; task run_phase(); `uvm_warning("SEQ_TIMEOUT", "timed out") phase.drop_objection(this); endtask endclass',
    }


class CoveragePolicyTest(unittest.TestCase):
    def test_no_line_residual_still_builds_prompt_for_toggle_gap(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            report = root / "modinfo.txt"
            report.write_text("=" * 72 + "\nModule : tiny_external\n" + "=" * 72 + "\n  17 1/1 covered_assignment\n")
            feedback = root / "feedback.json"
            feedback.write_text(json.dumps(compact_feedback(coverage(line=100))))
            with patch.object(paired.generation, "invoke", side_effect=AssertionError("no model")), contextlib.redirect_stdout(io.StringIO()):
                rc = paired.generation.main(["--modinfo", str(report), "--design", str(FIXTURES / "tiny_design.json"),
                     "--feedback-file", str(feedback), "--out", str(root / "prompt"), "--prompt-only", "--rag", "off"])
            self.assertEqual(rc, 0)
            prompt = (root / "prompt/attempt-1/prompt.txt").read_text()
            self.assertNotIn("0->1", prompt)
            self.assertIn("read_context", prompt)
            self.assertIn("Do not stop merely because all lines are covered", prompt)
            from task_context import TaskContext
            from sequence_framework import load_design
            context = TaskContext(load_design(FIXTURES / 'tiny_design.json'), json.loads(feedback.read_text()))
            self.assertIn('0->1', context.dispatch('read_context', {'topic': 'coverage'})['text'])
            manifest = json.loads((root / 'prompt/manifest.json').read_text())
            self.assertEqual(manifest['task_access']['topics'], context.record()['topics'])

    def test_toggle_progress_counts_with_all_lines_covered(self):
        first, second = coverage(line=100), coverage(line=100, toggle=64)
        self.assertIsNone(stop_reason(first, second, 1, 3))
        self.assertEqual(coverage_progress(first, second)["bin_gains"]["line"], 0)

    def test_conditions_and_branches_also_count(self):
        for changed in (coverage(cond=84), coverage(branch=84)):
            self.assertIsNone(stop_reason(coverage(), changed, 1, 3))

    def test_mean_is_not_weighted_by_number_of_bins(self):
        self.assertEqual(coverage_score({"line": [1000, 1000], "toggle": [10, 0]})[1], 50)

    def test_zero_bin_fsm_does_not_count_as_zero_coverage(self):
        self.assertEqual(coverage_score({"line": [100, 90], "fsm": [0, 0]})[1], 90)

    def test_stops_only_after_measured_plateau_or_budget(self):
        first = coverage()
        self.assertIsNone(stop_reason(None, first, 0, 3))
        self.assertEqual(stop_reason(first, first, 1, 3), "coverage_stalled")
        self.assertEqual(stop_reason(first, coverage(toggle=90), 3, 3), "round_budget")
        self.assertEqual(stop_reason(first, coverage(100, 100, 100, 100), 1, 3), "coverage_target")

    def test_regression_retained_not_falsely_counted_as_gain(self):
        self.assertLess(coverage_progress(coverage(), coverage(toggle=50))["score_gain"], 0)

    def test_reject_denominator_and_metric_changes(self):
        changed = coverage()
        changed["bins"]["toggle"][0] += 1
        with self.assertRaises(ValueError):
            coverage_progress(coverage(), changed)
        changed = coverage()
        changed["bins"].pop("line")
        with self.assertRaises(ValueError):
            coverage_progress(coverage(), changed)

    def test_feedback_preserves_direction_and_deduplicates(self):
        row = coverage()
        row["uncovered"] *= 120
        feedback = compact_feedback(row)
        self.assertEqual(len(feedback["gaps"]), 1)
        self.assertEqual(feedback["gaps"][0]["direction"], "0->1")


class PairedLoopTest(unittest.TestCase):
    def test_native_search_exhaustion_is_not_reported_as_shared_setup_failure(self):
        from native_witness_selection import NativeWitnessExhaustion
        calls=[]
        def generate(arm, rd, *args):
            calls.append(arm)
            raise NativeWitnessExhaustion(0, 4, 16, rd/'native-witness-search/goal')
        with tempfile.TemporaryDirectory() as directory:
            result=paired.paired_loop({'sequences':[sequence('base')],'fingerprint':'x'},
                directory, lambda *args: coverage(), generate, runtime_repairs=3)
        self.assertEqual(calls,['haven','rvprobe'])
        for arm in result['arms'].values():
            self.assertEqual(arm['stop_reason'],'native_witness_search_exhausted')
            self.assertEqual(arm['diagnostics']['attempted'],16)
            self.assertEqual(arm['rounds'],[])
            self.assertEqual(arm['final'],result['baseline'])

    def test_replay_setup_failure_never_requests_a_new_ut(self):
        from replay_failures import ReplayInfrastructureFailure
        calls=[]
        def simulate(path,*args):
            if path.name!='baseline': raise ReplayInfrastructureFailure('lowered top metadata mismatch')
            return coverage()
        def generate(arm,*args):
            calls.append(arm)
            return {'sequences':[sequence(arm)],'frames':[]}
        with tempfile.TemporaryDirectory() as directory:
            result=paired.paired_loop({'sequences':[sequence('base')],'fingerprint':'x'},
                directory,simulate,generate,runtime_repairs=3)
        self.assertEqual(calls,['haven','rvprobe'])
        for arm in result['arms'].values():
            self.assertEqual(arm['rounds'],[])
            self.assertEqual(arm['stop_reason'],'shared_contract_requires_validation')

    def test_unreplayable_contract_failure_does_not_request_model_repair(self):
        calls = []
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory)/'sim.log'
            log.write_text('UVM_FATAL driver.sv(5) @ 12: driver [WITNESS_X] actual=xx expected=12 mask=ff')
            def simulate(path, *args):
                if path.name != 'baseline':
                    raise paired.SimulationFailure('unknown output', log)
                return coverage()
            def generate(arm, *args):
                calls.append(arm)
                return {'sequences':[sequence(arm)],'frames':[]}
            result = paired.paired_loop({'sequences':[sequence('base')],'fingerprint':'x'},
                directory, simulate, generate, runtime_repairs=3)
        self.assertEqual(calls, ['haven','rvprobe'])
        for arm in result['arms'].values():
            self.assertEqual(arm['rounds'], [])
            self.assertEqual(arm['final'], result['baseline'])
            self.assertEqual(arm['stop_reason'], 'shared_contract_requires_validation')
            self.assertEqual(len(arm['rejected_candidates']), 1)

    def test_runtime_repair_keeps_failed_candidates_out_of_both_arms(self):
        bundle = {'sequences':[sequence('base')], 'fingerprint':'x'}
        calls = []
        def simulate(path, sources, frames):
            if any('bad' in s for s in sources):
                raise ValueError('measured runtime failure')
            return {**coverage(toggle=70 if len(sources)>1 else 60),'modinfo':str(path/'modinfo.txt')}
        def generate(arm, rd, feedback, existing, ordinal):
            repair = '-repair-' in rd.name
            self.assertEqual(existing,bundle['sequences'])
            self.assertEqual('runtime_failure' in feedback,repair)
            self.assertTrue(feedback['modinfo'].endswith('baseline/modinfo.txt'))
            calls.append((arm,repair))
            return {'sequences':[sequence(arm+('_good' if repair else '_bad'))], 'frames':[]}
        with tempfile.TemporaryDirectory() as directory:
            result = paired.paired_loop(bundle,directory,simulate,generate,rounds=1)
        self.assertEqual(result['status'],'completed')
        self.assertEqual(calls,[('haven',False),('haven',True),('rvprobe',False),('rvprobe',True)])
        for arm in result['arms'].values():
            self.assertEqual(arm['sequence_count'],2)
            self.assertEqual(len(arm['rejected_candidates']),1)
            self.assertEqual(len(arm['rounds']),1)

    def test_exhausted_runtime_repair_is_failure_not_a_coverage_result(self):
        def simulate(path,*args):
            if path.name != 'baseline': raise ValueError('bad witness')
            return coverage()
        with tempfile.TemporaryDirectory() as directory:
            result = paired.paired_loop({'sequences':[sequence('base')],'fingerprint':'x'},directory,
                simulate,lambda arm,*args:{'sequences':[sequence(arm)],'frames':[]})
        self.assertEqual(result['status'],'failed')
        for arm in result['arms'].values():
            self.assertEqual(len(arm['rejected_candidates']),2)
            self.assertEqual(arm['rounds'],[])

    def test_failed_dsl_does_not_enter_accepted_history(self):
        initial = {'sequences':[{'name':'baseline'}]}
        attempts = [(['bad'],[{'name':'bad'}]),(['good'],[{'name':'good'}])]
        result = paired.accepted_haven_dsl(initial,attempts,['baseline','good'])
        self.assertEqual(result['sequences'],[{'name':'baseline'},{'name':'good'}])
        self.assertEqual(initial['sequences'],[{'name':'baseline'}])

    def test_one_baseline_both_arms_same_prefix_isolated_additions(self):
        baseline = [sequence("initial_random"), sequence("initial_toggle")]
        bundle = {"sequences": baseline, "fingerprint": "shared"}
        simulations, feedbacks = [], []
        def simulate(path, sequences, frames):
            simulations.append((str(path), list(sequences)))
            return coverage(line=100, toggle=60 + 4 * (len(sequences) - len(baseline)))
        def generate(arm, rd, feedback, existing, ordinal):
            live = json.loads((rd.parents[1]/'progress.json').read_text())['arms'][arm]
            self.assertEqual(live['status'],'running')
            self.assertEqual(live['active_round'],int(rd.name.split('-')[1]))
            self.assertIn('started_utc',live)
            feedbacks.append((arm, deepcopy(feedback)))
            return {"sequences": [sequence(f"{arm}_{rd.name.replace('-', '_')}")], "frames": []}
        with tempfile.TemporaryDirectory() as directory:
            result = paired.paired_loop(bundle, directory, simulate, generate, rounds=2)
        self.assertEqual(len(simulations), 5)
        self.assertEqual(result["status"], "completed")
        self.assertEqual(feedbacks[0][1], feedbacks[2][1])
        for path, seqs in simulations:
            self.assertEqual(seqs[:2], baseline)
            if "/rvprobe/" in path:
                self.assertNotIn("class haven_", "\n".join(seqs))
        self.assertEqual(bundle["sequences"], baseline)
        self.assertTrue(all(r["stop_reason"] == "round_budget" for r in result["arms"].values()))

    def test_no_premature_stop_before_new_sequence_simulation(self):
        calls = []
        def simulate(path, seqs, frames):
            calls.append(path)
            return coverage()
        with tempfile.TemporaryDirectory() as directory:
            result = paired.paired_loop({"sequences": [sequence("base")], "fingerprint": "x"}, directory,
                simulate, lambda arm, *a: {"sequences": [sequence(arm)], "frames": []})
        self.assertEqual(len(calls), 3)
        self.assertEqual(result["arms"]["haven"]["stop_reason"], "coverage_stalled")

    def test_failed_arm_is_explicit_other_arm_still_runs(self):
        def generate(arm, *args):
            if arm == "haven":
                raise ValueError("compile failed")
            return {"stop": "no useful new goal"}
        with tempfile.TemporaryDirectory() as directory:
            result = paired.paired_loop({"sequences": [sequence("base")], "fingerprint": "x"}, directory,
                lambda *a: coverage(), generate)
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["arms"]["haven"]["status"], "failed")
        self.assertEqual(result["arms"]["rvprobe"]["stop_reason"], "model_stop")

    def test_duplicate_cannot_overwrite_initial_sequence(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            check_sequence_set([sequence("base"), sequence("base")])


class SharedBenchTest(unittest.TestCase):
    def setUp(self):
        self.design, self.config = load_config(FIXTURES / "tiny_replay.json")

    def test_can_style_missing_output_field_repaired_from_io_only(self):
        before = components()
        fixed, changes = repair_components(before, [vars(p) for p in self.design.data_ports])
        self.assertIn("bit [7:0] result;", fixed["seq_item"])
        self.assertIn("bit [0:0] done;", fixed["seq_item"])
        self.assertNotIn("rand bit [7:0] result", fixed["seq_item"])
        self.assertEqual(before, components())
        again, changes2 = repair_components(fixed, [vars(p) for p in self.design.data_ports])
        self.assertEqual(again, fixed)
        self.assertEqual(changes2, [])

    def test_wrong_output_width_and_rand_are_fixed(self):
        before = components()
        before["seq_item"] = before["seq_item"].replace("endclass", "rand bit [31:0] result;\nendclass")
        fixed, _ = repair_components(before, [vars(p) for p in self.design.data_ports])
        self.assertIn("logic [7:0] result;", fixed["seq_item"])
        self.assertNotIn("rand bit [31:0] result", fixed["seq_item"])

    def test_unknown_typedef_is_not_redeclared(self):
        before = components()
        before["seq_item"] = before["seq_item"].replace("bit [7:0] payload;", "payload_t payload;")
        with self.assertRaisesRegex(ValueError, "typed field"):
            repair_components(before, [vars(p) for p in self.design.data_ports])

    def test_sdram_style_config_and_clock_have_one_owner(self):
        before = components()
        before["driver"] = "vif.cfg_enable <= item.cfg_enable;\nvif.sdram_clk <= item.sdram_clk;\nvif.payload <= item.payload;"
        fixed, _ = repair_components(before, [], owned_signals=["cfg_enable"], clock_connections={"sdram_clk": "clk"})
        self.assertNotIn("vif.cfg_enable", fixed["driver"])
        self.assertNotIn("vif.sdram_clk", fixed["driver"])
        self.assertIn("vif.payload", fixed["driver"])
        self.assertIn("assign vif.sdram_clk = clk;", fixed["top"])
        with self.assertRaisesRegex(ValueError, "not declared"):
            repair_components(before, [], clock_connections={"sdram_clk": "unknown_clock"})

    def test_handshake_does_not_consume_stale_done_or_leave_request_high(self):
        fixed, changes = repair_direct_handshake(components(), self.design, self.config)
        driver = fixed["driver"]
        self.assertIn("@(negedge vif.clk)", driver)
        self.assertIn("#1; // inspect the response", driver)
        self.assertIn("vif.valid = 0;", driver)
        self.assertIn('`uvm_fatal("HANDSHAKE_TIMEOUT"', driver)
        self.assertTrue(changes)

    def test_shared_constraint_repairs_require_exact_original(self):
        before = components()
        before["seq_item"] += "\nconstraint c { valid == 1; }"
        fixed, changes = repair_components(before, [], item_constraint_replacements={
            "constraint c { valid == 1; }": "constraint c { valid inside {[0:1]}; }"})
        self.assertIn("valid inside {[0:1]}", fixed["seq_item"])
        self.assertIn("valid == 1", before["seq_item"])
        self.assertTrue(changes)
        with self.assertRaises(ValueError):
            repair_components(before, [], item_constraint_replacements={"absent": "replacement"})

    def test_handshake_deasserts_all_request_controls(self):
        config = {**self.config, "request": {"valid": 1, "enable": 1},
                  "idle": {**self.config["idle"], "enable": 0}}
        fixed, _ = repair_direct_handshake(components(), self.design, config)
        self.assertIn("vif.valid = 0;", fixed["driver"])
        self.assertIn("vif.enable = 0;", fixed["driver"])
        self.assertIn("(item.valid == 1) || (item.enable == 1)", fixed["driver"])

    def test_event_metadata_does_not_invent_a_request_protocol(self):
        before = components()
        config = {k:v for k,v in self.config.items() if k != 'request'}
        fixed, changes = repair_direct_handshake(before, self.design, config)
        self.assertEqual(fixed,before)
        self.assertEqual(changes,[])

    def test_raw_mode_uses_shared_driver_but_no_handshake_wait(self):
        original = components()
        fixed = install_cycle_transport(original, self.design, self.config, "driver")
        self.assertIn("if (item.rvp_raw)", fixed["driver"])
        task = fixed["driver"].split("task rvp_drive_cycle", 1)[1]
        self.assertNotIn("while", task)
        self.assertIn("@(vif.rvp_sample)", task)
        self.assertIn("default input #1step", fixed["interface"])
        self.assertIn("rst |vif.rvp_reset_request", fixed["top"])
        self.assertIn("bit rvp_raw = 0", fixed["seq_item"])
        self.assertEqual(original, components())
        with self.assertRaisesRegex(ValueError, "already installed"):
            install_cycle_transport(fixed, self.design, self.config, "driver")

    def test_witness_compilation_is_explicit_assignments_no_randomize(self):
        frames = [frame("reset", self.config["idle"]),
                  frame("witness", {"payload": 42, "valid": 1}, expected={"result": [3, 15]})]
        source = render_witness_sequence(self.design, frames, "sample", 10)
        self.assertNotIn("randomize", source)
        self.assertIn("item.rvp_ordinal = 10", source)
        self.assertIn("item.rvp_ordinal = 11", source)
        self.assertIn("item.rvp_mask_result = 8'hf", source)
        self.assertIn("item.rvp_mask_done = 1'h0", source)

    def test_changed_template_fails_instead_of_silent_partial_patch(self):
        before = components()
        before["driver"] = "unsupported_driver"
        with self.assertRaisesRegex(ValueError, "hook"):
            install_cycle_transport(before, self.design, self.config, "driver")


class BundleTest(unittest.TestCase):
    def fixture(self, root):
        stage = root / "stage1"
        ir, final = stage / "ir", stage / "final"
        ir.mkdir(parents=True)
        final.mkdir()
        design, config = load_config(FIXTURES / "tiny_replay.json")
        blueprint = {"module_name": design.top, "clock": {"port": "clk"}, "reset": {"name": "rst", "level": "high"},
            "io_specification": {"clocks": [{"name": "clk"}], "inputs": [vars(p) for p in design.data_ports if p.direction == "input"],
                                  "outputs": [vars(p) for p in design.data_ports if p.direction == "output"]}}
        for name, obj in (("phase2b_blueprint", blueprint), ("phase1_structured_spec", {}),
                          ("phase0_config", {"root": str(FIXTURES.resolve()), "rtl_files": ["tiny_external.v"]}),
                          ("phase5_compile_check_result", {"compile_passed": True}),
                          ("phase2b_protocol_flows", {}), ("phase4b_dsl_sequences", {"module_name": design.top, "sequences": []})):
            (ir / f"{name}.json").write_text(json.dumps(obj))
        for name, code in components().items():
            (final / f"{design.top}_{name}.sv").write_text(code)
        (final / "sequence_1.sv").write_text(sequence("initial"))
        haven = root / "haven"
        (haven / "src/haven/eda").mkdir(parents=True)
        (haven / "src/haven/eda/urg_utils.py").write_text("# fake source identity fixture\n")
        return stage, haven

    def test_prepare_never_calls_models_or_eda_and_shares_original_sequences(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stage, haven = self.fixture(root)
            with patch.object(paired, "run_process", side_effect=AssertionError("must not execute")):
                result = paired.prepare(stage, haven, FIXTURES / "tiny_replay.json", root / "bundle")
            self.assertEqual(result["sequences"], [sequence("initial")])
            self.assertIn('`uvm_fatal("SEQ_TIMEOUT"', result["components"]["test"])
            self.assertEqual(checkout_hashes(haven), result["haven_sha256"])
            with self.assertRaisesRegex(ValueError, "must be new"):
                paired.prepare(stage, haven, FIXTURES / "tiny_replay.json", root / "bundle")

    def test_stage2_history_is_not_initial_baseline(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stage, haven = self.fixture(root)
            (stage / "ir/phase7_gaps.json").write_text("{}")
            with self.assertRaisesRegex(ValueError, "Stage-2"):
                paired.prepare(stage, haven, FIXTURES / "tiny_replay.json", root / "bundle")

    def test_named_active_driver_with_passive_observer_is_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stage, haven = self.fixture(root)
            bp_path = stage / 'ir/phase2b_blueprint.json'
            bp = json.loads(bp_path.read_text())
            bp['topology'] = {'agents': [{'name':'bus','mode':'active'}, {'name':'watch','mode':'passive'}]}
            bp_path.write_text(json.dumps(bp))
            old = stage/'final/tiny_external_driver.sv'
            old.rename(stage/'final/tiny_external_bus__driver.sv')
            monitor = 'class watch_monitor; task sample(); txn.done = vif.done; endtask endclass'
            (stage/'final/tiny_external_watch__monitor.sv').write_text(monitor)
            (stage/'final/tiny_external_watch__agent.sv').write_text('class watch_agent; endclass')
            bundle = paired.prepare(stage,haven,FIXTURES/'tiny_replay.json',root/'bundle')
            self.assertEqual(bundle['transport_driver'], 'bus__driver')
            self.assertEqual(bundle['components']['watch__monitor'], monitor)
            self.assertIn('rvp_drive_cycle', bundle['components']['bus__driver'])
            self.assertEqual(bundle['blueprint']['topology'], bp['topology'])
            for extra in ({'watch__driver':'class driver; endclass'},
                          {'watch__sequencer':'class sequencer; endclass'},
                          {'watch__monitor':'class monitor; task run(); vif.done <= 0; endtask endclass'}):
                with self.assertRaises(ValueError):
                    paired.transport_driver(bp, {**bundle['components'], **extra})

    def test_unversioned_axi_item_cannot_enter_new_paired_experiment(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stage, haven = self.fixture(root)
            path = stage / "ir/phase2b_blueprint.json"
            blueprint = json.loads(path.read_text())
            blueprint['protocol_flows'] = {'bus_field_mapping': {
                'awvalid': 'aw', 'wvalid': 'w', 'arvalid': 'ar'}}
            path.write_text(json.dumps(blueprint))
            with self.assertRaisesRegex(ValueError, 'no verified transaction-field ownership'):
                paired.prepare(stage, haven, FIXTURES / "tiny_replay.json", root / "bundle")
            self.assertFalse((root / 'bundle').exists())

    def test_active_master_metadata_keeps_components_and_rejects_passive(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stage, haven = self.fixture(root)
            path = stage / "ir/phase2b_blueprint.json"
            blueprint = json.loads(path.read_text())
            accepted = []
            for mode in ("active", "active_master", "passive", "active_slave", "unknown"):
                blueprint["topology"] = {"agents": [{"name": "bus_agent", "mode": mode}]}
                path.write_text(json.dumps(blueprint))
                if mode in ("active", "active_master"):
                    result = paired.prepare(stage, haven, FIXTURES / "tiny_replay.json", root / mode)
                    self.assertEqual(result["blueprint"]["topology"]["agents"][0]["mode"], mode)
                    accepted.append(result)
                else:
                    with self.assertRaisesRegex(ValueError, "active primary agent"):
                        paired.prepare(stage, haven, FIXTURES / "tiny_replay.json", root / mode)
            self.assertEqual(accepted[0]["components"], accepted[1]["components"])
            self.assertEqual(accepted[0]["sequences"], accepted[1]["sequences"])

    def test_reject_changed_ports_or_multiclock(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stage, haven = self.fixture(root)
            path = stage / "ir/phase2b_blueprint.json"
            original = json.loads(path.read_text())
            for mutate in (lambda b: b["io_specification"]["clocks"].append({"name": "clk2"}),
                           lambda b: b["io_specification"]["inputs"].pop()):
                changed = deepcopy(original)
                mutate(changed)
                path.write_text(json.dumps(changed))
                with self.assertRaises(ValueError):
                    paired.prepare(stage, haven, FIXTURES / "tiny_replay.json", root / "bundle")


@unittest.skipUnless(os.environ.get("HAVEN_TEST_ROOT"), "optional local HAVEN template/codegen regression")
class NativeHavenTest(unittest.TestCase):
    """Actual HAVEN template and DSL codegen only: never compile or simulate."""
    def test_native_renderer_and_codegen_with_shared_hook(self):
        haven = Path(os.environ["HAVEN_TEST_ROOT"])
        paired.load_haven(haven)
        from haven.graph.rendering import render_templates
        from haven.dsl.schema import DSLSequenceSet
        from haven.dsl.codegen import DSLCodegen
        with tempfile.TemporaryDirectory() as tmp:
            stage, _ = BundleTest().fixture(Path(tmp))
            bundle = paired.prepare(stage, haven, FIXTURES / "tiny_replay.json", Path(tmp) / "bundle")
            state = {"components": deepcopy(bundle["components"]), "sequences": bundle["sequences"],
                     "blueprint": bundle["blueprint"], "task": {"rtl_files": [str(FIXTURES / "tiny_external.v")]}}
            with patch("subprocess.Popen", side_effect=AssertionError("no EDA or subprocess")):
                render_templates(state)
                dsl = DSLSequenceSet.model_validate({"module_name": "tiny_external", "sequences": [
                    {"name": "native_gap", "description": "synthetic regression", "steps": [
                        {"type": "randomize_send", "name": "send", "repeat": 4, "constraints": ["valid == 1"]}]}]})
                codes = DSLCodegen().generate(dsl, seq_item_code=bundle["components"]["seq_item"])
            self.assertEqual(len(codes), 1)
            self.assertEqual(check_sequence_set(codes), ["native_gap"])
            self.assertIn("sequence_1.sv", state["components"]["pkg"])
            for key in ("driver", "interface", "seq_item", "top"):
                self.assertEqual(state["components"][key], bundle["components"][key])

    def test_haven_backend_records_raw_reply_and_codegen_filters_without_request(self):
        haven = Path(os.environ["HAVEN_TEST_ROOT"])
        paired.load_haven(haven)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stage, _ = BundleTest().fixture(root)
            bundle = paired.prepare(stage, haven, FIXTURES / "tiny_replay.json", root / "bundle")
            design, replay = load_config(FIXTURES / "tiny_replay.json")
            args = SimpleNamespace(haven_root=haven, model="fake", temperature=0.3, timeout=1,
                                   request_retries=1, attempts=1, env_file=None)
            backend = paired.Backends(bundle, design, replay, args)
            reply = {"module_name": design.top, "sequences": [{"name": "native_gap", "description": "synthetic", "steps": [
                {"type": "randomize_send", "name": "send", "repeat": 4, "constraints": []}]}]}
            def fake_request(command, **kwargs):
                ad = Path(command[command.index("--directory") + 1])
                (ad / "response.txt").write_text(json.dumps(reply))
            with patch.object(paired, "run_process", side_effect=fake_request), \
                 patch("subprocess.Popen", side_effect=AssertionError("no external process")):
                from prompt_context import MAX_INTENTS
                feedback = {**compact_feedback(coverage()), 'intent_batch_limit':MAX_INTENTS}
                result = backend.haven(root / "round", feedback, bundle["sequences"])
            self.assertEqual(len(result["sequences"]), 1)
            self.assertIn("codegen_filters", result["metadata"])
            self.assertEqual(json.loads((root / "round/attempt-1/response.txt").read_text()), reply)

    def test_native_simulation_adapter_uses_fixed_seed_and_dut_scope_with_fake_tools(self):
        haven = Path(os.environ["HAVEN_TEST_ROOT"])
        paired.load_haven(haven)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stage, _ = BundleTest().fixture(root)
            bundle = paired.prepare(stage, haven, FIXTURES / "tiny_replay.json", root / "bundle")
            design, _ = load_config(FIXTURES / "tiny_replay.json")
            def fake_urg(*, cwd, **kwargs):
                report = Path(cwd) / f"{design.top}_urgReport"
                report.mkdir()
                (report / "modinfo.txt").write_text("="*72 + f"\nModule : {design.top}\n" + "="*72 +
                    f"\nLine Coverage for Module {design.top}\nTOTAL 100 90\n  17 0/1 assign foo\n" +
                    f"\nToggle Coverage for Module {design.top}\nTotal Bits 100 60\n")
            with patch("haven.eda.vcs_utils.vcs_compile", return_value={"ok": True, "log": "fake compile"}), \
                 patch("haven.eda.run_eda_command", return_value=SimpleNamespace(returncode=0, stdout="HAVEN_SHARED_PASS\nUVM_ERROR : 0\nUVM_FATAL : 0\n", stderr="")) as sim, \
                 patch("haven.eda.urg_utils.run_urg", side_effect=fake_urg), \
                 patch("subprocess.Popen", side_effect=AssertionError("no external tools")):
                result = paired.HavenSimulation(bundle, design, {}, 123)(root / "sim", bundle["sequences"], [])
            self.assertIn("+ntb_random_seed=123", sim.call_args.args[1])
            self.assertEqual(result["score"], 75)
            self.assertEqual(result["modules"], [design.top])
            self.assertTrue(result["artifact_sha256"])


if __name__ == "__main__":
    unittest.main()
