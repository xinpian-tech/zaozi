#!/usr/bin/env python3
"""Sample diverse witnesses of a frozen, already validated UT; no model calls.

JasperGold Visualize soft input preferences select different solutions without
changing the original cover or introducing environment assumptions. This is
heuristic sampling, not uniform sampling or exhaustive solution enumeration.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import random
import re
import shlex
import subprocess
import time

from cycle_replay import (Replay, WITNESS_CONTRACT, baseline_frames, digest,
                          load_config, read_vcd, witness_frames)
from process_runner import run
from run_records import Records, begin, fingerprint, finish, framework_hashes, save
from sequence_framework import ROOT, check_saved_sources, parse_response
from ut_validation import validate

ASSERTION = re.compile(r"(\w+):((?:[^\n]*\n)?\s*)assert property \((.*?)\);", re.S)


def select_cover(sv, labels, label):
    matches = list(ASSERTION.finditer(sv))
    found = [m[1] for m in matches]
    if len(set(found)) != len(found) or set(found) != set(labels) or label not in found:
        raise ValueError("UT assertion labels differ from prepared job")
    return ASSERTION.sub(lambda m: f"{m[1]}:{m[2]}cover property (not ({m[3]}));"
                         if m[1] == label else "", sv)


def tcl_word(value):
    value = str(value)
    if any(c in value for c in "{}\n\r\\"):
        raise ValueError("unsupported Tcl path or identifier")
    return "{" + value + "}"


def render_sampling(job, label, sv, out, drives, cycles, count, seed, limit):
    """Stable per-label preferences; count only controls the prefix length."""
    rng = random.Random(f"rvprobe-soft-input-v1:{seed}:{label}")
    analyze = []
    include = "" if job["include"] is None else tcl_word("+incdir+" + job["include"]) + " "
    for suffix, flag in ((True, "-v2k"), (False, "-sv12")):
        paths = [Path(p) for p in [*job["rtl"], str(sv)] if (Path(p).suffix == ".v") == suffix]
        if paths:
            analyze.append(f"analyze {flag} {include}" + " ".join(map(tcl_word, paths)))
    # Whole-port preferences per beat avoid quadratic bit-level soft objectives.
    preferences = []
    for attempt in range(max(0, count - 1) * 2):
        terms = []
        for cycle in range(1, cycles + 1):
            for port in drives:
                name, width = port["name"], port["width"]
                if not re.fullmatch(r"[A-Za-z_]\w*", name):
                    raise ValueError("sampling requires simple input identifiers")
                terms.append((f"{name} == {width}'h{rng.getrandbits(width):x}", cycle))
        preferences.append(terms)
    # All attempts stay at the original witness length. A trace cannot become
    # different merely by appending irrelevant extra cycles.
    script = ["clear -all", *analyze, f"elaborate -top {job['top']}", "clock clock", "reset reset",
              f"set_prove_time_limit {limit}", "prove -all", 'set target ""',
              "foreach p [get_property_list -include {type cover}] {",
              f"  if {{[string match {{*::{job['top']}.{label}}} $p] || [string equal {{{job['top']}.{label}}} $p]}} {{ set target $p }}",
              "}", 'if {$target == ""} { error "generation cover missing" }',
              'if {[get_property_info $target -list status] != "covered"} { error "generation cover not covered" }',
              "set_trace_optimization standard",
              'if {[get_trace_optimization] != "standard"} { error "full-design trace required" }',
              "visualize -cover -property $target -window visualize:0",
              f"visualize -min_length {cycles} -window visualize:0",
              f"visualize -max_length {cycles} -window visualize:0",
              "set accepted 0", "set seen [dict create]"]
    for attempt, terms in enumerate(preferences):
        script += [f"if {{$accepted < {count - 1}}} {{"]
        for index, (expression, cycle) in enumerate(terms):
            if attempt:
                script.append(f"visualize -remove_conf rvprobe_soft_{index} -window visualize:0")
            script.append(f"visualize -force -soft {{{expression}}} {cycle} -name rvprobe_soft_{index} -window visualize:0")
        script += [f"set timing [time {{set status [visualize -replot -force -silent -proof_time {limit} -window visualize:0]}}]",
                   f'puts "JGSAMPLE_STATUS {attempt} $status"',
                   'if {$status != "covered"} { error "sampling did not preserve a covered target" }',
                   'if {[visualize -get_type -window visualize:0] != "cover"} { error "not a cover trace" }',
                   "set signature [list]"]
        for port in drives:
            script.append(f"lappend signature [visualize -get_value {port['name']} {{1:$}} -radix 2 -window visualize:0]")
        script += ["if {![dict exists $seen $signature]} {", "dict set seen $signature 1",
                   f"visualize -save -vcd {tcl_word(out / f'sample-{attempt}.vcd')} -force -window visualize:0",
                   f"visualize -save -config_only -window visualize:0 -force {tcl_word(out / f'sample-{attempt}.config.tcl')}",
                   "incr accepted", f'puts "JGSAMPLE_ACCEPT {attempt} $accepted [expr {{int([lindex $timing 0] / 1000)}}]"',
                   "} else {", f'puts "JGSAMPLE_DUPLICATE {attempt}"', "}", "}"]
    script += ['puts "JGTRACE [get_trace_optimization]"', 'puts "JGDONE"', "exit"]
    return "\n".join(script) + "\n"


def validate_sample_config(path, top, label, cycles):
    commands = [line.strip() for line in path.read_text().splitlines() if line.strip() and not line.startswith("#")]
    target = f"visualize -set_target -cover -property {{<embedded>::{top}.{label}}}"
    if commands.count(target) != 1:
        raise ValueError("sample configuration lost the original cover target")
    fixed = {"proc visualize_save {} {", "visualize -new_window", "task -set <embedded>", target,
             f"visualize -min_length {cycles}", f"visualize -max_length {cycles}",
             "visualize -replot", "}", "visualize_save"}
    soft = re.compile(r"visualize -force -soft \{[A-Za-z_]\w* == \d+'h[0-9a-f]+\} (\d+):(\d+) -name rvprobe_soft_\d+")
    for command in commands:
        if command not in fixed:
            match = soft.fullmatch(command)
            if not match or not 1 <= int(match[1]) == int(match[2]) <= cycles:
                raise ValueError("unexpected hard constraint or command in sample configuration")
    # JG omits its default min_length=1 from saved configurations. The max
    # remains explicit, and the imported nonempty VCD must still have exactly
    # the original number of cycles; no horizon relaxation is permitted.
    required = {f"visualize -max_length {cycles}"}
    if cycles != 1:
        required.add(f"visualize -min_length {cycles}")
    if not required <= set(commands):
        raise ValueError("sample configuration changed witness length")


def import_sample(path, label, design):
    trace = read_vcd(path)
    beats = []
    for sample in trace:
        drive = {}
        for port in design.data_ports:
            if port.direction == "input":
                value, mask = sample.get(port.name, sample.get("dut/" + port.name, (0, 0)))
                # Refuse ambiguous completions: do not mutate unknown inputs after solving.
                if mask != (1 << port.width) - 1:
                    raise ValueError(f"sample has unknown input bits: {port.name}")
                drive[port.name] = str(value)
        beats.append(drive)
    stimulus = path.with_suffix(".json")
    save(stimulus, beats)
    return {"label": label, "status": "generated", "cycles": len(beats),
            "witnessContract": WITNESS_CONTRACT, "witnessFile": str(path), "witnessSha256": digest(path),
            "stimulusFile": str(stimulus), "stimulusSha256": digest(stimulus),
            "inputFingerprint": fingerprint(beats), "origin": "soft-input-resample"}


def frozen_inputs(source, config_path):
    source = source.resolve()
    design, config = load_config(config_path)
    generated = source.parent / "sources"
    response = parse_response((generated / "response.json").read_text())
    check_saved_sources(generated, design, response)
    job = json.loads((source / "prepared.json").read_text())
    if job["fingerprint"] != fingerprint({k: v for k, v in job.items() if k != "fingerprint"}):
        raise ValueError("prepared job fingerprint changed")
    sv = Path(job["sv"])
    if digest(sv) != job["svSha256"] or digest(generated / "ModelUT.scala") != job["sourceSha256"]:
        raise ValueError("frozen UT source or lowering changed")
    if json.loads((generated / "design.json").read_text()) != design.record():
        raise ValueError("frozen design inputs changed")
    if job["rtl"] != [str(p) for p in design.sources] or job["labels"] != response["ut"]["generationLabels"]:
        raise ValueError("prepared job differs from frozen source")
    validate(sv.read_text(), design, job["top"], job["labels"])
    goals = []
    for label in job["labels"]:
        row = json.loads((source / label / "goal.json").read_text())
        if row["utSourceSha256"] != job["sourceSha256"]:
            raise ValueError("sampling requires goals from this UT")
        if row["status"] != "generated":
            goals.append(row)
            continue
        for field in ("witness", "stimulus"):
            if digest(Path(row[field + "File"])) != row[field + "Sha256"]:
                raise ValueError("original witness artifact changed")
        witness_frames(design, config, row, 0)
        row["inputFingerprint"] = fingerprint(json.loads(Path(row["stimulusFile"]).read_text()))
        row["origin"] = "frozen-original"
        goals.append(row)
    return design, config, job, goals


def sample_goal(job, goal, design, config, directory, count, seed, limit, eda_shell, *, resume=False):
    """One original plus diverse solutions; a shared implementation for all experiment entry points."""
    if not 1 <= count <= 256 or not re.fullmatch(r"[1-9][0-9]*s", limit):
        raise ValueError("invalid sampling count or time limit")
    directory = Path(directory).resolve()
    label = goal["label"]
    goal = dict(goal)
    for field in ("witness", "stimulus"):
        if digest(Path(goal[field + "File"])) != goal[field + "Sha256"]:
            raise ValueError("original witness artifact changed")
    goal["inputFingerprint"] = fingerprint(json.loads(Path(goal["stimulusFile"]).read_text()))
    goal["origin"] = "frozen-original"
    pool = [goal]
    records = Records(directory.parent)
    env = {k: v for k, v in os.environ.items() if k not in (
        "RVPROBE_LLM_API_KEY", "RVPROBE_LLM_BASE_URL", "OPENAI_API_KEY", "OPENAI_BASE_URL")}
    manifest = directory / "pool.json"
    identity = fingerprint({"job": job["fingerprint"], "original": goal["inputFingerprint"], "count": count,
                            "seed": seed, "limit": limit, "replay": config, "method": "soft-input-resample-v1"})
    if resume and manifest.exists():
        if json.loads((directory / "sampling-inputs.json").read_text())["fingerprint"] != identity:
            raise ValueError("sampling resume configuration changed")
        pool = json.loads(manifest.read_text())
        for row in pool:
            if digest(Path(row["stimulusFile"])) != row["stimulusSha256"]:
                raise ValueError("cached sampled stimulus changed")
            witness_frames(design, config, row, 0)
            if row["origin"] == "soft-input-resample":
                conf = Path(row["configFile"])
                if digest(conf) != row["configSha256"]:
                    raise ValueError("cached sampler configuration changed")
                validate_sample_config(conf, job["top"], label, goal["cycles"])
        if pool[0]["inputFingerprint"] != goal["inputFingerprint"] or len({r["inputFingerprint"] for r in pool}) != len(pool):
            raise ValueError("cached pool changed original witness or contains duplicates")
    elif count > 1:
        if resume and directory.exists():
            directory.rename(directory.with_name(label + f".interrupted-{time.time_ns()}"))
        directory.mkdir(parents=True, exist_ok=False)
        save(directory / "sampling-inputs.json", {"fingerprint": identity})
        sv = directory / Path(job["sv"]).name
        sv.write_text(select_cover(Path(job["sv"]).read_text(), job["labels"], label))
        drives = [p for p in job["abi"]["ports"] if p["role"] == "Drive"]
        tcl = directory / "sample.tcl"
        tcl.write_text(render_sampling(job, label, sv, directory, drives, goal["cycles"],
                                      count, seed, limit))
        with records.phase("goal-sampling", label=label) as event:
            with (directory / "jg.log").open("w") as log:
                run([str(eda_shell.resolve()), "-c", shlex.join([
                    "jg", "-batch", "-tcl", str(tcl), "-proj", str(directory / "jgproj")])],
                    cwd=directory, env=env, stdout=log, stderr=subprocess.STDOUT, check=True,
                    timeout=300 + 2 * count * (int(limit[:-1]) + 5))
            log = (directory / "jg.log").read_text()
            lines = set(log.splitlines())
            if not {"JGTRACE standard", "JGDONE"} <= lines or "WVS028" in log:
                raise ValueError("sampling lacks a completed full-design trace export")
            accepted = re.findall(r"^JGSAMPLE_ACCEPT (\d+) \d+ (\d+)$", log, re.M)
            seen = {goal["inputFingerprint"]}
            for attempt, replot_ms in accepted:
                conf = directory / f"sample-{attempt}.config.tcl"
                validate_sample_config(conf, job["top"], label, goal["cycles"])
                row = import_sample(directory / f"sample-{attempt}.vcd", label, design)
                if row["cycles"] != goal["cycles"]:
                    raise ValueError("sampled witness length changed")
                row.update(attempt=int(attempt), replot_ms=int(replot_ms),
                           configFile=str(conf), configSha256=digest(conf))
                witness_frames(design, config, row, 0)
                if row["inputFingerprint"] not in seen:
                    seen.add(row["inputFingerprint"])
                    pool.append(row)
            event.update(attempts=len(re.findall(r"^JGSAMPLE_STATUS ", log, re.M)),
                         distinct_sequences=len(pool))
        save(manifest, pool)
    return pool


def sampling_options(parser):
    parser.add_argument("--sequences-per-intent", type=int, default=4)
    parser.add_argument("--sampling-seed", type=int, default=20260906)
    parser.add_argument("--sampling-time-limit", default="30s")


def sampling_policy(args):
    if not 1 <= args.sequences_per_intent <= 256 or not re.fullmatch(r"[1-9][0-9]*s", args.sampling_time_limit):
        raise ValueError("invalid sampling count or time limit")
    return {"method": "soft-input-resample-v1", "sequences_per_intent": args.sequences_per_intent,
            "seed": args.sampling_seed, "time_limit": args.sampling_time_limit}


def expand_goals(result, config_path, directory, args):
    """Keep Gen identity, retain good originals on sampling failure, and report shortages honestly."""
    goals = result["result"]["goals"]
    if args.sequences_per_intent == 1:
        return goals
    source = Path(result["sources"]).parent / "solve"
    design, config, job, originals = frozen_inputs(source, config_path)
    if [g["label"] for g in goals] != [g["label"] for g in originals]:
        raise ValueError("generation result and prepared goal list differ")
    expanded = []
    for goal in originals:
        row = dict(goal)
        row["requested_sequences"] = args.sequences_per_intent
        if goal["status"] == "generated":
            try:
                row["sequences"] = sample_goal(job, goal, design, config, Path(directory) / goal["label"],
                    args.sequences_per_intent, args.sampling_seed, args.sampling_time_limit, args.eda_shell,
                    resume=args.resume)
                row["sampling_status"] = "complete" if len(row["sequences"]) == args.sequences_per_intent else "partial"
            except (ValueError, OSError, subprocess.SubprocessError, KeyError) as error:
                row.update(sequences=[goal], sampling_status="failed", sampling_error=str(error))
        else:
            row.update(sequences=[], sampling_status="not-generated")
        expanded.append(row)
        save(Path(directory) / "sampling.json", expanded)
    return expanded


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--source-solve", required=True, type=Path)
    ap.add_argument("--config", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--counts", type=int, nargs="+", default=[1, 4, 16, 64])
    ap.add_argument("--seed", type=int, default=20260906)
    ap.add_argument("--jg-time-limit", default="30s")
    ap.add_argument("--eda-shell", type=Path, default=ROOT / "experiments/eda-shell")
    ap.add_argument("--labels", nargs="+", help="subset for smoke tests; recorded, never implicit")
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()
    counts = sorted(set(args.counts))
    if not counts or counts[0] != 1 or counts[-1] > 256:
        ap.error("counts must start with 1 and be at most 256")
    if not re.fullmatch(r"[1-9]\d*s", args.jg_time_limit):
        ap.error("time limit must be positive seconds, e.g. 30s")
    design, config, job, goals = frozen_inputs(args.source_solve, args.config)
    if any(g["status"] != "generated" for g in goals):
        ap.error("standalone prefix comparison requires all goals to have an original witness")
    if args.labels:
        if not set(args.labels) <= {g["label"] for g in goals}:
            ap.error("unknown labels")
        goals = [g for g in goals if g["label"] in args.labels]
    out = args.out.resolve()
    comparison = {"method": "soft-input-resample-v1", "source_solve": str(args.source_solve.resolve()),
                  "source_job": job, "design": design.record(), "replay": config,
                  "originals": goals, "counts": counts, "seed": args.seed,
                  "time_limit": args.jg_time_limit, "framework": framework_hashes(ROOT),
                  "model_requests": 0, "new_model_tokens": 0,
                  "sampling_note": "whole-port soft preferences on every original witness beat; fixed length; deduplicated inputs; not uniform or exhaustive"}
    started = begin(out, comparison, resume=args.resume)
    began = time.monotonic()
    records = Records(out)
    summary = {"status": "running", "cells": [], "pools": {}}
    try:
        with records.phase("sampling-session"):
            replay = Replay(design, config, out / "replay", args.eda_shell, resume=args.resume)
            replay.root.mkdir(exist_ok=True)
            replay.compile()
            base = baseline_frames(design, config)
            pools = {g["label"]: [g] for g in goals}
            # Measure the exact historical witness set before any sampling.
            def measure(count):
                frames = list(base)
                sizes = {}
                selected = []
                for goal in goals:
                    rows = pools[goal["label"]][:count]
                    sizes[goal["label"]] = len(rows)
                    for row in rows:
                        selected.append(row)
                        frames += witness_frames(design, config, row, len(selected))
                replay_began = time.monotonic()
                coverage = replay.simulate(f"count-{count}", frames)
                witness_coverage = replay.simulate(f"count-{count}-witness-only", [f for f in frames if f["kind"] != "drain"])
                cell = {"requested_per_intent": count, "actual_per_intent": sizes,
                        "sequences": sum(sizes.values()), "complete": all(v == count for v in sizes.values()),
                        "coverage": coverage, "witness_only_coverage": witness_coverage,
                        "measured_replay_seconds": time.monotonic() - replay_began,
                        "accepted_replot_seconds": sum(r.get("replot_ms", 0) for r in selected) / 1000,
                        "new_samples": sum(r["origin"] != "frozen-original" for r in selected),
                        "cost_note": "Nested prefixes reuse original witnesses and one maximum-size sampling run; replot time excludes JG startup, initial prove, duplicate attempts and export; not independent arm wall time."}
                summary["cells"].append(cell)
                save(out / "progress.json", summary)
                print(json.dumps({"count": count, "sequences": cell["sequences"], "percent": coverage["percent"]}), flush=True)
            measure(1)
            for goal in goals:
                label = goal["label"]
                directory = out / "samples" / label
                pools[label] = sample_goal(job, goal, design, config, directory, counts[-1], args.seed,
                                           args.jg_time_limit, args.eda_shell, resume=args.resume)
                summary["pools"][label] = len(pools[label])
                save(out / "progress.json", summary)
                print(json.dumps({"label": label, "sequences": len(pools[label])}), flush=True)
            for count in counts[1:]:
                measure(count)
            summary.update(status="complete" if all(c["complete"] for c in summary["cells"]) else "partial",
                           final=summary["cells"][-1]["coverage"])
    except BaseException as error:
        summary.update(status="failed", error=str(error))
        raise
    finally:
        finish(out, summary, started, began, session_phase="sampling-session",
               method="soft-input-resample-v1", counts=counts, seed=args.seed, source_ut_sha256=job["sourceSha256"])


if __name__ == "__main__":
    main()
