#!/usr/bin/env python3
"""Paired HAVEN/rvprobe generation on one frozen HAVEN Stage-1 testbench.

prepare is read/transform only; run explicitly invokes models/EDA. Unit tests use
injected generators/simulators. Never run an experiment just by preparing a bundle.
"""
import argparse
from copy import deepcopy
import importlib
import json
from pathlib import Path
import re
import sys
import time
from types import SimpleNamespace

import sequence_experiment as generation
from cycle_replay import digest, load_config, validate_samples, witness_frames
from haven_shared import (CONTRACT, METRICS, checkout_hashes, check_sequence_set,
                          compact_feedback, coverage_progress, coverage_score,
                          install_cycle_transport, repair_components, replace_once,
                          render_witness_sequence, stop_reason, repair_direct_handshake, repair_axi_response_sampling)
from process_runner import run as run_process
from prompt_context import paired_feedback, POLICY as CONTEXT_POLICY
from task_context import POLICY as RTL_CONTEXT_POLICY, MAX_MODEL_CALLS
from run_records import Records, begin, finish, fingerprint, framework_hashes, save, totals, utc
from sequence_framework import ROOT, identifier
from urg_score import parse, score, _MODULE_SPLIT
from witness_sampling import expand_goals, sampling_options, sampling_policy
from isolated_replay import POLICY as ISOLATION_POLICY


def load_haven(root, expected=None):
    hashes = checkout_hashes(root)
    if expected is not None and hashes != expected:
        raise ValueError("HAVEN checkout changed since shared bundle preparation")
    source = (Path(root) / "src").resolve()
    sys.path.insert(0, str(source))
    module = importlib.import_module("haven")
    if not Path(module.__file__).resolve().is_relative_to(source):
        raise ValueError("a different HAVEN installation was already imported")
    return hashes


def transport_driver(blueprint, components):
    """Select one real driver; additional agents must be non-driving observers."""
    agents = blueprint.get("topology", {}).get("agents", []) or []
    active = [a for a in agents if a.get("mode", "active") != "passive"]
    if agents and (len(active) != 1 or active[0].get("mode", "active") not in ("active", "active_master")):
        raise ValueError("paired transport needs one active primary agent; others must be passive")
    keys = [k for k in components if k == "driver" or k.endswith("__driver")]
    allowed = {"driver"}
    if active:
        allowed.add(identifier(active[0]["name"], "active agent") + "__driver")
    if len(keys) != 1 or keys[0] not in allowed:
        raise ValueError("Stage-1 must contain exactly one matching driver component")
    for agent in agents:
        if agent.get("mode") != "passive":
            continue
        prefix = identifier(agent["name"], "passive agent") + "__"
        if any(prefix + kind in components for kind in ("driver", "sequencer")):
            raise ValueError("passive agent cannot own a driver or sequencer")
        for kind in ("monitor", "agent"):
            if prefix + kind not in components:
                raise ValueError("passive agent is missing its monitor/agent component")
    # Conservative pin-owner check, not a general SystemVerilog security parser.
    for key, source in components.items():
        if key in (keys[0], "top", "interface"):
            continue
        source = re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"', ' ', source, flags=re.S)
        if re.search(r"\bvif\.\w+\s*(?:\[[^\]]+\]\s*)?(?:<=|=(?!=))", source):
            raise ValueError(f"non-driver component {key} writes interface pins")
    return keys[0]


def prepare(stage1, haven_root, replay_config, out, repairs=None, io_width_repairs=None):
    """Capture ONLY a completed Stage-1 run. Refuse historical Stage-2 answers."""
    stage1, out = Path(stage1).resolve(), Path(out).resolve()
    if out.exists():
        raise ValueError("shared bundle output must be new")
    ir = stage1 / "ir"
    if list(ir.glob("phase7*")):
        raise ValueError("input contains Stage-2 artifacts; provide a Stage-1-only run")
    design, replay = load_config(Path(replay_config))
    if design.parameters:
        raise ValueError("paired HAVEN top must use the default RTL parameters")
    stage_config_path = ir / "phase0_config.json"
    stage_config = json.loads(stage_config_path.read_text())
    compile_path = ir / "phase5_compile_check_result.json"
    if not json.loads(compile_path.read_text()).get("compile_passed"):
        raise ValueError("Stage-1 must have passed its compile check before freezing")
    event_mode = bool(replay.get('environment'))
    if not event_mode and any(stage_config.get(k) for k in ("bfm_configs", "extra_resets", "static_signals")):
        raise ValueError("Stage-1 has external pin owners not represented by the current formal environment")
    stage_root = Path(stage_config["root"])
    rtl_paths = [Path(p) if Path(p).is_absolute() else stage_root / p
                 for p in stage_config.get("rtl_files", [])]
    if not rtl_paths and stage_config.get("dut"):
        rtl_paths = [stage_root / stage_config["dut"]]
    if sorted(digest(p) for p in rtl_paths) != sorted(digest(p) for p in design.sources):
        raise ValueError("Stage-1 RTL content differs from the paired design")
    blueprint_path = ir / "phase2b_blueprint.json"
    if not blueprint_path.exists():
        blueprint_path = ir / "phase2_blueprint.json"
    blueprint = json.loads(blueprint_path.read_text())
    if event_mode:
        from environment_contract import verify_shared_environment
        verify_shared_environment(design,replay['environment'],blueprint)
    # Legacy cycle mode is intentionally unchanged. Event mode must pass the
    # elaborated shared-environment contract above, not merely bypass guards.
    agents = blueprint.get("topology", {}).get("agents", []) or []
    io = blueprint.get("io_specification", {})
    if io.get('inouts') or (not event_mode and (blueprint.get("bfm_configs") or len(io.get("clocks", [])) > 1)):
        raise ValueError("paired witness transport currently supports one active agent, one clock, no BFM/inout")
    # HAVEN blueprints can qualify an active initiator as active_master.
    # This is metadata only; retain the compiled agent and all pin-owner checks.
    active = [a for a in agents if a.get("mode", "active") != "passive"]
    if not event_mode and agents and (len(active) != 1 or active[0].get("mode", "active") not in ("active", "active_master")):
        raise ValueError("paired transport needs an active primary agent")
    if blueprint.get("module_name") != design.top or blueprint.get("clock", {}).get("port") != design.clock:
        raise ValueError("HAVEN blueprint DUT/clock disagrees with IO manifest")
    if blueprint.get("reset") != {"name": design.reset, "level": "low" if design.reset_active_low else "high"}:
        raise ValueError("HAVEN reset contract disagrees with formal IO manifest")
    ports = {p.name: (p.direction, p.width) for p in design.data_ports}
    width_changes = []
    for name, old_width in (io_width_repairs or {}).items():
        if name not in ports:
            raise ValueError("width repair names an unknown RTL port")
        direction, width = ports[name]
        entries = [p for p in io.get("inputs" if direction == "input" else "outputs", []) if p["name"] == name]
        if len(entries) != 1 or entries[0]["width"] != old_width:
            raise ValueError("width repair does not match original blueprint")
        entries[0]["width"] = width
        # Keep transaction metadata consistent with the authoritative interface.
        def update_fields(value):
            if isinstance(value, dict):
                if value.get("name") == name and value.get("width") == old_width:
                    value["width"] = width
                for child in value.values():
                    update_fields(child)
            elif isinstance(value, list):
                for child in value:
                    update_fields(child)
        update_fields(blueprint)
        width_changes.append(f"shared blueprint/interface {name}: width {old_width} -> {width} from RTL")
    declared = {p["name"]: (direction, int(p["width"])) for direction, key in (("input", "inputs"), ("output", "outputs")) for p in io.get(key, [])}
    if ports != declared:
        raise ValueError("HAVEN blueprint IO differs from formal IO manifest")
    paths = sorted((stage1 / "final").glob(f"{design.top}_*.sv"))
    components = {p.stem[len(design.top) + 1:]: p.read_text() for p in paths}
    if event_mode:
        load_haven(haven_root)
        from event_transport import select_primary_driver
        driver_key = select_primary_driver(blueprint,components)
    else:
        driver_key = transport_driver(blueprint, components)
    bfm_paths = sorted((stage1/'final').glob('bfm_*.sv'))
    bfm_components = {p.stem:p.read_text() for p in bfm_paths}
    if event_mode and blueprint.get('bfm_configs') and not bfm_components:
        raise ValueError('BFM sources missing from compiled Stage-1')
    if event_mode:
        from event_transport import verify_native_sources
        verify_native_sources(blueprint,components,bfm_components)
    for name, old_width in (io_width_repairs or {}).items():
        components["interface"] = replace_once(components["interface"],
            f"logic [{old_width - 1}:0] {name};", f"logic [{ports[name][1] - 1}:0] {name};")
    for required in ("interface", "seq_item", "top", "test", "pkg", driver_key):
        if required not in components:
            raise ValueError(f"Stage-1 missing component {required}")
    from item_contract import scenario_constraints, sequence_fields
    scenarios=scenario_constraints(components['seq_item'],sequence_fields(blueprint))
    if scenarios:
        raise ValueError('fixed Stage-1 item has legacy scenarios on sequence-owned fields: '+
            ', '.join(b['name'] for b in scenarios)+
            '; normalize them offline in a new shared environment, preserve baseline sources, and revalidate BOTH arms')
    sequences_paths = sorted((stage1 / "final").glob("sequence_*.sv"),
                             key=lambda p: int(p.stem.split("_")[-1]))
    sequences = [p.read_text() for p in sequences_paths]
    if not sequences:
        raise ValueError("Stage-1 contains no initial sequences")
    check_sequence_set(sequences)
    bus = (blueprint.get("protocol_flows") or {}).get("bus_field_mapping") or {}
    if all(bus.get(key) for key in ("awvalid", "wvalid", "arvalid")):
        if (blueprint.get("transaction_contract") or {}).get("version") != "axi4lite-transaction-v1":
            raise ValueError("AXI Stage-1 has no verified transaction-field ownership contract; "
                             "apply the HAVEN component patch and regenerate Stage-1 in a new directory")
        load_haven(haven_root)
        from haven.utils.transaction_contract import apply_transaction_contract, validate_item_contract
        normalized = apply_transaction_contract(blueprint)
        if normalized["data_contracts"] != blueprint["data_contracts"] or normalized["transaction_contract"] != blueprint["transaction_contract"]:
            raise ValueError("AXI Stage-1 transaction contract no longer matches its bus mapping")
        errors = validate_item_contract(components["seq_item"], blueprint["data_contracts"]["seq_item_fields"])
        if errors:
            raise ValueError("Invalid shared AXI transaction item: " + "; ".join(errors))
    repairs = repairs or {}
    if repairs.get("clock_connections") or repairs.get("owned_signals"):
        raise ValueError("multi-clock/static/BFM pin ownership needs a matching formal environment before paired execution")
    item_widths = {}
    if all(bus.get(key) for key in ('cyc','stb','ack','addr')):
        load_haven(haven_root)
        from haven.utils.protocol_driver_renderer import _build_bus_context
        # Sequence items carry byte addresses, while the physical RTL port may
        # omit low address bits. Do not truncate items back to physical width.
        item_widths[bus['addr']] = _build_bus_context(blueprint)['addr_width']
    fixed, changes = repair_components(components, [vars(p) for p in design.data_ports], item_widths=item_widths, **repairs)
    changes = width_changes + changes
    fixed, axi_changes = repair_axi_response_sampling(fixed, driver_key)
    changes += axi_changes
    fixed, handshake_changes = repair_direct_handshake(fixed, design, replay, driver_key)
    changes += handshake_changes
    native_seq_item = fixed['seq_item']
    if event_mode:
        from event_transport import install_event_transport
        from environment_policy import derive_policy
        environment_policy = derive_policy(blueprint,replay['environment'].get('boundary'))
        fixed = install_event_transport(fixed,design,replay,driver_key,environment_policy)
    else:
        fixed = install_cycle_transport(fixed, design, replay, driver_key)
    # A sequence timeout must fail BOTH arms, not silently advance to the next test.
    fixed["test"] = fixed["test"].replace('`uvm_warning("SEQ_TIMEOUT"', '`uvm_fatal("SEQ_TIMEOUT"')
    changes += ["shared raw-event driver mode with pre-edge known-bit checks" if event_mode else "shared raw-cycle driver mode with pre-edge known-bit checks",
                "shared deterministic initial input values", "sequence timeout is fatal in both arms"]
    files = paths + sequences_paths + bfm_paths + [stage_config_path, compile_path, blueprint_path, ir / "phase1_structured_spec.json",
                                     ir / "phase2b_protocol_flows.json", ir / "phase4b_dsl_sequences.json"]
    bundle = {"contract": CONTRACT, "haven_sha256": checkout_hashes(haven_root),
              "design": design.record(), "replay_config": str(Path(replay_config).resolve()),
              "replay_sha256": digest(Path(replay_config)), "replay": replay,
              "components": fixed, "bfm_components": bfm_components, "sequences": sequences, "blueprint": blueprint,
              "native_seq_item": native_seq_item,
              "stimulus_interface_policy": "rvprobe-raw-haven-native-v1",
              "transport_driver": driver_key,
              "structured_spec": json.loads((ir / "phase1_structured_spec.json").read_text()),
              "protocol_flows": json.loads((ir / "phase2b_protocol_flows.json").read_text()),
              "initial_dsl": json.loads((ir / "phase4b_dsl_sequences.json").read_text()),
              "repairs": changes, "sources": {str(p): digest(p) for p in files},
              "stage1_costs": "shared setup cost; retain the source Stage-1 token/time records, not duplicated per arm",
              "initial_sequence_policy": "verbatim Stage-1 sequences, shared by both arms; not assumed model-free"}
    if event_mode:
        bundle['event_environment_policy'] = environment_policy
    if stage_config.get('io_adapter'):
        from bidirectional_wrapper import verify
        bundle['coverage_module'] = verify(stage_config['io_adapter'],design)
        bundle['io_adapter'] = stage_config['io_adapter']
    if (stage1/'manual-diagnostic.json').is_file():
        bundle['manual_baseline_provenance'] = json.loads((stage1/'manual-diagnostic.json').read_text())
        bundle['diagnostic_only'] = True
        bundle['sources'][str(stage1/'manual-diagnostic.json')] = digest(stage1/'manual-diagnostic.json')
    bundle["fingerprint"] = fingerprint(bundle)
    save(out / "bundle.json", bundle)
    return bundle


def load_bundle(path, haven_root):
    bundle = json.loads(Path(path).read_text())
    recorded = bundle.pop("fingerprint")
    if fingerprint(bundle) != recorded or bundle["contract"] != CONTRACT:
        raise ValueError("shared bundle changed")
    bundle["fingerprint"] = recorded
    design, config = load_config(Path(bundle["replay_config"]))
    if design.record() != bundle["design"] or config != bundle["replay"] or digest(Path(bundle["replay_config"])) != bundle["replay_sha256"]:
        raise ValueError("DUT or replay contract changed")
    load_haven(haven_root, bundle["haven_sha256"])
    return bundle, design, config


def paired_loop(bundle, directory, simulate, generate, *, rounds=3, min_gain=0.1, target=100, arms=("haven", "rvprobe"), runtime_repairs=1):
    """One shared baseline; isolated cumulative arms, common post-simulation policy."""
    if not arms or len(set(arms)) != len(arms) or set(arms) - {"haven", "rvprobe"}:
        raise ValueError("select distinct haven/rvprobe arms")
    if type(runtime_repairs) is not int or runtime_repairs < 0:
        raise ValueError('runtime repair budget must be nonnegative')
    directory = Path(directory)
    summary = {"status": "running", "contract": CONTRACT, "bundle": bundle["fingerprint"], "arms": {}}
    from replay_failures import POLICY as FAILURE_POLICY
    summary['runtime_failure_policy'] = FAILURE_POLICY
    baseline_sequences = list(bundle["sequences"])
    check_sequence_set(baseline_sequences)
    baseline = simulate(directory / "baseline", baseline_sequences, [])
    summary["baseline"] = baseline
    save(directory / "progress.json", summary)
    for arm in arms:
        arm_began, arm_started = time.monotonic(), utc()
        current, previous, best = deepcopy(baseline), None, deepcopy(baseline)
        sequences, frames = list(baseline_sequences), []
        result = {"status": "running", "rounds": [], "baseline": deepcopy(baseline), "started_utc": arm_started}
        summary["arms"][arm] = result
        save(directory / "progress.json", summary)
        reason = stop_reason(None, current, 0, rounds, min_gain, target)
        try:
            if not reason:
                for number in range(1, rounds + 1):
                    rd = directory / arm / f"round-{number}"
                    result['active_round'] = number
                    save(directory / 'progress.json', summary)
                    feedback = compact_feedback(current, previous)
                    # Report provenance follows the accepted measurement, never a
                    # failed candidate's coverage or a guessed round directory.
                    if current.get('modinfo'):
                        feedback['modinfo'] = current['modinfo']
                    for repair in range(runtime_repairs + 1):
                        rd = directory / arm / (f'round-{number}' + (f'-repair-{repair}' if repair else ''))
                        save(rd / 'feedback.json', feedback)
                        candidate = generate(arm, rd, feedback, sequences, len(frames))
                        save(rd / 'candidate.json', candidate)
                        if candidate.get('stop') or not candidate.get('sequences'):
                            if repair:
                                raise ValueError('runtime repair did not produce a replacement candidate')
                            break
                        proposed = sequences + candidate['sequences']
                        if proposed[:len(baseline_sequences)] != baseline_sequences:
                            raise ValueError('shared baseline was altered')
                        check_sequence_set(proposed)
                        new_frames = frames + candidate.get('frames', [])
                        try:
                            measured = simulate(rd / 'simulation', proposed, new_frames)
                        except ValueError as error:
                            failure = {'round':number,'repair':repair,'error':str(error),
                                       'directory':str(rd),'diagnostics':getattr(error,'diagnostics',{})}
                            result.setdefault('rejected_candidates', []).append(failure)
                            save(rd/'runtime-failure.json',failure)
                            save(directory/'progress.json',summary)
                            if not getattr(error, 'diagnostics', {}).get('model_repair_allowed', True) or repair == runtime_repairs:
                                raise
                            feedback = {**feedback,'runtime_failure':failure,
                                'rejected_model_candidate':candidate.get('repair_context',{}),
                                'repair_instruction':'Replace your failed candidate using measured diagnostics. Keep the verification intent; do not suppress or delete checks, fabricate outputs, modify shared components, or claim success without execution.'}
                        else:
                            break
                    if candidate.get("stop"):
                        reason = "model_stop"
                        break
                    additions = candidate["sequences"]
                    if not additions:
                        reason = "no_generated_sequences"
                        break
                    delta = coverage_progress(current, measured)
                    result["rounds"].append({"round": number, **delta, "coverage": measured,
                                             "added_sequences": len(additions), "metadata": candidate.get("metadata", {})})
                    previous, current, sequences, frames = current, measured, proposed, new_frames
                    if current["score"] > best["score"]:
                        best = deepcopy(current)
                    reason = stop_reason(previous, current, number, rounds, min_gain, target)
                    save(directory / "progress.json", summary)
                    if reason:
                        break
            result.update(status="completed", stop_reason=reason or "round_budget", final=current, best=best,
                          sequence_count=len(sequences), witness_frames=len(frames))
        except Exception as error:
            result.update(status="failed", error=str(error), final=current, best=best)
            result['failed_round'] = result.get('active_round')
            details = getattr(error, 'diagnostics', {})
            if details:
                result['diagnostics'] = details
            if details.get('model_repair_allowed') is False:
                result['stop_reason'] = ('native_witness_search_exhausted'
                    if details.get('kind') == 'native_witness_search_exhausted'
                    else 'shared_contract_requires_validation')
        result.pop('active_round', None)
        result.update(started_utc=arm_started, finished_utc=utc(), elapsed_seconds=time.monotonic()-arm_began,
                      costs=totals(directory / arm))
        save(directory / "progress.json", summary)
    summary["status"] = "completed" if all(a["status"] == "completed" for a in summary["arms"].values()) else "failed"
    save(directory / "progress.json", summary)
    return summary


class SimulationFailure(ValueError):
    def __init__(self, message, log_path):
        super().__init__(message)
        from replay_failures import classify
        log = log_path.read_text()
        self.diagnostics = {'log':str(log_path),'tail':log[-24000:], **classify(log+'\n'+message)}


class HavenSimulation:
    def __init__(self, bundle, design, config, seed):
        self.bundle, self.design, self.config, self.seed = bundle, design, config, seed
        self.coverage_module = bundle.get('coverage_module',design.top)

    def __call__(self, directory, sequences, frames):
        from experiment_storage import require_space, archive_closed_simulation
        if directory.exists() or directory.is_symlink():
            raise FileExistsError(f'simulation output already exists: {directory}')
        require_space(directory)
        try:
            return self._run(directory, sequences, frames)
        finally:
            archive_closed_simulation(directory)

    def _run(self, directory, sequences, frames):
        from haven.graph.rendering import render_templates
        from haven.eda.vcs_utils import vcs_compile, extract_sim_errors
        from haven.eda.urg_utils import run_urg, parse_urg_output
        from haven.eda import run_eda_command
        directory.mkdir(parents=True, exist_ok=False)
        state = {"components": deepcopy(self.bundle["components"]), "sequences": list(sequences),
                 "blueprint": deepcopy(self.bundle["blueprint"]),
                 "task": {"module_name": self.design.top, "rtl_files": [str(p) for p in self.design.sources]}}
        render_templates(state)  # native HAVEN test/pkg/filelist, same for both arms
        components = state["components"]
        components["pkg"] = "`timescale 1ns/1ps\n" + components["pkg"]
        for include in self.design.include_dirs:
            components["filelist"] += f"\n+incdir+{include}\n"
        components["test"] = components["test"].replace('`uvm_warning("SEQ_TIMEOUT"', '`uvm_fatal("SEQ_TIMEOUT"')
        completion = f'$display("RVPROBE_REPLAY_PASS {len(frames)}");\n    $display("HAVEN_SHARED_PASS");\n    phase.drop_objection(this);'
        components["test"] = replace_once(components["test"], "phase.drop_objection(this);", completion)
        # Only test/pkg/filelist are allowed to change when sequences are appended.
        for key, original in self.bundle["components"].items():
            if key not in ("test", "pkg", "filelist") and components[key] != original:
                raise ValueError("shared testbench component changed")
        from ltl_replay import install as install_ltl, check_hit
        try:
            ltl = install_ltl(components,frames,self.design)
        except ValueError as error:
            from replay_failures import ReplayInfrastructureFailure
            raise ReplayInfrastructureFailure(str(error)) from error
        for key, code in components.items():
            suffix = "f" if key == "filelist" else "sv"
            (directory / f"{self.design.top}_{key}.{suffix}").write_text(code)
        for key, code in self.bundle.get('bfm_components', {}).items():
            (directory / f'{key}.sv').write_text(code)
        for index, code in enumerate(sequences, 1):
            (directory / f"sequence_{index}.sv").write_text(code)
        save(directory / "schedule.json", frames)
        save(directory / "inputs.json", {"bundle": self.bundle["fingerprint"], "seed": self.seed,
              "components": {k: fingerprint(v) for k, v in components.items()},
              "sequences": [fingerprint(s) for s in sequences]})
        records = Records(directory)
        with records.phase("vcs-build"):
            compiled = vcs_compile(f"{self.design.top}_filelist.f", cwd=str(directory), config=self.config)
            (directory / "compile.log").write_text(compiled["log"])
            if not compiled["ok"]:
                raise SimulationFailure("shared testbench compilation failed; inspect compile.log", directory/'compile.log')
        with records.phase("vcs-replay"):
            command = (f"./simv +UVM_TESTNAME={self.design.top}_test +ntb_random_seed={self.seed} "
                       "+UVM_TIMEOUT=5000000000 -cm line+cond+tgl+fsm+branch")
            sim = run_eda_command("vcs", command, cwd=str(directory),
                                  timeout=self.config.get("simulation", {}).get("timeout_seconds", 300),
                                  eda_env=self.config.get("eda_env"))
            log = sim.stdout + sim.stderr
            (directory / "sim.log").write_text(log)
            # HAVEN's older helper treats the normal "UVM_ERROR : 0" summary
            # itself as an error. Strip ONLY those exact zero-count summaries.
            diagnostics = re.sub(r"(?m)^\s*UVM_(?:ERROR|FATAL)\s*:\s*0\s*$", "", log)
            errors = extract_sim_errors(diagnostics)
            if sim.returncode or errors or "SEQ_TIMEOUT" in log or "HAVEN_SHARED_PASS" not in log:
                reasons = ([f'exit code {sim.returncode}'] if sim.returncode else []) + errors[:3]
                if 'SEQ_TIMEOUT' in log: reasons.append('sequence timeout')
                if 'HAVEN_SHARED_PASS' not in log: reasons.append('missing completion marker')
                raise SimulationFailure("shared simulation failed or timed out: "+'; '.join(reasons), directory/'sim.log')
            if any(not re.search(rf"UVM_{kind}\s*:\s*0\b", log) for kind in ("ERROR", "FATAL")):
                raise ValueError("UVM errors are not coverage success")
            checks_path = None
            try:
                policy = self.bundle.get('event_environment_policy') or {}
                live_inputs = list(policy.get('retained_inputs', []))
                if ltl:
                    # A clocked PHY resolves inactive RX payload and loopback
                    # itself. These are not directly driven DUT input pins.
                    # The original goal is still checked on their actual values.
                    live_inputs += policy.get('resolved_stimulus_inputs', [])
                checks = validate_samples(self.design, frames, log,
                    goal_checked=bool(ltl),
                    environment=self.bundle.get('replay', {}).get('environment'),
                    reset_environment_inputs=live_inputs) if frames else {"passed": True, "witness_cycles": 0}
                # Validate stimulus/reset/event fidelity before classifying a
                # missing Cover hit as a solver/native semantics mismatch.
                if ltl:
                    # Persist successful transport checks even when the original
                    # Cover fails. Otherwise the most useful first-divergence
                    # evidence was discarded on precisely the failing path.
                    save(directory/'replay-checks.json', checks)
                    checks_path = directory/'replay-checks.json'
                goal_check = check_hit(ltl,log) if ltl else None
                if goal_check: checks['ltl'] = goal_check
            except ValueError as error:
                failure = SimulationFailure(str(error), directory/'sim.log')
                if checks_path is not None:
                    failure.diagnostics['replay_checks'] = str(checks_path)
                raise failure from error
        with records.phase("urg"):
            run_urg(cwd=str(directory), config=self.config, module_name=self.design.top)
            modinfo = directory / f"{self.design.top}_urgReport/modinfo.txt"
            report = parse(modinfo)
            percent, total, bins = score(report, [self.coverage_module], METRICS)
            checked_percent, checked_total = coverage_score(bins)
            assert (percent, total) == (checked_percent, checked_total)
            # Feed native HAVEN parser only the selected DUT module, not BFM/TB gaps.
            parts = _MODULE_SPLIT.split(modinfo.read_text())
            section = next(body for name, body in zip(parts[1::2], parts[2::2]) if name == self.coverage_module)
            parsed = parse_urg_output(section)
            gaps = parsed["uncovered"]
            for gap in gaps:
                gap["module"] = self.coverage_module
            # Older HAVEN lacks FSM parsing; preserve the real section, never silently drop it.
            for metric, value in percent.items():
                if value < 100 and not any(g.get("type") == metric for g in gaps):
                    gaps.append({"type": metric, "module": self.coverage_module, "summary_only": True,
                                 "covered": bins[metric][1], "total": bins[metric][0],
                                 "report_section": section if metric == "fsm" else "See full RTL and metric counts."})
        result = {"modules": [self.coverage_module], "percent": percent, "score": total, "bins": bins,
                  "uncovered": gaps, "modinfo": str(modinfo), "replay": checks,
                  "artifact_sha256": {str(p): digest(p) for p in sorted(directory.glob("*.sv")) +
                                      [directory / "schedule.json", directory / "sim.log", modinfo]},
                  "functional_correctness_proven": False}
        save(directory / "coverage.json", result)
        return result


def build_haven_prompt(template, design, feedback, existing_dsl, initial_dsl, schema):
    """Keep HAVEN's inline evidence prompt; only this arm's later DSL is history."""
    baseline = initial_dsl["sequences"]
    if existing_dsl["sequences"][:len(baseline)] != baseline:
        raise ValueError("HAVEN DSL history no longer starts with the shared baseline")
    added = {**existing_dsl, "sequences": existing_dsl["sequences"][len(baseline):]}
    prompt = template.format(
        module_name=design.top,
        existing_dsl_json="Baseline: shared_context.initial_dsl below. Additional sequences from this arm:\n" + generation.prompt_json(added),
        protocol_flows_json="See shared_context.protocol_flows below.",
        coverage_gaps_json=generation.prompt_json(feedback),
        bus_field_mapping_json="See shared_context.protocol_flows.bus_field_mapping above.",
        seq_item_fields_json="See shared_context.seq_item_fields and transaction_contract above.")
    prompt += "\nAuthoritative specification/protocol text:\n" + (design.context or "(None supplied.)")
    prompt += "\nComplete RTL:\n" + generation.design_evidence(design)
    prompt += "\nOutput JSON schema:\n" + generation.prompt_json(schema)
    return prompt


def saved_generation(directory, bundle, design, model):
    """Continue the latest model attempt, never select a hand-authored/best UT."""
    directory = Path(directory).resolve()
    summary = json.loads((directory/'summary.json').read_text())
    manifest = json.loads((directory/'manifest.json').read_text())
    if summary.get('model') != model or manifest.get('model') != model:
        raise ValueError('saved generation was not authored by the requested model')
    if manifest['design'] != design.record():
        raise ValueError('saved generation RTL/IO/specification changed')
    previous = json.loads((directory.parents[3]/'shared/bundle.json').read_text())
    for key in ('initial_dsl','sequences','components','bfm_components','blueprint','haven_sha256'):
        if previous[key] != bundle[key]:
            raise ValueError('saved generation shared environment changed: '+key)
    attempt = directory/f"attempt-{summary['attempts']}"
    provider = json.loads((attempt/'provider.json').read_text())
    if provider.get('requested_model') != model or provider.get('response_status') != 'complete':
        raise ValueError('saved generation has no complete provider response')
    response = attempt/'response.txt'
    parsed = generation.parse_response(response.read_text())
    from sequence_framework import check_saved_sources
    check_saved_sources(attempt/'sources',design,parsed)
    return {'source':str(directory),'response':str(response),'response_sha256':digest(response),
            'prior_costs':summary['costs'],'prior_elapsed_seconds':summary['elapsed_seconds'],
            'policy':'continue latest remote-model attempt after framework repair; no manual source edits'}


def accepted_rvprobe_history(candidates, existing):
    """Keep only this run's LTL fragments whose sequences were actually accepted."""
    return {'ltls': [deepcopy(c['ltl']) for c in candidates
                    if c['sequences'] and all(s in existing for s in c['sequences'])]}


class Backends:
    def __init__(self, bundle, design, replay, args):
        if (replay.get('environment',{}).get('boundary') == 'independent-dut-v1' and
                bundle.get('stimulus_interface_policy') != 'rvprobe-raw-haven-native-v1'):
            raise ValueError('obsolete shared raw/transaction API bundle; prepare a new method-separated bundle before model calls')
        self.bundle, self.design, self.replay, self.args = bundle, design, replay, args
        self.haven_dsl = deepcopy(bundle["initial_dsl"])
        self.haven_candidates = []
        self.rvprobe_candidates = []
        self.native_witness_simulator = None
        self.rvprobe_skill = generation.snapshot()

    def __call__(self, arm, rd, feedback, existing, ordinal):
        args = self.args
        # Common source evidence; TaskContext further removes native transaction
        # abstractions for the RVProbe arm before any model/tool response.
        feedback = paired_feedback(feedback, self.bundle)
        if self.replay.get("formal_initial_state"):
            feedback["formal_environment"] = {
                "initialization": "trusted RTL-derived initial-state snapshot",
                "past_history_supported": False,
                "instruction": "This snapshot mode has no validated pre-trace past history. Use current-cycle expressions or forward LTL sequences; do not call past. Do not generate Assume."}
        save(rd / "feedback.json", feedback)
        if arm == "haven":
            return self.haven(rd, feedback, existing)
        skill_path = rd.parent / 'frozen-skill.json'
        if skill_path.exists():
            if generation.load_snapshot(skill_path) != self.rvprobe_skill:
                raise ValueError('frozen RVProbe skill changed between rounds')
        else:
            save(skill_path, self.rvprobe_skill)
        from rtl_evidence import collect
        history = accepted_rvprobe_history(self.rvprobe_candidates, existing)
        history['coverage_round'] = int(rd.name.removeprefix('round-'))
        history['rtl_evidence'] = collect(rd.parent, int(rd.name.removeprefix('round-')))
        save(rd / 'accepted-history.json', history)
        command = [sys.executable, str(ROOT / "experiments/sequence_experiment.py"),
                   '--skill-snapshot', str(skill_path), '--history-file', str(rd / 'accepted-history.json'),
                   "--design", str((Path(self.bundle["replay_config"]).parent / self.replay["design"]).resolve()),
                   "--replay-config", str(self.bundle["replay_config"]),
                   "--modinfo", feedback["modinfo"], "--feedback-file", str(rd / "feedback.json"),
                   "--out", str(rd / "generation"), "--model", args.model, "--temperature", str(args.temperature),
                   "--attempts", str(args.attempts), "--timeout", str(args.timeout),
                   "--request-retries", str(args.request_retries), "--jg-time-limit", args.jg_time_limit,
                   "--eda-shell", str(args.eda_shell), "--sequences-per-intent", str(args.sequences_per_intent)]
        if self.bundle.get('coverage_module'):
            command += ['--module',self.bundle['coverage_module']]
        continuation = getattr(args,'continued_generation',None)
        if continuation and rd.name == 'round-1':
            if digest(Path(continuation['response'])) != continuation['response_sha256']:
                raise ValueError('saved model response changed after validation')
            command += ['--response-file',continuation['response']]
            save(rd/'continued-generation.json',continuation)
        elif args.env_file:
            command += ["--env-file", str(args.env_file)]
        with (rd / "generation.log").open("w") as log:
            run_process(command, stdout=log, stderr=-2, check=True,
                        timeout=args.attempts * (MAX_MODEL_CALLS * args.timeout * args.request_retries + 21600))
        result = json.loads((rd / "generation/summary.json").read_text())
        if result["result"]["status"] == "stopped":
            return {"stop": result["result"]["stopReason"]}
        goals = expand_goals(result, Path(self.bundle["replay_config"]), rd / "sampling", args)
        sequences, frames, sampling = [], [], []
        for goal in goals:
            rows = goal.get("sequences", [goal] if goal["status"] == "generated" else [])
            selection = None
            if self.native_witness_simulator is not None and rows:
                from native_witness_selection import pool_target, select_witnesses
                from witness_sampling import frozen_inputs, sample_goal
                search = rd/'native-witness-search'/goal['label']
                selected_frame_count = 0
                def evaluate(row, index):
                    nonlocal selected_frame_count
                    # Cache exactly the same independently rendered sequence
                    # that the final cumulative coverage merge will consume.
                    new = witness_frames(self.design, self.replay, row, ordinal + len(frames) + selected_frame_count)
                    from ltl_replay import attach
                    attach(new, goal)
                    name = f"rvp_{rd.name.replace('-', '_')}_{goal['label']}_{index}"
                    source = render_witness_sequence(self.design, new, name, 0)
                    self.native_witness_simulator.measure_one([source], new)
                    selected_frame_count += len(new)
                    return (row, index)
                def replenish(budget):
                    _, _, job, original_goals = frozen_inputs(
                        Path(result['sources']).parent/'solve', Path(self.bundle['replay_config']))
                    original = next(g for g in original_goals if g['label'] == goal['label'])
                    return sample_goal(job, original, self.design, self.replay, search/'sampling',
                                       budget, args.sampling_seed, args.sampling_time_limit, args.eda_shell)
                auxiliary = None
                if getattr(args, 'encoded_witness_yosys', None):
                    from encoded_candidate_search import candidates
                    def auxiliary(budget):
                        return candidates(Path(result['sources']).parent/'solve',
                            Path(self.bundle['replay_config']), goal['label'], search/'encoded',
                            args.encoded_witness_yosys, args.eda_shell, budget, args.sampling_seed)
                selected, selection = select_witnesses(rows, pool_target(args.sequences_per_intent, len(rows)),
                                                       evaluate, replenish, search, auxiliary=auxiliary)
            else:
                selected = [(row, index) for index, row in enumerate(rows)]
            sampling.append({"label": goal["label"], "actual": len(selected),
                             "requested_cap": args.sequences_per_intent,
                             "available_pool": len(rows),
                             "sampling_shortfall": max(0, args.sequences_per_intent - len(selected)),
                             "pool_policy": 'up-to-cap-all-offered-native-validated-v1',
                             "status": 'native-validated' if selection else goal.get("sampling_status", goal["status"]),
                             **({'native_selection':selection} if selection else {})})
            for row, index in selected:
                new = witness_frames(self.design, self.replay, row, ordinal + len(frames))
                if self.replay.get('environment'):
                    from ltl_replay import attach
                    attach(new,goal)
                label = f"rvp_{rd.name.replace('-', '_')}_{goal['label']}_{index}"
                sequences.append(render_witness_sequence(self.design, new, label, ordinal + len(frames)))
                frames += new
        ut_files = sorted((rd/'generation').glob('attempt-*/sources/model.ltl'))
        if ut_files:
            self.rvprobe_candidates.append({'sequences': list(sequences), 'ltl': {
                'round': rd.name, 'source': ut_files[-1].read_text(), 'sha256': digest(ut_files[-1])}})
        return {"sequences": sequences, "frames": frames, "metadata": {"sampling": sampling},
                "repair_context": {"ltl":ut_files[-1].read_text() if ut_files else None}}

    def haven(self, rd, feedback, existing):
        from haven.dsl.schema import DSLSequenceSet, BusFieldMapping, BFMConfig
        from haven.dsl.codegen import DSLCodegen, dsl_filter_stats
        prompt_template = (self.args.haven_root / "src/haven/prompts/gen_sequence_dsl_gap.md").read_text()
        flow = self.bundle["protocol_flows"]
        fields = self.bundle["blueprint"].get("data_contracts", {}).get("seq_item_fields", [])
        from haven.utils.transaction_contract import validate_item_contract
        interface_errors = validate_item_contract(self.bundle['native_seq_item'], fields)
        if interface_errors:
            raise ValueError('invalid frozen HAVEN interface before model request: ' + '; '.join(interface_errors))
        self.haven_dsl = accepted_haven_dsl(self.bundle['initial_dsl'],self.haven_candidates,existing)
        prompt = build_haven_prompt(prompt_template, self.design, feedback, self.haven_dsl,
                                    self.bundle["initial_dsl"], DSLSequenceSet.model_json_schema())
        save(rd / "request-options.json", {"model": self.args.model, "temperature": self.args.temperature,
              "timeout": self.args.timeout, "request_retries": self.args.request_retries})
        errors = []
        for attempt in range(1, self.args.attempts + 1):
            ad = rd / f"attempt-{attempt}"
            ad.mkdir()
            (ad / "prompt.txt").write_text(prompt + ("\nSchema feedback:\n" + json.dumps(errors) if errors else ""))
            command = [sys.executable, str(Path(__file__).resolve()), "request", "--directory", str(ad),
                       "--options", str(rd / "request-options.json")]
            if self.args.env_file:
                command += ["--env-file", str(self.args.env_file)]
            with (ad / "request.log").open("w") as log:
                run_process(command, check=True, stdout=log, stderr=-2,
                            timeout=self.args.timeout * self.args.request_retries + 60)
            try:
                raw = generation.strip_fence((ad / "response.txt").read_text())
                from haven.utils.json_output import loads
                dsl = DSLSequenceSet.model_validate(loads(raw))
                if dsl.module_name != self.design.top:
                    raise ValueError("DSL targets a different DUT")
                if not dsl.sequences:
                    return {"stop": "HAVEN returned no additional DSL sequences"}
                if len(dsl.sequences) > feedback['intent_batch_limit']:
                    raise ValueError(f"This paired batch permits at most {feedback['intent_batch_limit']} intents/DSL sequences; submit a smaller complete batch without weakening its selected intents.")
                from dsl_validation import validate_storage, validate_native_interface
                validate_native_interface(dsl)
                validate_storage(dsl)
                from haven.utils.bfm_api import available, validate
                ownership = self.bundle['blueprint'].get('environment_contract') or {}
                validate(dsl,available(self.bundle['blueprint'].get('bfm_configs')),
                         set(ownership.get('bfm_owned', {})) | set(ownership.get('infrastructure_owned', {})))
                mapping = flow.get("bus_field_mapping")
                dsl_filter_stats.reset()
                codes = DSLCodegen().generate(dsl, BusFieldMapping(**mapping) if mapping else None, fields,
                         seq_item_code=self.bundle['native_seq_item'],
                         bfm_configs=[BFMConfig(**b) for b in self.bundle['blueprint'].get('bfm_configs') or []])
                filters = dsl_filter_stats.to_dict()
                save(ad / "codegen-filters.json", filters)
                check_sequence_set(existing + codes)
            except ValueError as error:
                errors.append(str(error))
                save(ad / "error.json", {"error": str(error)})
                continue
            self.haven_candidates.append((codes,dsl.model_dump()['sequences']))
            save(ad / "dsl.json", dsl.model_dump())
            return {"sequences": codes, "frames": [], "metadata": {"dsl_attempts": attempt, "codegen_filters": filters},
                    "repair_context": {'dsl':dsl.model_dump()}}
        raise ValueError("HAVEN DSL generation exhausted schema repair budget")


def accepted_haven_dsl(initial, candidates, existing):
    result = deepcopy(initial)
    for codes, dsl in candidates:
        if all(code in existing for code in codes):
            result['sequences'] += deepcopy(dsl)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="command", required=True)
    prep = subs.add_parser("prepare", help="freeze Stage-1 assets; no model/EDA calls")
    prep.add_argument("--stage1-run", type=Path, required=True)
    prep.add_argument("--haven-root", type=Path, required=True)
    prep.add_argument("--replay-config", type=Path, required=True)
    prep.add_argument("--out", type=Path, required=True)
    run = subs.add_parser("run", help="explicitly run BOTH arms (models and EDA)")
    run.add_argument("--bundle", type=Path, required=True)
    run.add_argument("--haven-root", type=Path, required=True)
    run.add_argument("--eda-config", type=Path, required=True, help="HAVEN EDA-only JSON, no model keys")
    run.add_argument("--out", type=Path, required=True)
    run.add_argument("--arm", choices=("both", "haven", "rvprobe"), default="both")
    run.add_argument("--rounds", type=int, default=3)
    run.add_argument("--min-gain", type=float, default=0.1)
    run.add_argument("--target", type=float, default=100)
    run.add_argument("--seed", type=int, default=20260906)
    run.add_argument("--isolate-sequences", action="store_true",
                     help="fresh simulation per added sequence in BOTH arms; union coverage databases")
    run.add_argument("--model", default=generation.DEFAULT_MODEL)
    run.add_argument("--temperature", type=float, default=0.3)
    run.add_argument("--timeout", type=int, default=600)
    run.add_argument("--attempts", type=int, default=3)
    run.add_argument("--request-retries", type=int, default=3)
    run.add_argument('--runtime-repairs',type=int,default=1)
    run.add_argument("--jg-time-limit", default="120s")
    run.add_argument("--eda-shell", type=Path, default=generation.DEFAULT_EDA_SHELL)
    run.add_argument("--env-file", type=Path)
    run.add_argument('--encoded-witness-yosys', type=Path,
                     help='opt-in auxiliary candidate search; unchanged original native Cover required')
    run.add_argument('--continue-generation',type=Path,help='reuse latest first-round remote-model response in a new run')
    run.add_argument('--fixed-stage1-identity',type=Path,
                     help='explicit frozen shared-environment admission; preserves original setup provenance')
    sampling_options(run)
    req = subs.add_parser("request", help="internal credential-isolated model worker")
    req.add_argument("--directory", type=Path, required=True)
    req.add_argument("--options", type=Path, required=True)
    req.add_argument("--env-file", type=Path)
    args = parser.parse_args(argv)
    if args.command == "prepare":
        bundle = prepare(args.stage1_run, args.haven_root, args.replay_config, args.out)
        print(json.dumps({"bundle": str(args.out / "bundle.json"), "fingerprint": bundle["fingerprint"], "experiments_run": False}))
        return 0
    if args.command == "request":
        if args.env_file:
            generation.load_env_file(args.env_file)
        options = SimpleNamespace(**json.loads(args.options.read_text()))
        raw, info = generation.request_model(options, (args.directory / "prompt.txt").read_text(), args.directory, Records(args.directory))
        (args.directory / "response.txt").write_text(raw)
        save(args.directory / "provider.json", info)
        return 0
    args.resume = False
    arms = ("haven", "rvprobe") if args.arm == "both" else (args.arm,)
    if "rvprobe" in arms:
        import shutil
        if not shutil.which("bwrap"):
            parser.error("bubblewrap is required; start inside nix develop before requesting models")
    policy = sampling_policy(args)
    stop_reason(None, {"score": 0}, 0, args.rounds, args.min_gain, args.target)
    if min(args.attempts, args.timeout, args.request_retries) < 1 or args.runtime_repairs < 0:
        parser.error("budgets must be positive")
    if not 0 <= args.seed < 2**31:
        parser.error("seed must be a nonnegative 31-bit integer")
    bundle, design, replay = load_bundle(args.bundle, args.haven_root)
    admission = None
    if args.fixed_stage1_identity:
        from frozen_stage1 import admit_shared_environment
        admission = admit_shared_environment(bundle,json.loads(args.fixed_stage1_identity.read_text()))
    if bundle.get('diagnostic_only') and args.model != 'manual-author-debug' and admission is None:
        raise ValueError('manually authored diagnostic baseline cannot be used as a provider-model benchmark')
    if 'rvprobe' in arms and replay.get('environment'):
        from environment_policy import derive_policy, require_supported
        expected_policy = derive_policy(bundle['blueprint'],replay['environment'].get('boundary'))
        if bundle.get('event_environment_policy') != expected_policy:
            raise ValueError('regenerate shared bundle with response-preserving event transport')
        require_supported(expected_policy)
    isolate_sequences = args.isolate_sequences or bool(replay.get("formal_initial_state"))
    config = json.loads(args.eda_config.read_text())
    if set(config) - {"eda_tools", "eda_env", "simulation"}:
        parser.error("EDA config accepts only eda_tools, eda_env, simulation; model credentials belong in env-file")
    config.setdefault("eda_env", {})["shell"] = str(args.eda_shell.resolve())
    root = args.out.resolve()
    args.continued_generation = (saved_generation(args.continue_generation,bundle,design,args.model)
                                 if args.continue_generation else None)
    manifest = {"contract": CONTRACT, "bundle": bundle["fingerprint"], "model": args.model, "arms": list(arms),
                "source_sha256": framework_hashes(ROOT), "haven_sha256": bundle["haven_sha256"],
                "stimulus_interface_policy": bundle.get('stimulus_interface_policy'),
                "eda_sha256": digest(args.eda_config), "seed": args.seed, "sampling": policy,
                "eda_shell_sha256": digest(args.eda_shell.resolve()),
                "rounds": args.rounds, "min_gain": args.min_gain, "target": args.target,
                "attempts": args.attempts, "request_retries": args.request_retries,
                "temperature": args.temperature, "timeout": args.timeout, "jg_time_limit": args.jg_time_limit,
                "scope": [bundle.get('coverage_module',design.top)], "metrics": list(METRICS),
                "baseline_policy": "shared Stage-1 sequences, fresh process per sequence, coverage union" if isolate_sequences else "one shared HAVEN Stage-1 simulation; exact same sequence prefix",
                "common_context_policy": CONTEXT_POLICY,
                'rtl_context_policy':{'rvprobe':RTL_CONTEXT_POLICY,'haven':'full-inline-unchanged'},
                'max_model_calls_per_dialogue':{'rvprobe':MAX_MODEL_CALLS,'haven':1},
                "generation_task_policy": "finite-intent-batch-v1",
                "incomplete_response_policy": "stop-without-automatic-regeneration-v1",
                "sequence_state_policy": ISOLATION_POLICY if isolate_sequences else "continuous",
                "runtime_repairs":args.runtime_repairs,
                "repair_policy": "bounded model candidate repair from measured runtime errors; shared infrastructure frozen; rejected candidates retain costs",
                "bo_policy": "disabled in both arms; only generation backend varies",
                "formal_exclusion_policy": "none in either arm"}
    if args.continued_generation:
        manifest['continued_generation'] = args.continued_generation
    if admission:
        manifest['shared_environment_admission'] = admission
    if isolate_sequences:
        from isolated_replay import DIAGNOSTIC_POLICY
        manifest['candidate_diagnostic_policy'] = DIAGNOSTIC_POLICY
        if replay.get('environment', {}).get('boundary') == 'independent-dut-v1':
            from native_witness_selection import POLICY as NATIVE_SELECTION_POLICY, search_budget
            manifest['native_witness_selection'] = dict(policy=NATIVE_SELECTION_POLICY,
                target_per_intent=args.sequences_per_intent,
                candidate_budget_per_intent=search_budget(args.sequences_per_intent) * (2 if args.encoded_witness_yosys else 1),
                auxiliary_candidate_policy='encoded-native-checked-v1' if args.encoded_witness_yosys else None,
                auxiliary_yosys=str(args.encoded_witness_yosys) if args.encoded_witness_yosys else None,
                all_attempt_costs_retained=True, model_calls=0)
    from prompt_context import MAX_INTENTS
    manifest['intent_batch_limit'] = MAX_INTENTS
    started, began = begin(root, manifest), time.monotonic()
    summary = {"status": "failed"}
    records = Records(root)
    backends = Backends(bundle, design, replay, args)
    def generate(arm, rd, feedback, existing, ordinal):
        # The report path is shared evidence, but not repeated per sample in the prompt.
        return backends(arm, rd, feedback, existing, ordinal)
    try:
        with records.phase("paired-session"):
            simulate = HavenSimulation(bundle, design, config, args.seed)
            if isolate_sequences:
                from isolated_replay import IsolatedSimulation
                simulate = IsolatedSimulation(simulate, root / "replay-cache")
                if replay.get('environment', {}).get('boundary') == 'independent-dut-v1':
                    backends.native_witness_simulator = simulate
            summary = paired_loop(bundle, root, simulate, generate,
                                  rounds=args.rounds, min_gain=args.min_gain, target=args.target, arms=arms,
                                  runtime_repairs=args.runtime_repairs)
    except Exception as error:
        summary.update(status="failed", error=str(error))
    finally:
        if args.continued_generation and 'rvprobe' in summary.get('arms',{}):
            costs = summary['arms']['rvprobe']['costs']
            prior = args.continued_generation['prior_costs']['usage_reported']
            costs['prior_generation_usage'] = prior
            costs['cumulative_usage_reported'] = {
                key: (prior[key]+value if prior.get(key) is not None and value is not None else None)
                for key,value in costs['usage_reported'].items()}
        finish(root, summary, started, began, session_phase="paired-session", **manifest)
    print(json.dumps({"status": summary["status"], "summary": str(root / "summary.json")}))
    return int(summary["status"] != "completed")


if __name__ == "__main__":
    raise SystemExit(main())
