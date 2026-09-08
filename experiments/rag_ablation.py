#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Paired framework-RAG experiment on one fresh, fixed residual task and checked cycle replay."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import statistics
import subprocess
import sys
import time

import sequence_experiment as generation
from cycle_diagnostic import compare
from cycle_replay import CONTRACT as REPLAY_CONTRACT, Replay, baseline_frames, digest, load_config, preflight, save, witness_frames
from sequence_framework import CONTRACT as GENERATION_CONTRACT, ROOT
from goal_coverage import measure_goals
from process_runner import run as run_process
from run_records import Records, begin, finish, framework_hashes
from witness_sampling import sampling_options, sampling_policy, expand_goals

CONTRACT = "rag-ablation-v1"


def jobs(samples: int) -> list[dict]:
    # Alternate request order to reduce systematic time/order bias. Neither arm sees the other's response.
    return [{"name": f"sample-{sample}-{mode}", "sample": sample, "mode": mode, "status": "pending"}
            for sample in range(1, samples + 1)
            for mode in (("off", "local") if sample % 2 else ("local", "off"))]


def distribution(values: list) -> dict | None:
    if not values:
        return None
    return {"n": len(values), "mean": statistics.mean(values), "min": min(values), "max": max(values)}


def summarize(manifest: dict, rows: list[dict]) -> dict:
    if (manifest["contract"] != CONTRACT or manifest["generation_contract"] != GENERATION_CONTRACT
            or manifest["replay_contract"] != REPLAY_CONTRACT or manifest["corpus_scope"] != "framework-only"):
        raise ValueError("unsupported experiment contract")
    if [(r["name"], r["sample"], r["mode"]) for r in rows] != [
            (r["name"], r["sample"], r["mode"]) for r in manifest["jobs"]]:
        raise ValueError("sample plan changed")
    cells = []
    for mode in ("off", "local"):
        arm = [row for row in rows if row["mode"] == mode]
        measured = [row for row in arm if row["status"] == "replayed"]
        known = [row["tokens"] for row in arm if row.get("tokens") is not None]
        cells.append({"mode": mode, "samples_planned": len(arm),
                      "samples_finished": sum(row["status"] != "pending" for row in arm),
                      "failures": sum(row["status"] == "failed" for row in arm),
                      "proof_only": sum(row["status"] == "proof-required" for row in arm),
                      "no_candidates": sum(row["status"] == "no-candidates" for row in arm),
                      "stopped": sum(row["status"] == "stopped" for row in arm),
                      "replays": len(measured), "reported_tokens": sum(known) if known else None,
                      "samples_with_token_accounting": len(known),
                      "elapsed_seconds": distribution([row["elapsed_seconds"] for row in arm if "elapsed_seconds" in row]),
                      "requests": sum(row.get("costs", {}).get("requests", 0) for row in arm),
                      "requests_without_usage": sum(row.get("costs", {}).get("requests_without_usage", 0) for row in arm),
                      "closed_lines": distribution([len(row["delta"]["closed_lines"]) for row in measured]),
                      "score_gain": distribution([row["delta"]["score_gain"] for row in measured])})
    paired = []
    for sample in sorted({row["sample"] for row in rows}):
        pair = {row["mode"]: row for row in rows if row["sample"] == sample and row["status"] == "replayed"}
        if set(pair) == {"off", "local"}:
            paired.append({"sample": sample,
                           "closed_line_advantage": len(pair["local"]["delta"]["closed_lines"]) - len(pair["off"]["delta"]["closed_lines"]),
                           "score_gain_advantage": pair["local"]["delta"]["score_gain"] - pair["off"]["delta"]["score_gain"]})
    complete = all(row["status"] != "pending" for row in rows)
    status = "running" if not complete else "failed" if any(row["status"] == "failed" for row in rows) else "completed"
    return {"contract": CONTRACT, "generation_contract": GENERATION_CONTRACT, "replay_contract": REPLAY_CONTRACT,
            "status": status, "complete": complete,
            "evaluation": "offline-framework-regression" if manifest["offline"] else "fixed-task-paired-rag",
            "cells": cells, "paired": paired, "samples": rows,
            "limitations": "One fixed baseline residual, not adaptive coverage closure or cross-design generalization. "
                           "Means use checked replays only; failures and missing samples remain explicit in denominators. "
                           "Pending proof metadata is never a coverage exclusion. Saved responses do not measure RAG efficacy."}


def run_generation(args, design_path: Path, baseline: dict, row: dict, directory: Path) -> dict:
    command = [sys.executable, str(ROOT / "experiments/sequence_experiment.py"),
               "--design", str(design_path), "--modinfo", baseline["modinfo"], "--out", str(directory / "generation"),
               "--rag", row["mode"], "--model", args.model, "--temperature", str(args.temperature),
               "--attempts", str(args.attempts), "--timeout", str(args.timeout),
               "--request-retries", str(args.request_retries),
               "--sequences-per-intent", str(args.sequences_per_intent),
               "--jg-time-limit", args.jg_time_limit, "--eda-shell", str(args.eda_shell.resolve())]
    if args.resume:
        command += ["--resume"]
    if args.response_file:
        command += ["--response-file", str(args.response_file.resolve())]
    elif args.env_file:
        command += ["--env-file", str(args.env_file.resolve())]
    with (directory / "generation.log").open("a") as log:
        process = run_process(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                             timeout=args.attempts * (args.timeout * args.request_retries + 21600))
    if process.returncode:
        raise ValueError(f"generation failed; inspect {directory / 'generation.log'}")
    result = json.loads((directory / "generation/summary.json").read_text())
    if result["contract"] != GENERATION_CONTRACT:
        raise ValueError("generation contract mismatch")
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--replay-config", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--samples", type=int, default=5, help="independent responses per arm on the same residual")
    parser.add_argument("--attempts", type=int, default=1, help="equal compiler/solver repair budget per response")
    parser.add_argument("--model", default=generation.DEFAULT_MODEL)
    parser.add_argument("--temperature", type=float, default=0.3)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--jg-time-limit", default="120s")
    parser.add_argument("--eda-shell", type=Path, default=generation.DEFAULT_EDA_SHELL)
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--response-file", type=Path, help="offline regression only: same saved response in both arms")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--request-retries", type=int, default=3)
    sampling_options(parser)
    args = parser.parse_args(argv)
    policy = sampling_policy(args)
    if min(args.samples, args.attempts, args.timeout, args.request_retries) < 1:
        parser.error("samples, attempts and timeout must be positive")
    if args.response_file and (not args.response_file.is_file() or args.env_file):
        parser.error("offline regression needs an existing response and must not load model credentials")
    config_path = args.replay_config.resolve()
    design, config = load_config(config_path)
    if design.parameters:
        parser.error("CIRCT IO preflight does not support parameter overrides")
    design_path = (config_path.parent / config["design"]).resolve()
    root = args.out.resolve()
    began = time.monotonic()
    rows = jobs(args.samples)
    manifest = {"contract": CONTRACT, "generation_contract": GENERATION_CONTRACT, "replay_contract": REPLAY_CONTRACT,
                "corpus_scope": "framework-only", "offline": bool(args.response_file),
                "model": "saved-response" if args.response_file else args.model,
                "temperature": args.temperature, "attempts": args.attempts, "jg_time_limit": args.jg_time_limit,
                "request_timeout": args.timeout, "request_retries": args.request_retries,
                "session_phase": "ablation-session",
                "sampling": policy,
                "design": design.record(), "replay_config": config, "config_sha256": digest(config_path),
                "source_sha256": framework_hashes(ROOT),
                "response": {"path": str(args.response_file.resolve()), "sha256": digest(args.response_file)} if args.response_file else None,
                "jobs": [dict(row) for row in rows]}
    started = begin(root, manifest, args.resume)
    records = Records(root)
    session = records.phase("ablation-session")
    session_event = session.__enter__()
    save(root / "summary.json", summarize(manifest, rows))
    try:
        with records.phase("interface-preflight"):
            preflight(design_path, root)
        replay = Replay(design, config, root, args.eda_shell, resume=args.resume)
        replay.compile()
        prefix = baseline_frames(design, config)
        baseline = replay.simulate("baseline", prefix)
        if not baseline["uncovered"]:
            raise ValueError("baseline has no residual task to compare")
    except (ValueError, OSError, subprocess.SubprocessError, KeyError, KeyboardInterrupt) as error:
        session_event["status"] = "failed"
        session.__exit__(None, None, None)
        finish(root, {**summarize(manifest, rows), "status": "failed", "phase": "baseline", "error": str(error)}, started, began, **manifest)
        return 1
    for row in rows:
        directory = root / row["name"]
        directory.mkdir(exist_ok=args.resume)
        cached = directory / "result.json"
        if args.resume and cached.exists():
            old = json.loads(cached.read_text())
            if old["status"] not in ("pending", "failed"):
                row.update(old)
                continue
        phase = "generation"
        interrupted = False
        sample_began = time.monotonic()
        try:
            result = run_generation(args, design_path, baseline, row, directory)
            row.update({"tokens": result["tokens"], "attempts": result["attempts"],
                        "generation_elapsed_seconds": result.get("elapsed_seconds"),
                        **{key: result[key] for key in ("started_utc", "finished_utc", "costs") if key in result}})
            solved = result["result"]
            row["pending_proofs"] = solved["proofObligations"]
            goals = solved["goals"]
            if solved.get("status") == "stopped":
                row.update(status="stopped", stop_reason=solved["stopReason"], ut_count=0)
                save(directory / "result.json", row)
                continue
            if solved.get("utCount") != 1 or not goals:
                raise ValueError("each sample requires exactly one UT with Gen goals")
            row["ut_count"] = 1
            row["goal_count"] = len(goals)
            # ujson preserves Scala Long values as decimal strings.
            row["candidate_solver_ms"] = sum(int(item["ms"]) for item in goals)
            phase = "sampling"
            goals = expand_goals(result, config_path, directory / "sampling", args)
            row["sampling"] = [{"label": g["label"], "status": g.get("sampling_status", g["status"]),
                "requested": args.sequences_per_intent, "actual": len(g.get("sequences", [g] if g["status"] == "generated" else [])),
                "error": g.get("sampling_error")} for g in goals]
            phase = "replay"
            goal_rows, additions = measure_goals(replay, design, config, prefix, baseline, goals,
                                                rows.index(row) + 1, directory)
            row["goals"] = goal_rows
            row["sequence_count"] = sum(g["status"] == "replayed" for g in goal_rows)
            if not additions:
                row.update(status="no-candidates", elapsed_seconds=time.monotonic() - sample_began)
                save(directory / "result.json", row)
                continue
            measured = replay.simulate(row["name"] + "-replay", prefix + additions) if additions else baseline
            row.update({"status": "replayed", "delta": compare(baseline, measured),
                        "coverage": measured["percent"], "replay": measured["replay"]})
        except (ValueError, TypeError, OSError, subprocess.SubprocessError, KeyError, KeyboardInterrupt) as error:
            interrupted = isinstance(error, KeyboardInterrupt)
            row.update({"status": "failed", "phase": phase, "error": str(error)})
            record = directory / "generation/summary.json"
            if record.is_file():
                try:
                    failed = json.loads(record.read_text())
                    row.update({key: failed[key] for key in ("tokens", "costs", "elapsed_seconds") if key in failed})
                except ValueError:
                    pass
        row["elapsed_seconds"] = time.monotonic() - sample_began
        save(directory / "result.json", row)
        save(root / "summary.json", summarize(manifest, rows))
        if interrupted:
            break
    summary = summarize(manifest, rows)
    summary["baseline"] = baseline
    if any(row["status"] == "failed" for row in rows):
        summary["status"] = "failed"
    if summary["status"] == "failed":
        session_event["status"] = "failed"
    session.__exit__(None, None, None)
    finish(root, summary, started, began, **manifest)
    print(json.dumps(summary))
    return int(any(row["status"] == "failed" for row in rows))


if __name__ == "__main__":
    raise SystemExit(main())
