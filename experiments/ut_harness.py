#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
"""The LLM-facing entry to the formal-UT flow.

Takes a generated typed-DSL experiment (a Scala file or a run-specific source directory),
typechecks it against the generic framework without touching repository sources, runs it, and prints ONE JSON line on
stdout — the only contract the calling harness (e.g. HAVEN's LangGraph loop) needs:

  {"phase": "typecheck", "ok": false, "errors": [{"file": …, "line": …, "col": …, "message": …}…]}   exit 2
  {"phase": "run",       "ok": false, "detail": …}                                                    exit 3
  {"phase": "solve",     "ok": true,  "result": {"status": "generated", "trace": …, "stimulusFile": …}} exit 0
  (result.status may also be "infeasible" / "unknown" — still exit 0: the flow worked, the constraint didn't)

Run inside the zaozi dev shell (`nix develop . -c experiments/ut_harness.py …`): mill and the CIRCT toolchain come
from there. Diagnostics go to stderr; stdout carries exactly the one JSON line.
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ZAOZI = Path(__file__).resolve().parent.parent
ERROR_HEAD = re.compile(r"\[error\]\s+(\S+\.scala):(\d+):(\d+)")
TASK_PREFIX = re.compile(r"^\d+\]\s?")


def strip_prefix(raw: str) -> str:
    """Drop mill's task-id prefix from an output line."""
    return TASK_PREFIX.sub("", raw)


def mill(*args: str, env: dict) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["mill", "--no-server", *args], cwd=ZAOZI, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
    )


def parse_type_errors(output: str) -> list[dict]:
    """Mill prints `[error] file:line:col` followed by indented context lines; group them."""
    errors: list[dict] = []
    current: dict | None = None
    for raw in output.splitlines():
        line = strip_prefix(raw)
        head = ERROR_HEAD.search(line)
        if head:
            current = {
                "file": head.group(1),
                "line": int(head.group(2)),
                "col": int(head.group(3)),
                "message": "",
            }
            errors.append(current)
        elif current is not None and "[error]" not in line and line.strip():
            current["message"] += ("\n" if current["message"] else "") + line.rstrip()
        elif "[error]" in line:
            current = None
    return errors


def emit(payload: dict, code: int) -> "NoReturn":  # type: ignore[name-defined]
    print(json.dumps(payload))
    sys.exit(code)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("generated", type=Path, help="a .scala file or directory containing this run's generated sources")
    parser.add_argument("--out", type=Path, required=True, help="artifact directory for the solve")
    parser.add_argument("--compile-only", action="store_true", help="typecheck without calling any solver")
    parser.add_argument("--legacy", action="store_true", help="explicitly enable archived stdlib-dependent regressions")
    args = parser.parse_args()

    source = args.generated.resolve()
    if not source.exists() or (source.is_file() and source.suffix != ".scala"):
        parser.error("generated source must be a Scala file or source directory")
    out_dir = args.out.resolve()
    if source.is_dir() and not any(source.rglob("*.scala")):
        parser.error("generated source directory contains no Scala files")
    record = source / "design.json" if source.is_dir() else source.parent / "design.json"
    if not args.legacy and record.exists():
        inputs = json.loads(record.read_text())
        for entry in inputs["sources"] + inputs.get("include_files", []):
            path = Path(entry["path"])
            if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
                emit({"phase": "input-check", "ok": False, "detail": f"RTL input changed: {path}"}, 2)
    if args.legacy:
        if not source.is_file():
            parser.error("--legacy expects one archived Scala driver file")
        # Translate only the relocated fixture prefix in a COPY, never edit the archive.
        copied = out_dir / "legacy-sources"
        copied.mkdir(parents=True, exist_ok=False)
        original = source.read_text()
        translated = original.replace("stdlib/tests/resources/", "experiments/fixtures/")
        (copied / "Generated.scala").write_text(translated)
        (copied / "provenance.json").write_text(json.dumps({
            "legacy": True, "source": str(source),
            "original_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "translation": "stdlib/tests/resources/ -> experiments/fixtures/",
        }, indent=2) + "\n")
        source = copied
    env = os.environ.copy()
    env["RVPROBE_EXPERIMENT_SOURCES"] = str(source)
    module = "experiments.legacy.replay" if args.legacy else "experiments"

    compile_run = mill(f"{module}.compile", env=env)
    if compile_run.returncode != 0:
        errors = parse_type_errors(compile_run.stdout)
        if not errors:  # non-typecheck failure (toolchain); surface the tail raw
            emit({"phase": "typecheck", "ok": False, "errors": [], "detail": compile_run.stdout[-2000:]}, 2)
        emit({"phase": "typecheck", "ok": False, "errors": errors}, 2)

    if args.compile_only:
        emit({"phase": "typecheck", "ok": True, "legacy": args.legacy, "sources": str(source)}, 0)

    out_dir.mkdir(parents=True, exist_ok=True)
    if (out_dir / "report.json").exists():
        emit({"phase": "input-check", "ok": False, "detail": "output already has a report; use a new run directory"}, 2)
    run = mill(f"{module}.runMain", "utRun", str(out_dir), env=env)
    report = out_dir / "report.json"
    if run.returncode == 0 and report.exists():
        emit({"phase": "solve", "ok": True, "legacy": args.legacy,
              "result": json.loads(report.read_text())}, 0)
    emit({"phase": "run", "ok": False, "detail": run.stdout[-2000:]}, 3)


if __name__ == "__main__":
    main()
