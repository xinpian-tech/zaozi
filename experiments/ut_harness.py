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

import backend_imports
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
from rvprobe.backend.process import run
from run_records import Records, fingerprint, save
from sequence_framework import Design, Port, MODEL_MODULE, parse_response, check_saved_sources
from rvprobe.backend.validation import validate
from framework_runtime import runtime_hashes
from repair_policy import model_repair_allowed

ZAOZI = Path(__file__).resolve().parent.parent
ERROR_HEAD = re.compile(r"(\S+\.scala):(\d+):(\d+)")
TASK_PREFIX = re.compile(r"^\d+\]\s?")
COMPILER_CRASH_START = re.compile(
    r"^\s*(?:exception occurred while |An unhandled exception was thrown in the compiler\.)")


def strip_prefix(raw: str) -> str:
    """Drop mill's task-id prefix from an output line."""
    return TASK_PREFIX.sub("", raw)


def parse_type_errors(output: str) -> list[dict]:
    """Mill prints `[error] file:line:col` followed by indented context lines; group them."""
    errors: list[dict] = []
    current: dict | None = None
    for raw in output.splitlines():
        line = re.sub(r"\x1b\[[0-9;]*m", "", strip_prefix(raw))
        # Crash metadata (classpath, compiler settings, stack trace) is not part
        # of the preceding source diagnostic. Preserve the full raw output in
        # compile.log, but do not append it to the model's source
        # error. A later source diagnostic may still begin a new record.
        if COMPILER_CRASH_START.match(line):
            current = None
            continue
        head = ERROR_HEAD.search(line)
        if head:
            current = {
                "file": head.group(1),
                "line": int(head.group(2)),
                "col": int(head.group(3)),
                "message": "",
            }
            category=re.search(r'--\s*(?:\[([^]]+)\]\s*)?((?:.+? )?Error):',line)
            if category:
                current.update(kind=category[2],code=category[1])
            errors.append(current)
        elif re.fullmatch(r'\s*\d+ (?:warning|error)s? found\s*',line):
            continue
        elif current is not None and "[error]" not in line and line.strip():
            current["message"] += ("\n" if current["message"] else "") + line.rstrip()
        elif "[error]" in line:
            current = None
    return errors


def ltl_diagnostics(errors: list[dict], source: Path) -> list[dict]:
    """Map generated-source locations back to the fragment the model actually authored.

    Original compiler output stays in compile.log; no diagnostic meaning is guessed.
    """
    generated = (source / "ModelUT.scala").read_text().splitlines()
    start = generated.index("    // BEGIN MODEL LTL") + 2  # one-based first body line
    count = len((source / "model.ltl").read_text().split("\n"))
    end = start + count
    mapped = []
    for original in errors:
        row = dict(original)
        if Path(row.get("file", "")).name == "ModelUT.scala" and start <= row.get("line", 0) < end:
            row.update(file="model.ltl", line=row["line"] - start + 1, col=max(1, row["col"] - 4))
            def line_number(match):
                number = int(match[2])
                return match[1] + str(number - start + 1 if start <= number < end else number) + match[3]
            row["message"] = re.sub(r"(?m)^(\s*)(\d+)(\s*\|)", line_number, row["message"])
        mapped.append(row)
    return mapped


def emit(payload: dict, code: int) -> "NoReturn":  # type: ignore[name-defined]
    if payload.get('ok') is False:
        payload['model_repair_allowed'] = model_repair_allowed(payload)
        if not payload['model_repair_allowed']:
            payload.setdefault('kind', 'framework_infrastructure_failure')
    print(json.dumps(payload))
    sys.exit(code)


NON_REPAIRABLE_GOAL_SHORTFALLS = frozenset({'property_compile_timeout'})


def solver_diagnostic(report, labels):
    """Separate local resource shortfalls from source repairs and infrastructure failures."""
    failed=[goal for goal in report['goals'] if goal['status']=='error']
    hard=[goal for goal in failed if goal.get('failureKind') not in NON_REPAIRABLE_GOAL_SHORTFALLS]
    if not hard:return None
    detail='; '.join(f"{g['label']}: {g.get('failureKind','solver_error')}: {g.get('detail','')}" for g in hard)
    if not all(g.get('failureKind')=='unsupported_liveness_cover' and g.get('label') in labels for g in hard):
        return dict(phase='solve',ok=False,result=report,model_repair_allowed=False,detail=detail)
    message=('The current JasperGold Cover backend rejects this generated liveness property (EOBS012). '
        'This is an unsupported goal form, not proof of unreachability or a solver timeout. '
        'Preserve the original intent and every Gen label; repair the affected expression, not the DUT or environment. '
        'Express the actual finite event sequence when that matches the intent. '
        'Use a finite delay bound only when justified by the supplied spec, configuration or observed RTL evidence; '
        'do not invent a bound, weaken the output condition, delete the goal, return STOP, or add assumptions. '
        'The framework has not rewritten the original property or certified a replacement equivalent.')
    return dict(phase='solve',ok=False,kind='model_goal_unsupported',result=report,
        errors=[dict(file='model.ltl',goal=g['label'],code='jg_unsupported_liveness_cover',
            message=message,backend_detail=g.get('detail','')) for g in hard])


def elaboration_diagnostic(work: Path, source: Path) -> dict | None:
    """Only typed, source-mapped helper errors permit LTL repair; logs are not parsed."""
    path = work / 'artifacts/ltl-error.json'
    if not path.exists():
        return None
    if not path.resolve().is_relative_to(work.resolve()) or path.stat().st_size > 16384:
        return None
    try:
        error = json.loads(path.read_text())
        if not isinstance(error, dict) or set(error) != {'schema','code','file','line','col','message'}:
            return None
        if (error['schema'] != 'ltl-argument-v1' or error['code'] not in
                {'ltl_unsigned_range','ltl_signed_range'} or
                not isinstance(error['file'], str) or
                Path(error['file']).resolve() != (source/'ModelUT.scala').resolve() or
                type(error['line']) is not int or error['line'] < 1 or type(error['col']) is not int or error['col'] != 1 or
                not isinstance(error['message'],str) or not error['message'].strip()):
            return None
        row = ltl_diagnostics([{k:error[k] for k in ('code','file','line','col','message')}],source)[0]
        if row['file'] != 'model.ltl':
            return None
        return {'phase':'elaboration-check','ok':False,'kind':'model_argument_error','errors':[row]}
    except (ValueError, TypeError, KeyError, OSError):
        return None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("generated", type=Path, help="directory containing this run's complete generated sources and manifests")
    parser.add_argument("--out", type=Path, required=True, help="artifact directory for the solve")
    parser.add_argument("--compile-only", action="store_true", help="typecheck without calling any solver")
    parser.add_argument("--resume", action="store_true", help="reuse verified lowering and successful goal checkpoints")
    parser.add_argument("--jg-time-limit", default="120s")
    parser.add_argument("--replay-config", type=Path)
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
        phase = 'framework-preflight'
        trusted_hashes = runtime_hashes(ZAOZI)
        phase = 'input-check'
        if not source.is_dir() or not (source / "response.json").is_file():
            raise ValueError("isolated execution requires a complete run-specific source directory")
        response = parse_response((source / "model.ltl").read_bytes().decode('utf-8'))
        inputs = json.loads(record.read_text())
        design = Design(inputs["top"], tuple(Path(p["path"]) for p in inputs["sources"]),
            tuple(Path(p) for p in inputs["include_dirs"]), tuple(Port(**p) for p in inputs["ports"]),
            inputs["clock"], inputs["reset"]["port"], inputs["reset"]["active_low"],
            inputs["sequence"]["name"], inputs["sequence"]["item_type"], inputs["context"],
            tuple(inputs["parameters"].items()))
        check_saved_sources(source, design, response)
        reset_sequence = None
        initial_state = None
        initial_state_record = None
        reset_snapshot_state, reset_snapshot_record = None, None
        clocks = []
        environment_assumptions = []
        if args.replay_config:
            from cycle_replay import load_config
            replay_design, replay = load_config(args.replay_config)
            if replay_design.record() != design.record():
                raise ValueError("reset protocol design differs from UT design")
            if replay.get('environment'):
                from event_trace import validate_clocks
                schedule = replay['environment']['clocks']
                quantum = validate_clocks(schedule)
                clocks = [{'port': 'clock' if c['port'] == design.clock else c['port'],
                           'factor': c['period_ps']//quantum} for c in schedule]
            reset_sequence = "reset 1'b1\n" + "".join(
                f"{p.name} {p.width}'h{replay['idle'][p.name]:x}\n"
                for p in design.data_ports if p.direction == "input" and
                p.name not in {c['port'] for c in clocks})
            reset_sequence += f"{replay['reset_cycles']}\nreset 1'b0\n$\n"
            if replay.get('environment'):
                from environment_contract import reset_sequence as event_reset_sequence, formal_assumptions
                reset_sequence = event_reset_sequence(design,replay)
                environment_assumptions = formal_assumptions(design,replay['environment'])
            if replay.get("formal_initial_state"):
                policy = replay["formal_initial_state"]
                prior_state = ""
                if set(policy) == {"file", "sha256"}:
                    state_path = (args.replay_config.parent / policy["file"]).resolve()
                    if hashlib.sha256(state_path.read_bytes()).hexdigest() != policy["sha256"]:
                        raise ValueError("formal initial state hash changed")
                    prior_state = state_path.read_text()
                elif policy != {"mode": "rtl-reset-simulation"}:
                    raise ValueError("unsupported formal initial state policy")
                if re.search(r"\bpast\s*\(", response["ltl"]):
                    raise ValueError("snapshot initialization has no explicit past history; use a supported history policy")
                from rtl_initial_state import POLICY, generate as generate_initial_state
                # Never treat a partial memory-only file as a complete power-on
                # state. Discover all clocked storage and simulate the original
                # RTL reset prefix; existing entries must agree with that result.
                phase = "initial-state"
                initial_state = generate_initial_state(design, replay, out_dir / "initial-state",
                    prior_state, Path(os.environ.get("ZAOZI_EDA_SHELL", ZAOZI / "experiments/eda-shell")))
                snapshot_record = out_dir / "initial-state/snapshot.json"
                initial_state_record = {"policy": POLICY, "file": str(snapshot_record),
                    "sha256": hashlib.sha256(snapshot_record.read_bytes()).hexdigest()}
                reset_sequence = None
            elif not args.compile_only:
                from rtl_initial_state import prepare_native_reset
                phase = 'initial-state'
                reset_snapshot_state, reset_snapshot_record = prepare_native_reset(design, replay, out_dir/'reset-snapshot',
                    Path(os.environ.get('ZAOZI_EDA_SHELL', ZAOZI/'experiments/eda-shell')))
        input_hash = fingerprint({"files": {**trusted_hashes,
            **{str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in source.glob('*.scala')}},
            "design": inputs, "timeLimit": args.jg_time_limit,
            "resetSequence": reset_sequence, "initialState": initial_state, "resetSnapshotState": reset_snapshot_state,
            "resetSnapshotRecord": reset_snapshot_record, "clocks": clocks,
            "environmentAssumptions": environment_assumptions})
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
            (work / "semanticdb").mkdir()
            compiler_options = isolation.compiler_options(config, source, work)
            save(out_dir / "compile-options.json", compiler_options)
            cp = os.pathsep.join(config["classpath"])
            phase = "typecheck"
            with events.phase(phase):
                compiled = isolation.execute([java, "-Xmx2g", *config["jvm"], "-cp", os.pathsep.join(config["compiler"]),
                    "dotty.tools.dotc.Main", "-color:never", *compiler_options, "-classpath", cp,
                    "-d", str(classes), *map(str, sorted(source.glob("*.scala")))], config, source, work)
                (out_dir / "compile.log").write_text(compiled.stdout)
                if compiled.returncode:
                    emit({"phase": phase, "ok": False, "errors": ltl_diagnostics(parse_type_errors(compiled.stdout), source),
                          "detail": compiled.stdout[-2500:]}, 2)
            if args.compile_only:
                emit({"phase": phase, "ok": True, "isolation": "bubblewrap-compile-lower-v1"}, 0)
            phase = "lower"
            with events.phase(phase):
                lowered = isolation.execute([java, "-Xmx2g", *config["jvm"], "-cp", str(classes) + os.pathsep + cp,
                    "utRun", str(work / "artifacts")], config, source, work)
                (out_dir / "lower.log").write_text(lowered.stdout)
                if lowered.returncode:
                    diagnostic = elaboration_diagnostic(work, source) if lowered.returncode == 2 else None
                    if diagnostic is not None:
                        emit(diagnostic, 2)
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
                check = validate(sv, design, description["top"], response["labels"])
                abi = json.loads(artifact("abiFile").read_text())
                ports = [{"name": "clock", "role": "Clock", "width": 1, "signed": False},
                         {"name": "reset", "role": "Reset", "width": 1, "signed": False}]
                ports += [{"name": p.name, "role": "Clock" if p.kind == 'clock' else "Drive", "width": p.width, "signed": p.kind == "sint"}
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
                    "rtl": [str(p) for p in design.sources], "includeDirs": [str(p) for p in design.include_dirs],
                    "labels": response["labels"], "module": MODEL_MODULE,
                    "sourceSha256": hashlib.sha256((source / "ModelUT.scala").read_bytes()).hexdigest(),
                    "ltlSha256": hashlib.sha256(response["ltl"].encode()).hexdigest(),
                    "sequenceName": design.sequence_name, "itemType": design.item_type,
                    "timeLimit": args.jg_time_limit, "proofObligations": [],
                    "resetSequence": reset_sequence, "initialState": initial_state,
                    "initialStateRecord": initial_state_record, "clocks": clocks,
                    "resetSnapshotState": reset_snapshot_state, "resetSnapshotRecord": reset_snapshot_record,
                    "environmentAssumptions": environment_assumptions}
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
        report = json.loads((out_dir / "report.json").read_text())
        diagnostic=solver_diagnostic(report,job['labels'])
        if diagnostic:
            emit(diagnostic,2 if model_repair_allowed(diagnostic) else 3)
        emit({"phase": phase, "ok": True, "result": report}, 0)
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError, KeyError) as error:
        emit({"phase": phase, "ok": False, "detail": str(error),
              "error_type": type(error).__name__, "model_repair_allowed": False}, 3)


if __name__ == "__main__":
    main()
