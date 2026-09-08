#!/usr/bin/env python3
"""Compare recorded runs without inferring unknown token usage or provider prices."""
import argparse
import json
from pathlib import Path
from run_records import fingerprint


def compare_runs(paths):
    rows = []
    for path in paths:
        path = Path(path)
        record = json.loads((path / "comparison.json" if path.is_dir() else path).read_text())
        costs = record["costs"]
        rows.append({"run": str(path), "status": record["status"], "model": record.get("model"),
            "rag": record.get("rag", {}).get("mode") if isinstance(record.get("rag"), dict) else record.get("rag"),
            "started_utc": record["started_utc"], "finished_utc": record["finished_utc"],
            "elapsed_seconds": record["elapsed_seconds"], "active_seconds": record.get("active_seconds"),
            "requests": costs["requests"], **costs["usage_reported"],
            "requests_without_usage": costs["requests_without_usage"],
            "token_accounting_complete": costs["token_accounting_complete"],
            "reported_models": costs.get("reported_models", []), "phases": costs["phases"],
            "coverage": record.get("coverage"), "closed_lines": record.get("closed_lines"),
            "cells": record.get("cells"), "paired": record.get("paired"), "arms": record.get("arms"),
            "comparison_basis": fingerprint({key: record.get(key) for key in (
                "design", "replay_config", "generation_contract", "source_sha256", "task_sha256",
                "attempt_budget", "request_retry_budget", "round_budget", "repair_budget_per_round",
                "request_retries", "patience", "jg_time_limit", "temperature", "request_timeout",
                "session_phase", "attempts", "jobs", "replay_contract", "offline", "saved_responses",
                "saved_response_sha256", "response", "sampling", "sequences_per_intent",
                "contract", "bundle", "haven_sha256", "eda_sha256", "eda_shell_sha256", "seed", "rounds", "min_gain", "target",
                "scope", "metrics", "baseline_policy", "repair_policy", "bo_policy", "formal_exclusion_policy")})})
    return {"runs": rows, "same_comparison_basis": len({row["comparison_basis"] for row in rows}) <= 1,
            "note": "Different basis means different RTL/task/framework/budgets; do not attribute the difference solely to RAG. "
                    "total_tokens sums reported usage only; unknown requests can still incur cost. "
                    "saved-response runs test the framework, not model/RAG efficacy. No currency estimate."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runs", type=Path, nargs="+")
    args = parser.parse_args()
    print(json.dumps(compare_runs(args.runs), indent=2, ensure_ascii=False))
