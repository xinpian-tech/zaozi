#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
"""The LLM-facing entry to the formal-UT flow.

Takes one generated typed-DSL experiment (a .scala file defining `object Generated extends UTExperiment`), installs it
as `experiments/src/Generated.scala`, typechecks it against the full framework, runs it, and prints ONE JSON line on
stdout — the only contract the calling harness (e.g. HAVEN's LangGraph loop) needs:

  {"phase": "typecheck", "ok": false, "errors": [{"file": …, "line": …, "col": …, "message": …}…]}   exit 2
  {"phase": "run",       "ok": false, "detail": …}                                                    exit 3
  {"phase": "solve",     "ok": true,  "result": {"status": "generated", "trace": …, "stimulusFile": …}} exit 0
  (result.status may also be "infeasible" / "unknown" — still exit 0: the flow worked, the constraint didn't)

Run inside the zaozi dev shell (`nix develop . -c experiments/ut_harness.py …`): mill and the CIRCT toolchain come
from there. Diagnostics go to stderr; stdout carries exactly the one JSON line.
"""

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ZAOZI = Path(__file__).resolve().parent.parent
SLOT = ZAOZI / "experiments" / "src" / "Generated.scala"
ERROR_HEAD = re.compile(r"\[error\]\s+(\S+\.scala):(\d+):(\d+)")
TASK_PREFIX = re.compile(r"^\d+\]\s?")


def strip_prefix(raw: str) -> str:
    """Drop mill's task-id prefix from an output line."""
    return TASK_PREFIX.sub("", raw)


def mill(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["mill", *args], cwd=ZAOZI, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
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
    parser.add_argument("generated", type=Path, help="the LLM-generated .scala experiment")
    parser.add_argument("--out", type=Path, required=True, help="artifact directory for the solve")
    args = parser.parse_args()

    shutil.copyfile(args.generated, SLOT)

    compile_run = mill("experiments.compile")
    if compile_run.returncode != 0:
        errors = parse_type_errors(compile_run.stdout)
        if not errors:  # non-typecheck failure (toolchain); surface the tail raw
            emit({"phase": "typecheck", "ok": False, "errors": [], "detail": compile_run.stdout[-2000:]}, 2)
        emit({"phase": "typecheck", "ok": False, "errors": errors}, 2)

    out_dir = args.out.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    run = mill("experiments.run", str(out_dir))
    report = out_dir / "report.json"
    if run.returncode == 0 and report.exists():
        emit({"phase": "solve", "ok": True, "result": json.loads(report.read_text())}, 0)
    emit({"phase": "run", "ok": False, "detail": run.stdout[-2000:]}, 3)


if __name__ == "__main__":
    main()
