#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Standalone cycle-replay diagnostic. NOT the paired HAVEN experiment entrypoint."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

import sequence_experiment as generation
from cycle_replay import CONTRACT, Replay, baseline_frames, digest, load_config, preflight, save, witness_frames
from sequence_framework import ROOT
from goal_coverage import measure_goals
from process_runner import run as run_process
from run_records import Records, begin, finish, framework_hashes
from witness_sampling import sampling_options, sampling_policy, expand_goals


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
        if "final_witness_only" in summary:
            lines += ["", "去掉 drain 后的累计覆盖：" + json.dumps(summary["final_witness_only"]["percent"], ensure_ascii=False) + "。"]
        lines += [f"实际回放序列数：{sum(r.get('sequence_count', 0) for r in summary['rounds'])}；每 intent 的请求/实际数量见各轮 sampling。"]
    costs = summary.get("costs", {})
    lines += ["", f"UTC：{summary.get('started_utc')} → {summary.get('finished_utc')}。",
              f"总墙钟耗时（含恢复间隔）：{summary.get('elapsed_seconds')} 秒；活动会话：{summary.get('active_seconds')} 秒。",
              f"模型请求：{costs.get('requests')} 次；用量未知请求：{costs.get('requests_without_usage')} 次。",
              "逐阶段、逐目标耗时及输入/输出 token 见 comparison.json；未知请求不能视为免费。"]
    lines += ["", "## 证据边界", "",
              "- 模型每轮输出一个完整 UT、多个 Gen；框架不补写或修改模型源码。",
              "- 模型不得新增 Assume / restrict；编译后的 UT 检查无环境假设，逐目标独立求解。",
              "- 初始化方式见 replay-config.json；默认只有复位和空闲周期，没有随机前置流量。",
              "- 一次编译、固定 baseline 前缀、累计 witness；每条独立复位，一拍一驱动。",
              "- 输入、复位和采样间隔须与 schedule 一致；JG 完整设计 VCD 的可见输出仅检查已知位。",
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
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--request-retries", type=int, default=3)
    sampling_options(parser)
    args = parser.parse_args(argv)
    policy = sampling_policy(args)
    if min(args.rounds, args.patience, args.attempts, args.timeout, args.request_retries) < 1:
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
    began = time.monotonic()
    design_path = (config_path.parent / config["design"]).resolve()
    manifest = {
        "contract": CONTRACT, "generation_contract": generation.CONTRACT, "config_sha256": digest(config_path),
        "model": "saved-response" if args.response_file else args.model,
        "round_budget": args.rounds, "repair_budget_per_round": args.attempts, "patience": args.patience,
        "source_sha256": framework_hashes(ROOT), "design": design.record(), "replay_config": config,
        "temperature": args.temperature, "rag": args.rag, "request_timeout": args.timeout,
        "request_retries": args.request_retries, "jg_time_limit": args.jg_time_limit,
        "session_phase": "flow-session",
        "sampling": policy,
        "saved_responses": [{"path": str(path.resolve()), "sha256": digest(path)} for path in args.response_file],
        "baseline": config["baseline"], "score_scope": design.top,
        "ut_policy": "one model UT per round with 1..64 independent Gen goals, or explicit stop",
        "environment_policy": "fixed-reset-no-model-assumptions-v1",
        "metrics": ["line", "cond", "toggle", "branch"],
        "proof_policy": "pending metadata only; never exclude lines without an independently verified property",
    }
    started = begin(root, manifest, args.resume)
    records = Records(root)
    save(root / "replay-config.json", config)
    save(root / "design.json", design.record())
    rounds, pending = [], []
    phase = "interface"
    summary = {"status": "running", "contract": CONTRACT, "rounds": rounds, "pending_proofs": pending}
    save(root / "summary.json", summary)
    session = records.phase("flow-session")
    session_event = session.__enter__()
    try:
        print("flow: CIRCT interface preflight", file=sys.stderr, flush=True)
        with records.phase("interface-preflight"):
            preflight(design_path, root)
        phase = "baseline"
        replay = Replay(design, config, root, args.eda_shell, resume=args.resume)
        replay.compile()
        frames = baseline_frames(design, config)
        prefix = list(frames)
        print(f"flow: fresh {config['baseline']['mode']} baseline", file=sys.stderr, flush=True)
        baseline = replay.simulate("baseline", frames)
        current = baseline
        summary.update(baseline=baseline, final=current)
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
            round_dir.mkdir(exist_ok=args.resume)
            feedback = {"round": number, "current_coverage": current["percent"],
                        "remaining": current["uncovered"],
                        "previous_round": rounds[-1] if rounds else None,
                        "replay_contract": CONTRACT,
                        "sampling": policy,
                        "instruction": "Write one new UT with Gen goals for the current residual; do not repeat already covered targets. "
                                       "Use per-goal witness_closed_lines and drain_added_lines as measured evidence, not intent. "
                                       "Do not sum overlapping independent-goal coverage. If no useful goal remains, return stop with a reason. "
                                       "Pending proof metadata is not a proof or a coverage exclusion."}
            save(round_dir / "feedback.json", feedback)
            command = ["--design", str(design_path), "--modinfo", current["modinfo"],
                       "--out", str(round_dir / "generation"), "--attempts", str(args.attempts),
                       "--jg-time-limit", args.jg_time_limit, "--eda-shell", str(args.eda_shell.resolve()),
                       "--model", args.model, "--temperature", str(args.temperature),
                       "--timeout", str(args.timeout), "--rag", args.rag,
                       "--request-retries", str(args.request_retries),
                       "--sequences-per-intent", str(args.sequences_per_intent),
                       "--feedback-file", str(round_dir / "feedback.json")]
            if args.resume:
                command += ["--resume"]
            if args.response_file:
                command += ["--response-file", str(args.response_file[number - 1].resolve())]
            elif args.env_file:
                command += ["--env-file", str(args.env_file.resolve())]
            phase = f"round-{number}/generation"
            print(f"flow: round {number}, {len(current['uncovered'])} residual lines", file=sys.stderr, flush=True)
            # The generation subprocess owns model credentials. Replay never loads them.
            with records.phase("round-generation", round=number), (round_dir / "generation.log").open("a") as log:
                generated = run_process([sys.executable, str(ROOT / "experiments/sequence_experiment.py"), *command],
                                           cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                           timeout=args.attempts * (args.timeout * args.request_retries + 21600))
            result = json.loads((round_dir / "generation/summary.json").read_text())
            if generated.returncode:
                raise ValueError(f"generation failed; inspect {round_dir / 'generation.log'}")
            solved = result.get("result", {})
            pending += [{"round": number, **proof} for proof in solved.get("proofObligations", [])]
            goals = solved.get("goals", [])
            row = {"round": number, "generation_status": result["status"],
                   "generation_summary": str(round_dir / "generation/summary.json"),
                   "tokens": result["tokens"], "compiler_attempts": result["attempts"],
                   "ut_count": solved.get("utCount"), "ut_module": solved.get("utModule"),
                   "goal_labels": [goal["label"] for goal in goals]}
            if solved.get("status") == "stopped":
                row.update(stop_reason=solved["stopReason"])
                rounds.append(row)
                save(round_dir / "result.json", row)
                stop = "model_stop"
                break
            if solved.get("utCount") != 1 or not goals:
                raise ValueError("each round requires exactly one UT with at least one Gen goal")
            phase = f"round-{number}/sampling"
            goals = expand_goals(result, config_path, round_dir / "sampling", args)
            row["sampling"] = [{"label": g["label"], "status": g.get("sampling_status", g["status"]),
                "requested": args.sequences_per_intent, "actual": len(g.get("sequences", [g] if g["status"] == "generated" else [])),
                "error": g.get("sampling_error")} for g in goals]
            phase = f"round-{number}/replay"
            goal_rows, additions = measure_goals(replay, design, config, prefix, baseline, goals, number, round_dir)
            row["goals"] = goal_rows
            row["sequence_count"] = sum(g["status"] == "replayed" for g in goal_rows)
            frames += additions
            measured = replay.simulate(f"replay-{number}", frames) if additions else current
            short_measured = replay.simulate(f"replay-{number}-witness-only", [f for f in frames if f["kind"] != "drain"]) if additions else summary.get("final_witness_only", baseline)
            delta = compare(current, measured)
            row.update(delta)
            row.update({"coverage": measured["percent"], "score": measured["score"],
                        "modinfo": measured["modinfo"], "replay": measured["replay"]})
            rounds.append(row)
            save(round_dir / "result.json", row)
            current = measured
            summary.update({"baseline": baseline, "final": current, "final_witness_only": short_measured})
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
                        "delta": compare(baseline, current),
                        "coverage_closed": not current["uncovered"], "functional_correctness_proven": False})
        save(root / "pending-proofs.json", pending)
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError, KeyError, TypeError, KeyboardInterrupt) as error:
        session_event["status"] = "failed"
        summary.update({"status": "failed", "phase": phase, "error": str(error)})
        save(root / "pending-proofs.json", pending)
    finally:
        session.__exit__(None, None, None)
        finish(root, summary, started, began, **manifest)
    write_report(root, summary)
    print(json.dumps(summary))
    return int(summary["status"] == "failed")


if __name__ == "__main__":
    raise SystemExit(main())
