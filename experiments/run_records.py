"""Durable timing/cost records. Missing provider usage is unknown, never silently zero."""
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import time
import uuid


def utc():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")
    temporary.replace(path)


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


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
                               ("utlib/src", "*.scala"), ("zaozi/src", "*.scala"),
                               ("zaozi-compiler-plugin/src", "*.scala")):
        paths.update(p for p in (root / directory).rglob(pattern) if p.is_file())
    paths.add(root / "experiments/package.mill")
    paths.update(root / name for name in ("build.mill", "flake.nix", "flake.lock", "experiments/eda-shell") if (root / name).is_file())
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}


class Records:
    def __init__(self, directory):
        self.path = Path(directory) / "events.jsonl"
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def append(self, record):
        with self.path.open("a") as stream:
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")
            stream.flush()
            os.fsync(stream.fileno())

    @contextmanager
    def phase(self, phase, **fields):
        event = {"id": uuid.uuid4().hex, "phase": phase, "started_utc": utc(), "status": "running", **fields}
        began = time.monotonic()
        self.append(event)
        try:
            yield event
            if event["status"] == "running":
                event["status"] = "ok"
        except BaseException as error:
            event.update(status="failed", error_type=type(error).__name__)
            raise
        finally:
            event.update(finished_utc=utc(), seconds=time.monotonic() - began)
            self.append(event)


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
    usage = {key: sum(e.get("usage", {}).get(key) or 0 for e in requests)
             for key in ("prompt_tokens", "completion_tokens", "total_tokens")}
    phases = {}
    for event in events:
        phase = event["phase"]
        row = phases.setdefault(phase, {"count": 0, "seconds": 0, "failures": 0})
        row["count"] += 1
        row["seconds"] += event.get("seconds", 0)
        row["failures"] += event.get("status") in ("failed", "running")
    return {"requests": len(requests), "usage_reported": usage,
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
