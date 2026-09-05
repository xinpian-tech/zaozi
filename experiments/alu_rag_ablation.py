#!/usr/bin/env python3
"""Repeated ALU RAG ablation: generate, solve serially, replay in one VCS build.

Each mode receives the same residual, prompt contract, model and temperature.
Only retrieved context changes. RAG is restricted to reviewed framework API
excerpts; design answers and historical solve results are forbidden.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path
import random
import re
import shlex
import shutil
import statistics
import subprocess
import time

import alu_residual_loop as loop
from prompt_rag import load_corpus, render_hits, retrieve_diverse
import urg_score


def save(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def generate(args) -> None:
    version, documents = load_corpus(loop.DEFAULT_RAG_CORPUS)
    args.out.mkdir(parents=True, exist_ok=False)
    inputs = {
        1: args.tb / "urgReport/modinfo.txt",
        2: args.tb / "round2_urg/modinfo.txt",
    }
    manifest = {
        "experiment": "ALU framework-only RAG ablation",
        "date": time.strftime("%Y-%m-%d", time.gmtime()),
        "corpus_scope": "framework-only", "corpus_version": version,
        "model": args.model, "temperature": args.temperature,
        "samples_per_mode_per_round": args.samples, "attempts_per_sample": 1,
        "order_seed": 20260905, "llm_workers": args.workers,
        "limitation": "Fixed ALU residual tasks and one replay seed; framework-only RAG, not cross-design evidence.",
        "rtl_sha256": digest(loop.DEFAULT_RTL),
        "corpus_sha256": digest(loop.DEFAULT_RAG_CORPUS),
        "script_sha256": digest(Path(__file__)),
        "loop_sha256": digest(Path(loop.__file__)),
        "jobs": [],
    }
    jobs = []
    for round_no, modinfo in inputs.items():
        uncovered = loop.residual(modinfo, "alu_top")
        queries = loop.retrieval_queries()
        hits = retrieve_diverse(queries, documents, top_k=6)
        for sample in range(1, args.samples + 1):
            modes = ["local", "off"] if sample % 2 else ["off", "local"]
            for mode in modes:
                name = f"round{round_no}-{mode}-{sample}"
                directory = args.out / name
                directory.mkdir()
                context = render_hits(hits if mode == "local" else [])
                prompt = loop.build_prompt(uncovered, loop.DEFAULT_RTL, "120s", context)
                (directory / "prompt.txt").write_text(prompt)
                save(directory / "residual.json", uncovered)
                save(directory / "rag.json", {
                    "mode": mode, "queries": queries,
                    "scope": "framework-only" if mode == "local" else "off",
                    "retrieved": [hit.json() for hit in hits] if mode == "local" else [],
                })
                job = {
                    "name": name, "round": round_no, "mode": mode, "sample": sample,
                    "prompt_sha256": hashlib.sha256(prompt.encode()).hexdigest(),
                    "modinfo_sha256": digest(modinfo),
                }
                jobs.append(job)
    random.Random(20260905).shuffle(jobs)
    manifest["jobs"] = jobs
    save(args.out / "manifest.json", manifest)
    shutil.copyfile(loop.DEFAULT_RAG_CORPUS, args.out / "corpus.json")
    shutil.copyfile(loop.DEFAULT_RTL, args.out / "alu_top.v")
    shutil.copyfile(Path(loop.__file__), args.out / "loop-snapshot.py")
    shutil.copyfile(Path(__file__), args.out / "ablation-snapshot.py")
    for source in {document.source for document in documents}:
        snapshot = args.out / "framework-sources" / source
        snapshot.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(loop.ZAOZI / source, snapshot)

    def one(job):
        directory = args.out / job["name"]
        started = time.monotonic()
        print(f"request {job['name']}", flush=True)
        try:
            raw, tokens = loop.invoke((directory / "prompt.txt").read_text(),
                                      args.model, args.temperature, args.timeout)
            (directory / "response.txt").write_text(raw)
            code, response_format, errors = loop.materialize_response(loop.strip_fence(raw), "120s")
            errors += loop.backend_errors(code)
            (directory / "Generated.scala").write_text(code)
            result = {**job, "ok": not errors, "tokens": tokens,
                      "response_format": response_format, "errors": errors,
                      "response_characters": len(raw)}
        except Exception as error:
            result = {**job, "ok": False, "phase": "provider",
                      "error_type": type(error).__name__}
        result["seconds"] = round(time.monotonic() - started, 3)
        save(directory / "generation.json", result)
        print(f"returned {job['name']} ok={result['ok']} tokens={result.get('tokens')} "
              f"seconds={result['seconds']}", flush=True)
        return result

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(one, job) for job in jobs]
        results = [future.result() for future in as_completed(futures)]
    save(args.out / "generations.json", sorted(results, key=lambda row: row["name"]))


def solve(args) -> None:
    jobs = json.loads((args.out / "manifest.json").read_text())["jobs"]
    for job in jobs:
        directory = args.out / job["name"]
        if (directory / "harness.json").exists():
            continue
        if not (directory / "generation.json").exists():
            continue
        generation = json.loads((directory / "generation.json").read_text())
        if not generation["ok"]:
            continue
        print(f"solve {job['name']}", flush=True)
        started = time.monotonic()
        report, log = loop.harness(directory / "Generated.scala", directory / "solve", args.eda_shell)
        (directory / "harness.log").write_text(log)
        report["wall_seconds"] = round(time.monotonic() - started, 3)
        save(directory / "harness.json", report)
        print(f"solved {job['name']} ok={report.get('ok')} "
              f"status={report.get('result', {}).get('status')}", flush=True)


def eda(args, directory: Path, command: list[str], log: Path, timeout=900):
    with log.open("w") as stream:
        proc = subprocess.run([str(args.eda_shell), "-c",
                               f"cd {shlex.quote(str(directory))} && {shlex.join(command)}"],
                              stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
    if proc.returncode:
        raise RuntimeError(f"{command[0]} failed ({proc.returncode}); see {log}")


def replay(args) -> None:
    """Build all sample sequences together; pair each with the same-build baseline."""
    work = args.out / "tb"
    work.mkdir(exist_ok=False)
    for source in args.tb.iterdir():
        if source.is_file() and (source.suffix in (".v", ".sv") or source.name == "filelist.f"):
            shutil.copyfile(source, work / source.name)
    # Keep the original bulk fill, driver, DUT, monitor and scoreboard.
    jobs = json.loads((args.out / "manifest.json").read_text())["jobs"]
    valid = []
    for job in jobs:
        report_file = args.out / job["name"] / "harness.json"
        if not report_file.exists():
            continue
        report = json.loads(report_file.read_text())
        if not report.get("ok"):
            continue
        result = report.get("result", {})
        sequence = Path(result.get("sequenceFile", ""))
        if not sequence.is_file():
            continue
        name = "rag_" + job["name"].replace("-", "_")
        (work / f"{name}.sv").write_text(sequence.read_text().replace("rvprobe_llm_seq", name))
        valid.append({**job, "class": name})
    previous = args.previous_sequence.read_text().replace("rvprobe_llm_seq", "rag_previous_seq")
    (work / "rag_previous_seq.sv").write_text(previous)
    package = (work / "alu_top_pkg.sv").read_text()
    package = package.replace('  `include "rvprobe_llm_seq.sv"',
                              '  `include "rag_previous_seq.sv"\n' + "\n".join(
                                  f'  `include "{job["class"]}.sv"' for job in valid))
    (work / "alu_top_pkg.sv").write_text(package)
    branches = "\n".join(
        f'''      "{job['name']}": begin
        {job['class']} selected;
        selected = {job['class']}::type_id::create("selected");
        selected.start(m_env.m_agent.m_sequencer);
      end''' for job in valid)
    (work / "alu_top_test.sv").write_text('''class alu_top_test extends uvm_test;
  `uvm_component_utils(alu_top_test)
  alu_top_env m_env;
  function new(string name = "alu_top_test", uvm_component parent = null);
    super.new(name, parent);
  endfunction
  function void build_phase(uvm_phase phase);
    super.build_phase(phase);
    m_env = alu_top_env::type_id::create("m_env", this);
  endfunction
  task run_phase(uvm_phase phase);
    string sample_name;
    rvprobe_bulk_seq seq;
    rvprobe_fp_seq fp_seq;
    phase.raise_objection(this);
    seq = rvprobe_bulk_seq::type_id::create("seq");
    seq.start(m_env.m_agent.m_sequencer);
    fp_seq = rvprobe_fp_seq::type_id::create("fp_seq");
    fp_seq.start(m_env.m_agent.m_sequencer);
    if ($test$plusargs("ROUND2")) begin
      rag_previous_seq previous;
      previous = rag_previous_seq::type_id::create("previous");
      previous.start(m_env.m_agent.m_sequencer);
    end
    if (!$value$plusargs("RAG_SAMPLE=%s", sample_name)) sample_name = "baseline";
    case (sample_name)
''' + branches + '''
      "baseline": begin end
      default: `uvm_fatal("RAG_SAMPLE", "unknown sample")
    endcase
    #1000;
    phase.drop_objection(this);
  endtask
endclass
''')
    save(args.out / "replay-manifest.json", {
        "seed": 1, "valid_jobs": valid, "previous_sequence_sha256": digest(args.previous_sequence),
        "tb_source_hashes": {p.name: digest(p) for p in work.iterdir() if p.is_file()},
    })
    print("VCS compiling shared replay bench", flush=True)
    eda(args, work, ["vcs", "+vcs+lic+wait", "-sverilog", "-ntb_opts", "uvm",
                    "-cm", "line+cond+tgl+fsm+branch", "-timescale=1ns/1ps", "+incdir+.",
                    "+verilog2001ext+.v", "+error+100", "-f", "filelist.f", "-o", "simv"],
        work / "build.log")
    results = []
    for job in [{"name": "baseline-round1", "round": 1},
                {"name": "baseline-round2", "round": 2}, *valid]:
        directory = args.out / "replay" / job["name"]
        directory.mkdir(parents=True)
        db = directory / "simv.vdb"
        shutil.copytree(work / "simv.vdb", db)
        command = [str(work / "simv"), "+vcs+lic+wait", "+UVM_TESTNAME=alu_top_test",
                   "+ntb_random_seed=1", "+UVM_TIMEOUT=5000000000", "+UVM_VERBOSITY=UVM_LOW",
                   "-cm", "line+cond+tgl+fsm+branch", "-cm_dir", str(db),
                   "+RAG_SAMPLE=" + (job["name"] if "class" in job else "baseline")]
        if job["round"] == 2:
            command.append("+ROUND2")
        print(f"VCS replay {job['name']}", flush=True)
        started = time.monotonic()
        eda(args, work, command, directory / "sim.log")
        log = (directory / "sim.log").read_text(errors="replace")
        if not re.search(r"UVM_ERROR\s*:\s*0\b", log) or not re.search(r"UVM_FATAL\s*:\s*0\b", log):
            raise RuntimeError(f"UVM failure in {directory / 'sim.log'}")
        urg = directory / "urgReport"
        eda(args, work, ["urg", "-dir", str(db), "-metric", "line+branch+tgl+cond",
                        "-format", "text", "-report", str(urg)], directory / "urg.log")
        percent, score, bins = urg_score.score(urg_score.parse(str(urg / "modinfo.txt")), ["alu_top"])
        uncovered = loop.residual(urg / "modinfo.txt", "alu_top")
        row = {**job, "score": score, "percent": percent, "bins": bins,
               "uncovered_lines": [line for line, _ in uncovered],
               "wall_seconds": round(time.monotonic() - started, 3)}
        save(directory / "coverage.json", row)
        results.append(row)
        save(args.out / "coverage.json", results)
        print(f"coverage {job['name']} score={score:.2f} residual={len(uncovered)}", flush=True)


def summarize(args) -> None:
    manifest = json.loads((args.out / "manifest.json").read_text())
    cover_file = args.out / "coverage.json"
    coverage = {row["name"]: row for row in json.loads(cover_file.read_text())} if cover_file.exists() else {}
    rows = []
    for job in manifest["jobs"]:
        directory = args.out / job["name"]
        generation_file = directory / "generation.json"
        if not generation_file.exists():
            continue
        generation = json.loads(generation_file.read_text())
        harness_file = directory / "harness.json"
        harness = json.loads(harness_file.read_text()) if harness_file.exists() else {}
        result = harness.get("result", {})
        response_file = directory / "response.txt"
        row = {
            **job, "generation_ok": generation["ok"], "tokens": generation.get("tokens"),
            "raw_response": response_file.read_text() if response_file.exists() else None,
            "response_format": generation.get("response_format"),
            "generation_errors": generation.get("errors", []),
            "generation_error_type": generation.get("error_type"),
            "generation_seconds": generation["seconds"],
            "harness_ok": harness.get("ok"), "status": result.get("status"),
            "candidate_count": len(result["intents"]) if "intents" in result else None,
            "proof_obligations": result.get("proofObligations"),
            "candidate_solver_ms": sum(int(intent["ms"]) for intent in result.get("intents", []))
                if "intents" in result else None,
        }
        if job["name"] in coverage:
            measured = coverage[job["name"]]
            baseline = coverage[f"baseline-round{job['round']}"]
            target_lines = {line for line, _ in json.loads((directory / "residual.json").read_text())}
            eligible = target_lines & set(baseline["uncovered_lines"])
            row.update({
                "coverage_score": measured["score"],
                "score_gain": measured["score"] - baseline["score"],
                "closed_target_lines": sorted(eligible - set(measured["uncovered_lines"])),
                "remaining_target_lines": sorted(target_lines & set(measured["uncovered_lines"])),
                "targets_already_covered_in_baseline": sorted(target_lines - eligible),
                "baseline_uncovered_lines": baseline["uncovered_lines"],
                "coverage_percent": measured["percent"],
                "closed_target_count": len(eligible - set(measured["uncovered_lines"])),
            })
        rows.append(row)
    cells = []
    for round_no in (1, 2):
        for mode in ("off", "local"):
            group = [row for row in rows if row["round"] == round_no and row["mode"] == mode]
            cell = {"round": round_no, "mode": mode, "samples_returned": len(group),
                    "samples_planned": sum(job["round"] == round_no and job["mode"] == mode
                                           for job in manifest["jobs"]),
                    "generation_passes": sum(row["generation_ok"] for row in group),
                    "harness_passes": sum(row["harness_ok"] is True for row in group),
                    "proof_only_samples": sum(row["status"] == "proof-required" for row in group),
                    "replays": sum("coverage_score" in row for row in group)}
            for key in ("tokens", "generation_seconds", "candidate_count", "candidate_solver_ms",
                        "coverage_score", "score_gain", "closed_target_count"):
                values = [row[key] for row in group if row.get(key) is not None]
                cell[key] = {"n": len(values), "mean": statistics.mean(values),
                             "min": min(values), "max": max(values)} if values else None
            cells.append(cell)
    summary = {
        "experiment": manifest.get("experiment", "ALU legacy answer-contaminated RAG ablation"),
        "date": manifest.get("date", "2026-09-05"),
        "corpus_scope": manifest.get("corpus_scope", "legacy-answer-contaminated"),
        "evaluation_status": "framework-only" if manifest.get("corpus_scope") == "framework-only"
            else "invalid-for-framework-rag",
        "model": manifest["model"], "temperature": manifest["temperature"],
        "attempts_per_sample": 1, "samples_planned": len(manifest["jobs"]),
        "complete": len(rows) == len(manifest["jobs"]) and all(
            not row["generation_ok"] or row["harness_ok"] is False or
            (row["harness_ok"] is True and "coverage_score" in row) for row in rows),
        "limitation": manifest["limitation"],
        "comparison": "Same revised prompt and typed-fragment contract; only RAG context differs.",
        "generation_manifest": manifest,
        "summary_script_sha256": digest(Path(__file__)),
        "replay": "One VCS build; simulation seed 1; same baseline per round; DUT-only 4-metric score.",
        "cells": cells, "samples": sorted(rows, key=lambda row: row["name"]),
        "baselines": [row for key, row in coverage.items() if key.startswith("baseline-")],
        "artifacts": str(args.out),
    }
    save(args.out / "summary.json", summary)
    print(json.dumps(cells, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=("generate", "solve", "replay", "summarize"))
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--samples", type=int, default=5)
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--model", default=loop.DEFAULT_MODEL)
    parser.add_argument("--temperature", type=float, default=0.3)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--follow", action="store_true", help="solve samples as the generate stage finishes them")
    parser.add_argument("--eda-shell", type=Path, default=loop.DEFAULT_EDA_SHELL)
    parser.add_argument("--tb", type=Path, default=loop.ZAOZI / "out/experiments/alu-main-baseline")
    parser.add_argument("--previous-sequence", type=Path, default=loop.ZAOZI /
                        "out/experiments/alu-main-jg-deepseek/attempt-1/solve/rvprobe_llm_seq.sv")
    args = parser.parse_args()
    args.out = args.out.resolve()
    args.tb = args.tb.resolve()
    args.eda_shell = args.eda_shell.resolve()
    if args.env_file:
        loop.load_env_file(args.env_file)
    if args.samples < 1 or args.workers < 1:
        parser.error("samples and workers must be positive")
    if args.stage == "solve" and args.follow:
        while not (args.out / "generations.json").exists():
            solve(args)
            time.sleep(5)
    {"generate": generate, "solve": solve, "replay": replay, "summarize": summarize}[args.stage](args)


if __name__ == "__main__":
    main()
