"""Durable timing/cost records. Missing provider usage is unknown, never silently zero."""
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import time
import uuid
import backend_imports
from rvprobe.backend.records import Records, save, utc, fingerprint


def fresh_directory(path):
    """Claim a previously absent run directory without overwriting any artifact.

    Some network filesystems can report EEXIST after creating an empty directory.
    A unique, exclusive owner file arbitrates concurrent creators in that case.
    Existing paths (including empty directories and symlinks) are never reused.
    """
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() or path.is_symlink():
        raise FileExistsError(f'output already exists: {path}')
    recovered = False
    try:
        path.mkdir()
    except FileExistsError:
        if path.is_symlink() or not path.is_dir() or any(path.iterdir()):
            raise
        recovered = True
    with (path/'.rvprobe-directory-owner').open('x') as stream:
        json.dump({'id':str(uuid.uuid4()),'pid':os.getpid(),
                   'recovered_empty_create':recovered,'created_utc':utc()},stream)
    return path



USAGE_DETAILS = {
    "reasoning_tokens": ("completion_tokens_details", "reasoning_tokens"),
    "cached_tokens": ("prompt_tokens_details", "cached_tokens"),
    "prompt_cache_hit_tokens": ("prompt_cache_hit_tokens",),
    "prompt_cache_miss_tokens": ("prompt_cache_miss_tokens",),
}


def model_usage(response):
    """Retain numeric provider usage only; no reasoning text or inferred token counts."""
    usage = response.get("usage") or {}
    def number(path):
        value = usage
        for key in path:
            value = value.get(key) if isinstance(value, dict) else None
        return value if type(value) is int and value >= 0 else None
    return {"usage": {key: number((key,)) for key in ("prompt_tokens", "completion_tokens", "total_tokens")},
            "usage_details": {key: number(path) for key, path in USAGE_DETAILS.items()}}


def response_metadata(response):
    """Persist provider termination and output size, never private reasoning text."""
    choice = response["choices"][0]
    message = choice["message"]
    content = message.get("content")
    reason = choice.get("finish_reason")
    status = ("truncated" if reason == "length" else "filtered" if reason == "content_filter" else
              "tool_call" if message.get("tool_calls") else
              "complete" if isinstance(content, str) and content.strip() else "empty")
    usage = model_usage(response)
    completion = usage['usage']['completion_tokens']
    reasoning = usage['usage_details']['reasoning_tokens']
    failure = None
    if status == 'truncated':
        failure = ('provider_reasoning_budget_exhausted'
                   if completion is not None and completion > 0 and reasoning == completion
                   and not (isinstance(content, str) and content.strip()) and not message.get('tool_calls')
                   else 'provider_output_truncated')
    elif status in ('filtered', 'empty'):
        failure = 'provider_' + status + '_response'
    return {"finish_reason": reason, "response_status": status,
            "response_failure_kind": failure,
            "output_characters": len(content) if isinstance(content, str) else 0,
            "reasoning_characters": len(message.get("reasoning_content") or "")}


def begin(directory, comparison, resume=False):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=resume)
    manifest = directory / "manifest.json"
    identity = fingerprint(comparison)
    if manifest.exists():
        previous = json.loads(manifest.read_text())
        if not resume or previous["fingerprint"] != identity:
            raise ValueError("resume input/config/framework fingerprint changed; use a new directory")
        return previous["started_utc"]
    started = utc()
    save(manifest, {**comparison, "fingerprint": identity, "started_utc": started})
    return started


def framework_hashes(root):
    root = Path(root)
    paths = {p for p in (root / "experiments").glob("*.py") if not p.name.startswith("test_")}
    for directory, pattern in (("experiments/src", "*.scala"), ("experiments/rag", "*"),
                               ("utlib/src", "*.scala"), ("rvprobe/backend", "*.py"), ("zaozi/src", "*.scala"),
                               ("zaozi-compiler-plugin/src", "*.scala")):
        paths.update(p for p in (root / directory).rglob(pattern) if p.is_file())
    paths.add(root / "experiments/package.mill")
    paths.update(root / name for name in ("build.mill", "flake.nix", "flake.lock", "experiments/eda-shell", "rvprobe-skill.md") if (root / name).is_file())
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}



def totals(directory):
    records = {}
    for path in Path(directory).rglob("events.jsonl"):
        for line in path.read_text().splitlines():
            try:
                event = json.loads(line)
                records[event.get("id", str(path) + str(len(records)))] = event
            except json.JSONDecodeError:
                # A killed process may leave an incomplete trailing record; explicitly account for it.
                records[str(path) + "-incomplete"] = {"phase": "incomplete-record", "status": "failed"}
    events = list(records.values())
    requests = [e for e in events if e["phase"] == "model-request"]
    usage = {}
    for key in ("prompt_tokens", "completion_tokens", "total_tokens"):
        known = [e.get("usage", {}).get(key) for e in requests]
        known = [value for value in known if type(value) is int and value >= 0]
        # No calls really costs zero. Calls without any usage evidence are
        # unknown, not zero; a partially known total remains a reported subtotal.
        usage[key] = sum(known) if known or not requests else None
    phases = {}
    for event in events:
        phase = event["phase"]
        row = phases.setdefault(phase, {"count": 0, "seconds": 0, "failures": 0})
        row["count"] += 1
        row["seconds"] += event.get("seconds", 0)
        row["failures"] += event.get("status") in ("failed", "running")
    detail_totals = {}
    for key in USAGE_DETAILS:
        values = [e.get("usage_details", {}).get(key) for e in requests]
        known = [value for value in values if type(value) is int and value >= 0]
        detail_totals[key] = {"reported_tokens": sum(known) if known else None,
                              "requests_with_usage": len(known), "requests_without_usage": len(values) - len(known),
                              "complete": len(known) == len(values)}
    return {"requests": len(requests), "usage_reported": usage, "usage_breakdown": detail_totals,
            "reported_models": sorted({e["reported_model"] for e in requests if e.get("reported_model")}),
            "token_accounting_complete": all(e.get("usage", {}).get("total_tokens") is not None for e in requests),
            "requests_without_usage": sum(e.get("usage", {}).get("total_tokens") is None for e in requests),
            "phases": phases, "note": "phase timings may nest; do not add them to estimate wall time"}


def finish(directory, summary, started, began, **comparison):
    costs = totals(directory)
    finished = utc()
    elapsed = (datetime.fromisoformat(finished) - datetime.fromisoformat(started)).total_seconds()
    session = comparison.get("session_phase", "generation-session")
    summary.update(started_utc=started, finished_utc=finished, elapsed_seconds=elapsed,
                   session_seconds=time.monotonic() - began,
                   active_seconds=costs["phases"].get(session, {}).get("seconds", 0),
                   costs=costs, tokens=costs["usage_reported"]["total_tokens"])
    save(Path(directory) / "summary.json", summary)
    save(Path(directory) / "comparison.json", {
        "version": 1, **comparison, "status": summary["status"], "started_utc": started,
        "finished_utc": summary["finished_utc"], "elapsed_seconds": summary["elapsed_seconds"],
        "active_seconds": summary["active_seconds"],
        "costs": costs, "coverage": summary.get("final", {}).get("percent"),
        "closed_lines": len(summary["delta"]["closed_lines"]) if "delta" in summary else None,
        "cells": summary.get("cells"), "paired": summary.get("paired"),
        "arms": summary.get("arms"), "baseline": summary.get("baseline"),
        "money": None, "money_note": "No provider pricing supplied; token usage is not a currency estimate",
        "time_note": "elapsed includes downtime between resume sessions; active sums completed outer sessions only",
    })
