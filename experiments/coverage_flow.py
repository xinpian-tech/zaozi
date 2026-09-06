#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Baseline -> generation/repair -> checked cycle replay -> residual feedback."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys

import sequence_experiment as generation
from cycle_replay import CONTRACT, Replay, baseline_frames, digest, load_config, preflight, save, witness_frames
from sequence_framework import ROOT


def compare(before: dict, after: dict) -> dict:
    if set(before["bins"]) != set(after["bins"]):
        raise ValueError("coverage metric universe changed")
    for metric in before["bins"]:
        old_total, old_hit = before["bins"][metric]
        new_total, new_hit = after["bins"][metric]
        if old_total != new_total or new_hit < old_hit:
            raise ValueError("coverage universe changed or cumulative replay regressed")
    old_lines = {line for line, _ in before["uncovered"]}
    new_lines = {line for line, _ in after["uncovered"]}
    if not new_lines <= old_lines:
        raise ValueError("cumulative replay introduced new residual lines")
    return {"closed_lines": sorted(old_lines - new_lines), "remaining_lines": sorted(new_lines),
            "score_gain": after["score"] - before["score"]}


def write_report(root: Path, summary: dict) -> None:
    lines = ["# 覆盖闭环结果", "", f"状态：`{summary['status']}`；契约：`{CONTRACT}`。", ""]
    if summary["status"] == "failed":
        lines += [f"失败阶段：`{summary['phase']}`。", "", summary["error"]]
    else:
        before, after = summary["baseline"], summary["final"]
        lines += [f"停止原因：`{summary['stop_reason']}`；覆盖闭合：`{summary['coverage_closed']}`。", "",
                  "| 指标 | baseline | 最终累计回放 |", "|---|---:|---:|"]
        for metric in before["percent"]:
            lines.append(f"| {metric} | {before['percent'][metric]:.2f}% | {after['percent'][metric]:.2f}% |")
        lines += ["", f"综合分：{before['score']:.2f} → {after['score']:.2f}。",
                  f"新覆盖行：{summary['delta']['closed_lines']}。",
                  f"剩余行：{summary['delta']['remaining_lines']}。", "",
                  "综合分按实际存在的指定指标取等权均值；不同基线/回放契约之间不能直接比较。",
                  f"本次服务报告 token 合计：{summary['tokens']}；saved-response 回归不计在线样本。"]
    lines += ["", "## 证据边界", "",
              "- 一次编译、固定 baseline 前缀、累计 witness；每条独立复位，一拍一驱动。",
              "- 输入、复位和采样间隔须与 schedule 一致；VCD 可见输出仅检查已知位。",
              "- 排空周期在 witness 之外；输入型意图可能没有输出检查，不能当作全状态/功能正确性证明。",
              "- proofObligations 只作为 pending 元数据，不排除残余、不计作不可达证明。", "",
              "配置、源码哈希和离线输入来源见 manifest.json；逐轮 prompt、反馈、求解及回放均保存在本目录。", ""]
    (root / "REPORT.md").write_text("\n".join(lines))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--replay-config", type=Path, default=ROOT / "experiments/designs/alu_replay.json")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--patience", type=int, default=1, help="stop after this many rounds without newly covered lines")
    parser.add_argument("--attempts", type=int, default=3, help="compiler/solver feedback attempts per round")
    parser.add_argument("--response-file", type=Path, action="append", default=[],
                        help="saved response for each successive round; repeat flag for multi-round offline validation")
    parser.add_argument("--model", default=generation.DEFAULT_MODEL)
    parser.add_argument("--temperature", type=float, default=0.3)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--jg-time-limit", default="120s")
    parser.add_argument("--eda-shell", type=Path, default=generation.DEFAULT_EDA_SHELL)
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--rag", choices=("local", "off"), default="local")
    args = parser.parse_args(argv)
    if min(args.rounds, args.patience, args.attempts, args.timeout) < 1:
        parser.error("rounds, patience, attempts and timeout must be positive")
    config_path = args.replay_config.resolve()
    design, config = load_config(config_path)
    if design.parameters:
        parser.error("cycle flow requires CIRCT IO preflight, which does not yet support parameter overrides")
    if any(not path.is_file() for path in args.response_file):
        parser.error("saved response does not exist")
    if args.response_file and args.env_file:
        parser.error("offline replay must not load model credentials")
    root = args.out.resolve()
    root.mkdir(parents=True, exist_ok=False)
    design_path = (config_path.parent / config["design"]).resolve()
    save(root / "replay-config.json", config)
    save(root / "design.json", design.record())
    save(root / "manifest.json", {
        "contract": CONTRACT, "generation_contract": generation.CONTRACT, "config_sha256": digest(config_path),
        "model": "saved-response" if args.response_file else args.model,
        "round_budget": args.rounds, "repair_budget_per_round": args.attempts, "patience": args.patience,
        "source_sha256": {name: digest(ROOT / "experiments" / name) for name in (
            "coverage_flow.py", "cycle_replay.py", "sequence_framework.py", "sequence_experiment.py")},
        "saved_responses": [{"path": str(path.resolve()), "sha256": digest(path)} for path in args.response_file],
        "baseline": config["baseline"], "score_scope": design.top,
        "metrics": ["line", "cond", "toggle", "branch"],
        "proof_policy": "pending metadata only; never exclude lines without an independently verified property",
    })
    rounds, pending = [], []
    phase = "interface"
    summary = {"status": "running", "contract": CONTRACT, "rounds": rounds, "pending_proofs": pending}
    save(root / "summary.json", summary)
    try:
        print("flow: CIRCT interface preflight", file=sys.stderr, flush=True)
        preflight(design_path, root)
        phase = "baseline"
        replay = Replay(design, config, root, args.eda_shell)
        replay.compile()
        frames = baseline_frames(design, config)
        print("flow: fresh deterministic baseline", file=sys.stderr, flush=True)
        baseline = replay.simulate("baseline", frames)
        current = baseline
        stale = 0
        stop = "round_budget"
        for number in range(1, args.rounds + 1):
            if not current["uncovered"]:
                stop = "covered"
                break
            if args.response_file and number > len(args.response_file):
                stop = "saved_responses_exhausted"
                break
            round_dir = root / f"round-{number}"
            round_dir.mkdir()
            feedback = {"round": number, "current_coverage": current["percent"],
                        "remaining": current["uncovered"],
                        "previous_round": rounds[-1] if rounds else None,
                        "replay_contract": CONTRACT,
                        "instruction": "Derive new intents from the current residual; do not repeat already covered targets. "
                                       "Pending proof metadata is not a proof or a coverage exclusion."}
            save(round_dir / "feedback.json", feedback)
            command = ["--design", str(design_path), "--modinfo", current["modinfo"],
                       "--out", str(round_dir / "generation"), "--attempts", str(args.attempts),
                       "--jg-time-limit", args.jg_time_limit, "--eda-shell", str(args.eda_shell.resolve()),
                       "--model", args.model, "--temperature", str(args.temperature),
                       "--timeout", str(args.timeout), "--rag", args.rag,
                       "--feedback-file", str(round_dir / "feedback.json")]
            if args.response_file:
                command += ["--response-file", str(args.response_file[number - 1].resolve())]
            elif args.env_file:
                command += ["--env-file", str(args.env_file.resolve())]
            phase = f"round-{number}/generation"
            print(f"flow: round {number}, {len(current['uncovered'])} residual lines", file=sys.stderr, flush=True)
            # The generation subprocess owns model credentials. Replay never loads them.
            with (round_dir / "generation.log").open("w") as log:
                generated = subprocess.run([sys.executable, str(ROOT / "experiments/sequence_experiment.py"), *command],
                                           cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                           timeout=args.attempts * (args.timeout + 3600))
            result = json.loads((round_dir / "generation/summary.json").read_text())
            if generated.returncode:
                raise ValueError(f"generation failed; inspect {round_dir / 'generation.log'}")
            solved = result.get("result", {})
            pending += [{"round": number, **proof} for proof in solved.get("proofObligations", [])]
            intents = solved.get("intents", [])
            row = {"round": number, "generation_status": result["status"],
                   "generation_summary": str(round_dir / "generation/summary.json"),
                   "tokens": result["tokens"], "compiler_attempts": result["attempts"],
                   "intent_labels": [intent["label"] for intent in intents]}
            if not intents:
                rounds.append(row)
                stop = "proof_required" if solved.get("proofObligations") else "no_candidates"
                break
            phase = f"round-{number}/replay"
            additions = []
            for index, intent in enumerate(intents):
                if intent["status"] != "generated":
                    raise ValueError("only fully generated candidate batches can be replayed")
                additions += witness_frames(design, config, intent, number * 100 + index)
            frames += additions
            measured = replay.simulate(f"replay-{number}", frames)
            delta = compare(current, measured)
            row.update(delta)
            row.update({"coverage": measured["percent"], "score": measured["score"],
                        "modinfo": measured["modinfo"], "replay": measured["replay"]})
            rounds.append(row)
            save(round_dir / "result.json", row)
            current = measured
            summary.update({"baseline": baseline, "final": current})
            save(root / "summary.json", summary)
            print(f"flow: round {number} closed {delta['closed_lines']}; remaining {len(current['uncovered'])}",
                  file=sys.stderr, flush=True)
            stale = stale + 1 if not delta["closed_lines"] else 0
            if not current["uncovered"]:
                stop = "covered"
                break
            if stale >= args.patience:
                stop = "no_line_progress"
                break
        summary.update({"status": "completed", "stop_reason": stop, "baseline": baseline, "final": current,
                        "delta": compare(baseline, current), "tokens": sum(row["tokens"] for row in rounds),
                        "coverage_closed": not current["uncovered"], "functional_correctness_proven": False})
        save(root / "pending-proofs.json", pending)
    except (ValueError, OSError, subprocess.SubprocessError, KeyError) as error:
        summary.update({"status": "failed", "phase": phase, "error": str(error)})
        save(root / "pending-proofs.json", pending)
        save(root / "summary.json", summary)
        write_report(root, summary)
        print(json.dumps(summary))
        return 1
    save(root / "summary.json", summary)
    write_report(root, summary)
    print(json.dumps(summary))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
