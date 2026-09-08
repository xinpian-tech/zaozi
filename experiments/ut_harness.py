#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
"""The LLM-facing entry to the formal-UT flow.

Takes a complete run-specific source directory, compiles/lowers it in isolation,
checks the single-DUT wiring and invokes a separate trusted solver process. Prints ONE JSON line:

  {"phase": "typecheck", "ok": false, "errors": [{"file": …, "line": …, "col": …, "message": …}…]}   exit 2
  {"phase": "wiring-check", "ok": false, "detail": …}                                               exit 3
  {"phase": "solve", "ok": true, "result": {"status": "partial", "goals": […]}}                     exit 0
  (UT status: generated/partial/no-witness; per-goal: generated/infeasible/unknown/error.)

Run inside the zaozi dev shell (`nix develop . -c experiments/ut_harness.py …`): mill and the CIRCT toolchain come
from there. Diagnostics go to stderr; stdout carries exactly the one JSON line.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
import isolation
from process_runner import run
from run_records import Records, fingerprint, save
from sequence_framework import Design, Port, parse_response, check_saved_sources
from ut_validation import validate

ZAOZI = Path(__file__).resolve().parent.parent
ERROR_HEAD = re.compile(r"(\S+\.scala):(\d+):(\d+)")
TASK_PREFIX = re.compile(r"^\d+\]\s?")


def strip_prefix(raw: str) -> str:
    """Drop mill's task-id prefix from an output line."""
    return TASK_PREFIX.sub("", raw)


def parse_type_errors(output: str) -> list[dict]:
    """Mill prints `[error] file:line:col` followed by indented context lines; group them."""
    errors: list[dict] = []
    current: dict | None = None
    for raw in output.splitlines():
        line = re.sub(r"\x1b\[[0-9;]*m", "", strip_prefix(raw))
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
    parser.add_argument("generated", type=Path, help="directory containing this run's complete generated sources and manifests")
    parser.add_argument("--out", type=Path, required=True, help="artifact directory for the solve")
    parser.add_argument("--compile-only", action="store_true", help="typecheck without calling any solver")
    parser.add_argument("--resume", action="store_true", help="reuse verified lowering and successful goal checkpoints")
    parser.add_argument("--jg-time-limit", default="120s")
    args = parser.parse_args()

    source = args.generated.resolve()
    if not source.exists() or (source.is_file() and source.suffix != ".scala"):
        parser.error("generated source must be a Scala file or source directory")
    out_dir = args.out.resolve()
    if source.is_dir() and not any(source.rglob("*.scala")):
        parser.error("generated source directory contains no Scala files")
    model_record = source / "model-sources.json" if source.is_dir() else source.parent / "model-sources.json"
    if model_record.exists():
        for entry in json.loads(model_record.read_text()):
            path = model_record.parent / entry["file"]
            if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
                emit({"phase": "input-check", "ok": False, "detail": f"model UT source changed: {path}"}, 2)
    record = source / "design.json" if source.is_dir() else source.parent / "design.json"
    if record.exists():
        inputs = json.loads(record.read_text())
        for entry in inputs["sources"] + inputs.get("include_files", []):
            path = Path(entry["path"])
            if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
                emit({"phase": "input-check", "ok": False, "detail": f"RTL input changed: {path}"}, 2)
    out_dir.mkdir(parents=True, exist_ok=True)
    if (out_dir / "report.json").exists() and not args.resume:
        emit({"phase": "input-check", "ok": False, "detail": "output already has a report; use a new run directory"}, 2)
    events = Records(out_dir)
    phase = "input-check"
    try:
        if not source.is_dir() or not (source / "response.json").is_file():
            raise ValueError("isolated execution requires a complete run-specific source directory")
        response = parse_response((source / "response.json").read_text())
        inputs = json.loads(record.read_text())
        design = Design(inputs["top"], tuple(Path(p["path"]) for p in inputs["sources"]),
            tuple(Path(p) for p in inputs["include_dirs"]), tuple(Port(**p) for p in inputs["ports"]),
            inputs["clock"], inputs["reset"]["port"], inputs["reset"]["active_low"],
            inputs["sequence"]["name"], inputs["sequence"]["item_type"], inputs["context"],
            tuple(inputs["parameters"].items()))
        check_saved_sources(source, design, response)
        runtime = [ZAOZI / p for p in ("experiments/ut_harness.py", "experiments/isolation.py",
            "experiments/ut_validation.py", "experiments/src/TrustedSolver.scala", "utlib/src/JasperGold.scala")]
        input_hash = fingerprint({"files": {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in [*source.glob("*.scala"), *runtime]}, "design": inputs, "timeLimit": args.jg_time_limit})
        phase = "toolchain"
        with events.phase(phase):
            config = isolation.toolchain(ZAOZI)
        save(out_dir / "toolchain.json", config)
        java = str(Path(shutil.which("java")).resolve())
        prepared = out_dir / "prepared.json"
        if args.resume and prepared.exists():
            job = json.loads(prepared.read_text())
            if (job["fingerprint"] != fingerprint({k: v for k, v in job.items() if k != "fingerprint"}) or
                    job["inputsFingerprint"] != input_hash or hashlib.sha256(Path(job["sv"]).read_bytes()).hexdigest() != job["svSha256"]):
                raise ValueError("resume inputs or lowered artifact changed")
        else:
            work = out_dir / f"sandbox-{len(list(out_dir.glob('sandbox-*')))}"
            work.mkdir()
            classes = work / "classes"
            classes.mkdir()
            cp = os.pathsep.join(config["classpath"])
            phase = "typecheck"
            with events.phase(phase):
                compiled = isolation.execute([java, "-Xmx2g", *config["jvm"], "-cp", os.pathsep.join(config["compiler"]),
                    "dotty.tools.dotc.Main", "-color:never", *config["options"], "-classpath", cp,
                    "-d", str(classes), *map(str, sorted(source.glob("*.scala")))], config, source, work)
                (out_dir / "compile.log").write_text(compiled.stdout)
                if compiled.returncode:
                    emit({"phase": phase, "ok": False, "errors": parse_type_errors(compiled.stdout),
                          "detail": compiled.stdout[-2500:]}, 2)
            if args.compile_only:
                emit({"phase": phase, "ok": True, "isolation": "bubblewrap-compile-lower-v1"}, 0)
            phase = "lower"
            with events.phase(phase):
                lowered = isolation.execute([java, "-Xmx2g", *config["jvm"], "-cp", str(classes) + os.pathsep + cp,
                    "utRun", str(work / "artifacts")], config, source, work)
                (out_dir / "lower.log").write_text(lowered.stdout)
                if lowered.returncode:
                    raise ValueError(lowered.stdout[-2500:])
            description = json.loads((work / "artifacts/report.json").read_text())
            def artifact(name):
                path = Path(description[name]).resolve()
                if not path.is_relative_to(work) or not path.is_file() or path.stat().st_size > 64 * 1024 * 1024:
                    raise ValueError("invalid sandbox artifact path or size")
                return path
            phase = "wiring-check"
            with events.phase(phase):
                sv = artifact("sv").read_text()
                check = validate(sv, design, description["top"], response["ut"]["generationLabels"])
                abi = json.loads(artifact("abiFile").read_text())
                ports = [{"name": "clock", "role": "Clock", "width": 1, "signed": False},
                         {"name": "reset", "role": "Reset", "width": 1, "signed": False}]
                ports += [{"name": p.name, "role": "Drive", "width": p.width, "signed": p.kind == "sint"}
                          for p in design.data_ports if p.direction == "input"]
                if abi["abiVersion"] != "1.0" or abi["ports"] != ports:
                    raise ValueError("model ABI differs from fixed binding")
                save(out_dir / "wiring-check.json", check)
                trusted = out_dir / "ut/lowered"
                trusted.mkdir(parents=True, exist_ok=True)
                path = trusted / (description["top"] + ".sv")
                path.write_text(sv)
                job = {"sv": str(path), "svSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                    "inputsFingerprint": input_hash, "top": description["top"], "abi": abi,
                    "rtl": [str(p) for p in design.sources], "include": str(design.include_dirs[0]) if design.include_dirs else None,
                    "labels": response["ut"]["generationLabels"], "module": response["ut"]["module"],
                    "sourceSha256": hashlib.sha256(response["ut"]["source"].encode()).hexdigest(),
                    "sequenceName": design.sequence_name, "itemType": design.item_type,
                    "timeLimit": args.jg_time_limit, "proofObligations": response["proofObligations"]}
                job["fingerprint"] = fingerprint(job)
                save(prepared, job)
        phase = "solve"
        env = {k: v for k, v in os.environ.items() if k not in (
            "RVPROBE_LLM_API_KEY", "RVPROBE_LLM_BASE_URL", "OPENAI_API_KEY", "OPENAI_BASE_URL", "RVPROBE_EXPERIMENT_SOURCES")}
        with events.phase(phase):
            match = re.fullmatch(r"(\d+)([smh])", job["timeLimit"])
            if not match:
                raise ValueError("JG time limit must be a positive integer followed by s, m or h")
            seconds = int(match[1]) * {"s": 1, "m": 60, "h": 3600}[match[2]]
            solved = run([java, "-Xmx2g", *config["jvm"], "-cp", os.pathsep.join(config["classpath"]),
                "solvePrepared", str(prepared), str(out_dir)], env=env, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, text=True, timeout=1800 + (seconds + 180) * len(job["labels"]))
            (out_dir / "solve.log").write_text(solved.stdout)
            if solved.returncode:
                raise ValueError(solved.stdout[-2500:])
        emit({"phase": phase, "ok": True, "result": json.loads((out_dir / "report.json").read_text())}, 0)
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError, KeyError) as error:
        emit({"phase": phase, "ok": False, "detail": str(error)}, 3)


if __name__ == "__main__":
    main()
