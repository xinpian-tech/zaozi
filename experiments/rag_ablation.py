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

import sequence_experiment as generation
from coverage_flow import compare
from cycle_replay import CONTRACT as REPLAY_CONTRACT, Replay, baseline_frames, digest, load_config, preflight, save, witness_frames
from sequence_framework import CONTRACT as GENERATION_CONTRACT, ROOT

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
                      "replays": len(measured), "reported_tokens": sum(known) if known else None,
                      "samples_with_token_accounting": len(known),
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
               "--jg-time-limit", args.jg_time_limit, "--eda-shell", str(args.eda_shell.resolve())]
    if args.response_file:
        command += ["--response-file", str(args.response_file.resolve())]
    elif args.env_file:
        command += ["--env-file", str(args.env_file.resolve())]
    with (directory / "generation.log").open("w") as log:
        process = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                 timeout=args.attempts * (args.timeout + 3600))
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
    args = parser.parse_args(argv)
    if min(args.samples, args.attempts, args.timeout) < 1:
        parser.error("samples, attempts and timeout must be positive")
    if args.response_file and (not args.response_file.is_file() or args.env_file):
        parser.error("offline regression needs an existing response and must not load model credentials")
    config_path = args.replay_config.resolve()
    design, config = load_config(config_path)
    if design.parameters:
        parser.error("CIRCT IO preflight does not support parameter overrides")
    design_path = (config_path.parent / config["design"]).resolve()
    root = args.out.resolve()
    root.mkdir(parents=True, exist_ok=False)
    rows = jobs(args.samples)
    manifest = {"contract": CONTRACT, "generation_contract": GENERATION_CONTRACT, "replay_contract": REPLAY_CONTRACT,
                "corpus_scope": "framework-only", "offline": bool(args.response_file),
                "model": "saved-response" if args.response_file else args.model,
                "temperature": args.temperature, "attempts": args.attempts, "jg_time_limit": args.jg_time_limit,
                "design": design.record(), "replay_config": config, "config_sha256": digest(config_path),
                "source_sha256": {name: digest(ROOT / "experiments" / name) for name in (
                    "rag_ablation.py", "coverage_flow.py", "cycle_replay.py", "sequence_framework.py", "sequence_experiment.py")},
                "response": {"path": str(args.response_file.resolve()), "sha256": digest(args.response_file)} if args.response_file else None,
                "jobs": [dict(row) for row in rows]}
    save(root / "manifest.json", manifest)
    save(root / "summary.json", summarize(manifest, rows))
    try:
        preflight(design_path, root)
        replay = Replay(design, config, root, args.eda_shell)
        replay.compile()
        prefix = baseline_frames(design, config)
        baseline = replay.simulate("baseline", prefix)
        if not baseline["uncovered"]:
            raise ValueError("baseline has no residual task to compare")
    except (ValueError, OSError, subprocess.SubprocessError, KeyError) as error:
        save(root / "summary.json", {**summarize(manifest, rows), "status": "failed", "phase": "baseline", "error": str(error)})
        return 1
    for row in rows:
        directory = root / row["name"]
        directory.mkdir()
        phase = "generation"
        try:
            result = run_generation(args, design_path, baseline, row, directory)
            row.update({"tokens": result["tokens"], "attempts": result["attempts"]})
            solved = result["result"]
            row["pending_proofs"] = solved["proofObligations"]
            intents = solved["intents"]
            if not intents:
                row["status"] = "proof-required" if row["pending_proofs"] else "no-candidates"
            else:
                if solved["status"] != "generated" or any(item["status"] != "generated" for item in intents):
                    raise ValueError("candidate batch did not produce all witnesses")
                # ujson preserves Scala Long values as decimal strings.
                row["candidate_solver_ms"] = sum(int(item["ms"]) for item in intents)
                phase = "replay"
                frames = list(prefix)
                for index, intent in enumerate(intents):
                    frames += witness_frames(design, config, intent, index)
                measured = replay.simulate(row["name"] + "-replay", frames)
                row.update({"status": "replayed", "delta": compare(baseline, measured),
                            "coverage": measured["percent"], "replay": measured["replay"]})
        except (ValueError, TypeError, OSError, subprocess.SubprocessError, KeyError) as error:
            row.update({"status": "failed", "phase": phase, "error": str(error)})
            record = directory / "generation/summary.json"
            if record.is_file():
                try:
                    row["tokens"] = json.loads(record.read_text()).get("tokens")
                except ValueError:
                    pass
        save(directory / "result.json", row)
        save(root / "summary.json", summarize(manifest, rows))
    summary = summarize(manifest, rows)
    print(json.dumps(summary))
    return int(any(row["status"] == "failed" for row in rows))


if __name__ == "__main__":
    raise SystemExit(main())
