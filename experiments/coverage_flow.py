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
                          render_witness_sequence, stop_reason, repair_direct_handshake)
from process_runner import run as run_process
from run_records import Records, begin, finish, fingerprint, framework_hashes, save, totals, utc
from sequence_framework import ROOT, identifier
from urg_score import parse, score, _MODULE_SPLIT
from witness_sampling import expand_goals, sampling_options, sampling_policy


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


def prepare(stage1, haven_root, replay_config, out, repairs=None):
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
    if any(stage_config.get(k) for k in ("bfm_configs", "extra_resets", "static_signals")):
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
    # Explicit scope until a formal environment contract for BFMs/multiple clocks exists.
    agents = blueprint.get("topology", {}).get("agents", []) or []
    io = blueprint.get("io_specification", {})
    if len(agents) > 1 or blueprint.get("bfm_configs") or io.get("inouts") or len(io.get("clocks", [])) > 1:
        raise ValueError("paired witness transport currently supports one active agent, one clock, no BFM/inout")
    if agents and agents[0].get("mode", "active") != "active":
        raise ValueError("paired transport needs an active primary agent")
    if blueprint.get("module_name") != design.top or blueprint.get("clock", {}).get("port") != design.clock:
        raise ValueError("HAVEN blueprint DUT/clock disagrees with IO manifest")
    if blueprint.get("reset") != {"name": design.reset, "level": "low" if design.reset_active_low else "high"}:
        raise ValueError("HAVEN reset contract disagrees with formal IO manifest")
    ports = {p.name: (p.direction, p.width) for p in design.data_ports}
    declared = {p["name"]: (direction, int(p["width"])) for direction, key in (("input", "inputs"), ("output", "outputs")) for p in io.get(key, [])}
    if ports != declared:
        raise ValueError("HAVEN blueprint IO differs from formal IO manifest")
    driver_key = "driver"
    identifier(driver_key, "driver component")
    paths = sorted((stage1 / "final").glob(f"{design.top}_*.sv"))
    components = {p.stem[len(design.top) + 1:]: p.read_text() for p in paths}
    for required in ("interface", "seq_item", "top", "test", "pkg", driver_key):
        if required not in components:
            raise ValueError(f"Stage-1 missing component {required}")
    sequences_paths = sorted((stage1 / "final").glob("sequence_*.sv"),
                             key=lambda p: int(p.stem.split("_")[-1]))
    sequences = [p.read_text() for p in sequences_paths]
    if not sequences:
        raise ValueError("Stage-1 contains no initial sequences")
    check_sequence_set(sequences)
    repairs = repairs or {}
    if repairs.get("clock_connections") or repairs.get("owned_signals"):
        raise ValueError("multi-clock/static/BFM pin ownership needs a matching formal environment before paired execution")
    fixed, changes = repair_components(components, [vars(p) for p in design.data_ports], **repairs)
    fixed, handshake_changes = repair_direct_handshake(fixed, design, replay, driver_key)
    changes += handshake_changes
    fixed = install_cycle_transport(fixed, design, replay, driver_key)
    # A sequence timeout must fail BOTH arms, not silently advance to the next test.
    fixed["test"] = fixed["test"].replace('`uvm_warning("SEQ_TIMEOUT"', '`uvm_fatal("SEQ_TIMEOUT"')
    changes += ["shared raw-cycle driver mode with pre-edge known-bit checks",
                "shared deterministic initial input values", "sequence timeout is fatal in both arms"]
    files = paths + sequences_paths + [stage_config_path, compile_path, blueprint_path, ir / "phase1_structured_spec.json",
                                     ir / "phase2b_protocol_flows.json", ir / "phase4b_dsl_sequences.json"]
    bundle = {"contract": CONTRACT, "haven_sha256": checkout_hashes(haven_root),
              "design": design.record(), "replay_config": str(Path(replay_config).resolve()),
              "replay_sha256": digest(Path(replay_config)), "replay": replay,
              "components": fixed, "sequences": sequences, "blueprint": blueprint,
              "structured_spec": json.loads((ir / "phase1_structured_spec.json").read_text()),
              "protocol_flows": json.loads((ir / "phase2b_protocol_flows.json").read_text()),
              "initial_dsl": json.loads((ir / "phase4b_dsl_sequences.json").read_text()),
              "repairs": changes, "sources": {str(p): digest(p) for p in files},
              "stage1_costs": "shared setup cost; retain the source Stage-1 token/time records, not duplicated per arm",
              "initial_sequence_policy": "verbatim Stage-1 sequences, shared by both arms; not assumed model-free"}
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


def paired_loop(bundle, directory, simulate, generate, *, rounds=3, min_gain=0.1, target=100, arms=("haven", "rvprobe")):
    """One shared baseline; isolated cumulative arms, common post-simulation policy."""
    if not arms or len(set(arms)) != len(arms) or set(arms) - {"haven", "rvprobe"}:
        raise ValueError("select distinct haven/rvprobe arms")
    directory = Path(directory)
    summary = {"status": "running", "contract": CONTRACT, "bundle": bundle["fingerprint"], "arms": {}}
    baseline_sequences = list(bundle["sequences"])
    baseline = simulate(directory / "baseline", baseline_sequences, [])
    summary["baseline"] = baseline
    save(directory / "progress.json", summary)
    for arm in arms:
        arm_began, arm_started = time.monotonic(), utc()
        current, previous, best = deepcopy(baseline), None, deepcopy(baseline)
        sequences, frames = list(baseline_sequences), []
        result = {"status": "running", "rounds": [], "baseline": deepcopy(baseline)}
        summary["arms"][arm] = result
        reason = stop_reason(None, current, 0, rounds, min_gain, target)
        try:
            if not reason:
                for number in range(1, rounds + 1):
                    rd = directory / arm / f"round-{number}"
                    feedback = compact_feedback(current, previous)
                    save(rd / "feedback.json", feedback)
                    candidate = generate(arm, rd, feedback, sequences, len(frames))
                    save(rd / "candidate.json", candidate)
                    if candidate.get("stop"):
                        reason = "model_stop"
                        break
                    additions = candidate["sequences"]
                    if not additions:
                        reason = "no_generated_sequences"
                        break
                    proposed = sequences + additions
                    if proposed[:len(baseline_sequences)] != baseline_sequences:
                        raise ValueError("shared baseline was altered")
                    check_sequence_set(proposed)
                    new_frames = frames + candidate.get("frames", [])
                    measured = simulate(rd / "simulation", proposed, new_frames)
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
        result.update(started_utc=arm_started, finished_utc=utc(), elapsed_seconds=time.monotonic()-arm_began,
                      costs=totals(directory / arm))
        save(directory / "progress.json", summary)
    summary["status"] = "completed" if all(a["status"] == "completed" for a in summary["arms"].values()) else "failed"
    return summary


class HavenSimulation:
    def __init__(self, bundle, design, config, seed):
        self.bundle, self.design, self.config, self.seed = bundle, design, config, seed

    def __call__(self, directory, sequences, frames):
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
        for key, code in components.items():
            suffix = "f" if key == "filelist" else "sv"
            (directory / f"{self.design.top}_{key}.{suffix}").write_text(code)
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
                raise ValueError("shared testbench compilation failed; inspect compile.log")
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
            if sim.returncode or extract_sim_errors(diagnostics) or "SEQ_TIMEOUT" in log or "HAVEN_SHARED_PASS" not in log:
                raise ValueError("shared simulation failed or timed out")
            if any(not re.search(rf"UVM_{kind}\s*:\s*0\b", log) for kind in ("ERROR", "FATAL")):
                raise ValueError("UVM errors are not coverage success")
            checks = validate_samples(self.design, frames, log) if frames else {"passed": True, "witness_cycles": 0}
        with records.phase("urg"):
            run_urg(cwd=str(directory), config=self.config, module_name=self.design.top)
            modinfo = directory / f"{self.design.top}_urgReport/modinfo.txt"
            report = parse(modinfo)
            percent, total, bins = score(report, [self.design.top], METRICS)
            checked_percent, checked_total = coverage_score(bins)
            assert (percent, total) == (checked_percent, checked_total)
            # Feed native HAVEN parser only the selected DUT module, not BFM/TB gaps.
            parts = _MODULE_SPLIT.split(modinfo.read_text())
            section = next(body for name, body in zip(parts[1::2], parts[2::2]) if name == self.design.top)
            parsed = parse_urg_output(section)
            gaps = parsed["uncovered"]
            for gap in gaps:
                gap["module"] = self.design.top
            # Older HAVEN lacks FSM parsing; preserve the real section, never silently drop it.
            for metric, value in percent.items():
                if value < 100 and not any(g.get("type") == metric for g in gaps):
                    gaps.append({"type": metric, "module": self.design.top, "summary_only": True,
                                 "covered": bins[metric][1], "total": bins[metric][0],
                                 "report_section": section if metric == "fsm" else "See full RTL and metric counts."})
        result = {"modules": [self.design.top], "percent": percent, "score": total, "bins": bins,
                  "uncovered": gaps, "modinfo": str(modinfo), "replay": checks,
                  "artifact_sha256": {str(p): digest(p) for p in sorted(directory.glob("*.sv")) +
                                      [directory / "schedule.json", directory / "sim.log", modinfo]},
                  "functional_correctness_proven": False}
        save(directory / "coverage.json", result)
        return result


class Backends:
    def __init__(self, bundle, design, replay, args):
        self.bundle, self.design, self.replay, self.args = bundle, design, replay, args
        self.haven_dsl = deepcopy(bundle["initial_dsl"])

    def __call__(self, arm, rd, feedback, existing, ordinal):
        args = self.args
        # Identical design/initial-context evidence; backend APIs differ intentionally.
        feedback = {**feedback, "shared_context": {"structured_spec": self.bundle["structured_spec"],
                   "protocol_flows": self.bundle["protocol_flows"], "blueprint": self.bundle["blueprint"],
                   "initial_sequences": self.bundle["sequences"]}}
        save(rd / "feedback.json", feedback)
        if arm == "haven":
            return self.haven(rd, feedback, existing)
        command = [sys.executable, str(ROOT / "experiments/sequence_experiment.py"),
                   "--design", str((Path(self.bundle["replay_config"]).parent / self.replay["design"]).resolve()),
                   "--modinfo", feedback["modinfo"], "--feedback-file", str(rd / "feedback.json"),
                   "--out", str(rd / "generation"), "--model", args.model, "--temperature", str(args.temperature),
                   "--attempts", str(args.attempts), "--timeout", str(args.timeout),
                   "--request-retries", str(args.request_retries), "--jg-time-limit", args.jg_time_limit,
                   "--eda-shell", str(args.eda_shell), "--sequences-per-intent", str(args.sequences_per_intent)]
        if args.env_file:
            command += ["--env-file", str(args.env_file)]
        with (rd / "generation.log").open("w") as log:
            run_process(command, stdout=log, stderr=-2, check=True,
                        timeout=args.attempts * (args.timeout * args.request_retries + 21600))
        result = json.loads((rd / "generation/summary.json").read_text())
        if result["result"]["status"] == "stopped":
            return {"stop": result["result"]["stopReason"]}
        goals = expand_goals(result, Path(self.bundle["replay_config"]), rd / "sampling", args)
        sequences, frames, sampling = [], [], []
        for goal in goals:
            rows = goal.get("sequences", [goal] if goal["status"] == "generated" else [])
            sampling.append({"label": goal["label"], "actual": len(rows), "status": goal.get("sampling_status", goal["status"])})
            for index, row in enumerate(rows):
                new = witness_frames(self.design, self.replay, row, ordinal + len(frames))
                label = f"rvp_{rd.name.replace('-', '_')}_{goal['label']}_{index}"
                sequences.append(render_witness_sequence(self.design, new, label, ordinal + len(frames)))
                frames += new
        return {"sequences": sequences, "frames": frames, "metadata": {"sampling": sampling}}

    def haven(self, rd, feedback, existing):
        from haven.dsl.schema import DSLSequenceSet, BusFieldMapping
        from haven.dsl.codegen import DSLCodegen, dsl_filter_stats
        prompt_template = (self.args.haven_root / "src/haven/prompts/gen_sequence_dsl_gap.md").read_text()
        flow = self.bundle["protocol_flows"]
        fields = self.bundle["blueprint"].get("data_contracts", {}).get("seq_item_fields", [])
        prompt = prompt_template.format(module_name=self.design.top,
            existing_dsl_json=json.dumps(self.haven_dsl), protocol_flows_json=json.dumps(flow),
            coverage_gaps_json=json.dumps(feedback), bus_field_mapping_json=json.dumps(flow.get("bus_field_mapping")),
            seq_item_fields_json=json.dumps(fields))
        prompt += "\nComplete RTL:\n" + generation.design_evidence(self.design)
        prompt += "\nOutput JSON schema:\n" + json.dumps(DSLSequenceSet.model_json_schema())
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
                dsl = DSLSequenceSet.model_validate_json(raw)
                if dsl.module_name != self.design.top:
                    raise ValueError("DSL targets a different DUT")
                if not dsl.sequences:
                    return {"stop": "HAVEN returned no additional DSL sequences"}
                mapping = flow.get("bus_field_mapping")
                dsl_filter_stats.reset()
                codes = DSLCodegen().generate(dsl, BusFieldMapping(**mapping) if mapping else None, fields,
                         seq_item_code=self.bundle["components"]["seq_item"], bfm_configs=[])
                filters = dsl_filter_stats.to_dict()
                save(ad / "codegen-filters.json", filters)
                check_sequence_set(existing + codes)
            except ValueError as error:
                errors.append(str(error))
                save(ad / "error.json", {"error": str(error)})
                continue
            self.haven_dsl["sequences"] += dsl.model_dump()["sequences"]
            save(ad / "dsl.json", dsl.model_dump())
            return {"sequences": codes, "frames": [], "metadata": {"dsl_attempts": attempt, "codegen_filters": filters}}
        raise ValueError("HAVEN DSL generation exhausted schema repair budget")


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
    run.add_argument("--model", default=generation.DEFAULT_MODEL)
    run.add_argument("--temperature", type=float, default=0.3)
    run.add_argument("--timeout", type=int, default=600)
    run.add_argument("--attempts", type=int, default=3)
    run.add_argument("--request-retries", type=int, default=3)
    run.add_argument("--jg-time-limit", default="120s")
    run.add_argument("--eda-shell", type=Path, default=generation.DEFAULT_EDA_SHELL)
    run.add_argument("--env-file", type=Path)
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
    if min(args.attempts, args.timeout, args.request_retries) < 1:
        parser.error("budgets must be positive")
    if not 0 <= args.seed < 2**31:
        parser.error("seed must be a nonnegative 31-bit integer")
    bundle, design, replay = load_bundle(args.bundle, args.haven_root)
    config = json.loads(args.eda_config.read_text())
    if set(config) - {"eda_tools", "eda_env", "simulation"}:
        parser.error("EDA config accepts only eda_tools, eda_env, simulation; model credentials belong in env-file")
    config.setdefault("eda_env", {})["shell"] = str(args.eda_shell.resolve())
    root = args.out.resolve()
    manifest = {"contract": CONTRACT, "bundle": bundle["fingerprint"], "model": args.model, "arms": list(arms),
                "source_sha256": framework_hashes(ROOT), "haven_sha256": bundle["haven_sha256"],
                "eda_sha256": digest(args.eda_config), "seed": args.seed, "sampling": policy,
                "eda_shell_sha256": digest(args.eda_shell.resolve()),
                "rounds": args.rounds, "min_gain": args.min_gain, "target": args.target,
                "attempts": args.attempts, "request_retries": args.request_retries,
                "temperature": args.temperature, "timeout": args.timeout, "jg_time_limit": args.jg_time_limit,
                "scope": [design.top], "metrics": list(METRICS),
                "baseline_policy": "one shared HAVEN Stage-1 simulation; exact same sequence prefix",
                "repair_policy": "frozen shared infrastructure; runtime bench errors fail, never arm-specific patching",
                "bo_policy": "disabled in both arms; only generation backend varies",
                "formal_exclusion_policy": "none in either arm"}
    started, began = begin(root, manifest), time.monotonic()
    summary = {"status": "failed"}
    records = Records(root)
    backends = Backends(bundle, design, replay, args)
    def generate(arm, rd, feedback, existing, ordinal):
        # The report path is shared evidence, but not repeated per sample in the prompt.
        simdir = root / "baseline" if rd.name == "round-1" else rd.parent / f"round-{int(rd.name[6:])-1}/simulation"
        return backends(arm, rd, {**feedback, "modinfo": str(simdir / f"{design.top}_urgReport/modinfo.txt")}, existing, ordinal)
    try:
        with records.phase("paired-session"):
            summary = paired_loop(bundle, root, HavenSimulation(bundle, design, config, args.seed), generate,
                                  rounds=args.rounds, min_gain=args.min_gain, target=args.target, arms=arms)
    except Exception as error:
        summary.update(status="failed", error=str(error))
    finally:
        finish(root, summary, started, began, session_phase="paired-session", **manifest)
    print(json.dumps({"status": summary["status"], "summary": str(root / "summary.json")}))
    return int(summary["status"] != "completed")


if __name__ == "__main__":
    raise SystemExit(main())
