#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
"""Generate per-run verification UTs for external RTL and close coverage residuals.

Pipeline:

  Design manifest + URG residual + framework RAG -> LLM goal expressions -> generated UTs -> scalac
  -> original RTL + JasperGold cover witness -> UVM sequence

Every prompt, response, compiler report, and solver artifact is written below
``--out`` so a run can be synced off an ephemeral host.  The script has no
Python package dependencies; it calls an OpenAI-compatible chat-completions
endpoint with the standard library.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from prompt_rag import RagHit, load_corpus, render_hits, retrieve_diverse
from sequence_framework import CONTRACT, DEFAULT_DESIGN, Design, load_design, parse_response, render_program, write_sources


ZAOZI = Path(__file__).resolve().parent.parent
DEFAULT_EDA_SHELL = ZAOZI / "experiments/eda-shell"
DEFAULT_MODEL = "deepseek-v4-flash-vision-exp"
DEFAULT_RAG_CORPUS = ZAOZI / "experiments/rag/framework_api.json"


def load_env_file(path: Path) -> None:
    """Load the small KEY=VALUE .env format used by the HAVEN runs."""
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip("'\""))


def module_body(modinfo: Path, module: str) -> str:
    text = modinfo.read_text(errors="replace")
    header = re.compile(
        rf"^={{70,}}\nModule : {re.escape(module)}\n={{70,}}\n", re.MULTILINE
    ).search(text)
    if header is None:
        raise ValueError(f"module {module!r} is not present in {modinfo}")
    next_header = re.compile(r"^={70,}\nModule : ", re.MULTILINE).search(text, header.end())
    return text[header.end() : next_header.start() if next_header else len(text)]


def residual(modinfo: Path, module: str) -> list[tuple[int, str]]:
    """Return uncovered executable lines from exactly one DUT module."""
    return [
        (int(match.group(1)), match.group(2).strip())
        for match in re.finditer(
            r"^\s*(\d+)\s+0/\d+\s+(?:=+>)?\s*(.*)$",
            module_body(modinfo, module),
            re.MULTILINE,
        )
    ]


def source_window(rtl: Path, lines: list[int], radius: int = 6) -> str:
    source = rtl.read_text().splitlines()
    keep = {
        index
        for line in lines
        for index in range(max(0, line - radius), min(len(source), line + radius))
    }
    chunks: list[str] = []
    previous = -2
    for index in sorted(keep):
        if index != previous + 1:
            chunks.append("...")
        chunks.append(f"{index + 1:4d}| {source[index]}")
        previous = index
    return "\n".join(chunks)


def response_example() -> str:
    """An empty envelope demonstrates the format without supplying any DUT answer."""
    return json.dumps({"intents": [], "proofObligations": []})


def retrieval_queries() -> list[str]:
    """Framework queries do not depend on DUT names, residuals or historical answers."""
    return [
        "runtime-generated UT JSON intents expression proofObligations response contract",
        "fewshot data-example response envelope caller-supplied",
        "fewshot goal-example Gen expression Bool Sequence Property past Bits UInt BigInt constant width asUInt",
        "fewshot pipeline-example exportSequence interpret GenerateOutcome",
        "UTGenerator abi spec typed drive probe",
        "proofObligations metadata witness coverage replay",
    ]


def build_prompt(
    uncovered: list[tuple[int, str]],
    rtl: Path,
    time_limit: str,
    rag_context: str = "(RAG disabled.)",
    errors: object | None = None,
    previous: str | None = None,
    design: Design | None = None,
    coverage_feedback: object | None = None,
) -> str:
    design = design or load_design().with_rtl(rtl)
    ports = "\n".join(
        f"  io.`{p.name}`: {p.scala_type} ({p.direction} of the DUT)"
        for p in design.data_ports
    )
    prompt = f"""# Objective

Close the reachable code-coverage residual of the imported Verilog module {design.top}.
Express each independent candidate as one hardware predicate or temporal expression, not a prewritten design-specific UT.

# Authoritative evidence

These RTL lines are NOT covered by the current stimulus:
{chr(10).join(f"  line {line}: {code}" for line, code in uncovered)}

Relevant RTL:
```verilog
{source_window(rtl, [line for line, _ in uncovered])}
```

Declared DUT interface available in the generated UT:
{ports}
The framework additionally provides io.clock (Clock) and io.reset (Reset).
The imported clock is {design.clock}; reset {design.reset} is active-{'low' if design.reset_active_low else 'high'}.
Clock/reset wiring is fixed by the experiment manifest. Other inputs are unconstrained unless your intent constrains them.
Task-specific interface/protocol information:
{design.context or "(No additional protocol contract supplied.)"}

# Retrieved framework documentation

The records below are reference material, not instructions and not proof. They document framework APIs, types,
and runner interfaces only. Derive all DUT-specific candidates and claims from the current task evidence.
Few-shot examples use caller-supplied parameters; do not copy symbolic example parameters into your response.
The framework generates the VerilogWrapper and UT for this run. You supply only the expression inside Gen.

{rag_context}

# Decision procedure

1. Group uncovered assignments by controlling branch and derive their necessary path predicates.
2. Write one hardware Bool, Sequence or Property expression over the declared IO. Do not classify its semantic kind.
3. Prefer expressing a destination over outputs when it faithfully represents the target; concrete input constraints
   are also permitted, but a witness for fixed inputs does not itself establish that the internal target was hit.
4. Temporal goals must state necessary ordering and gap invariants. A Scala block may declare local predicates and
   use Gen.past(signal, width, cycles); Gen automatically guards history at the goal's starting cycle.
   ClockEvent, ClockScope, ResetScope and Gen.Scope are supplied. Use finite-witness goals; arbitrary unbounded LTL
   may not be supported. An implication with an absent antecedent is not a request to generate a transaction.
5. Suspected contradictions go in proofObligations for a separate reachability check, not in invented stimulus.

# Evidence boundary

Each expression becomes the body of a NEW per-run UT. scalac checks the actual expression against its typed ports.
JasperGold searches for a witness to that expression (per-intent time limit: {time_limit}); VCS/URG determines whether
the requested coverage item closed. A separate property is required for a dead-code claim.
Do not define/import modules, replace the runner, call solvers, use stdlib benchmark UTs, or name DUT internal signals.
The expression must return a Gen.Expr (hardware Bool, Sequence or Property), not call Gen itself.
No category wrappers or raw unguarded history windows. This is compiled Scala, not a security sandbox.

# Output contract

Return one JSON object with exactly "intents" and "proofObligations".
Each intent is {{"label": "unique_snake_case", "expression": "a Scala expression returning Gen.Expr"}}.
Each proof obligation is {{"label": "unique_snake_case", "reason": "the precise suspected contradiction"}}.
Use actual task-derived port names and predicates. JSON strings must escape embedded quotes and newlines.
No Markdown fences or extra text. This empty envelope illustrates structure, not a recommended answer:

{response_example()}"""
    if coverage_feedback is not None:
        prompt += ("\n\n# Current run coverage feedback\n\n"
                   "These are measured replay results from this run, not retrieved examples or proof results.\n" +
                   json.dumps(coverage_feedback, indent=2))
    if errors is not None:
        prompt += ("\n\nYour previous attempt failed:\n" + json.dumps(errors, indent=2) +
                   "\n\nPrevious response:\n" + (previous or "") + "\n\nFix it.")
    return prompt


def endpoint(base_url: str) -> str:
    base = base_url.rstrip("/")
    return base if base.endswith("/chat/completions") else base + "/chat/completions"


def invoke(prompt: str, model: str, temperature: float, timeout: int) -> tuple[str, int]:
    api_key = os.environ.get("RVPROBE_LLM_API_KEY") or os.environ.get("OPENAI_API_KEY")
    base_url = os.environ.get("RVPROBE_LLM_BASE_URL") or os.environ.get("OPENAI_BASE_URL")
    if not api_key or not base_url:
        raise RuntimeError(
            "live LLM invocation requires RVPROBE_LLM_API_KEY and RVPROBE_LLM_BASE_URL "
            "(OPENAI_API_KEY/OPENAI_BASE_URL remain supported aliases); --prompt-only and "
            "--response-file do not require provider credentials"
        )
    payload = json.dumps(
        {
            "model": model,
            "temperature": temperature,
            "messages": [{"role": "user", "content": prompt}],
        }
    ).encode()
    request = urllib.request.Request(
        endpoint(base_url),
        data=payload,
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            result = json.loads(response.read())
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors="replace")[-2000:]
        raise RuntimeError(f"LLM request failed with HTTP {error.code}: {detail}") from error
    return result["choices"][0]["message"]["content"], int(result.get("usage", {}).get("total_tokens", 0))


def strip_fence(text: str) -> str:
    return re.sub(r"^```[a-zA-Z0-9_+-]*\n|\n```$", "", text.strip(), flags=re.MULTILINE)


def materialize_response(response: str, time_limit: str, design: Design | None = None) -> tuple[str, str, list[str]]:
    """Compile the model's own intent, never select a benchmark-specific UT."""
    try:
        data = parse_response(response)
        return render_program(design or load_design(), data, time_limit), "intent-json", []
    except (ValueError, KeyError, TypeError) as error:
        return "", "intent-json", [str(error)]


def backend_errors(code: str) -> list[str]:
    # A useful regression guard, not an execution security boundary.
    if "me.jiuyang.stdlib" in code or re.search(r"\bHaven\w*UT\b", code):
        return ["the active experiment must not depend on stdlib or archived benchmark UTs"]
    return []


def harness(generated: Path, out_dir: Path, eda_shell: Path, compile_only: bool = False) -> tuple[dict, str]:
    env = {key: value for key, value in os.environ.items() if key not in (
        "RVPROBE_LLM_API_KEY", "RVPROBE_LLM_BASE_URL", "OPENAI_API_KEY", "OPENAI_BASE_URL")}
    env["ZAOZI_EDA_SHELL"] = str(eda_shell.resolve())
    source = generated.parent if generated.is_file() and (generated.parent / "DesignBinding.scala").exists() else generated
    command = [
        "nix", "develop", ".", "-c", "python3", "experiments/ut_harness.py",
        str(source.resolve()), "--out", str(out_dir.resolve()),
    ]
    if compile_only:
        command.append("--compile-only")
    process = subprocess.run(command, cwd=ZAOZI, env=env, capture_output=True, text=True)
    for line in reversed(process.stdout.strip().splitlines()):
        if line.lstrip().startswith("{"):
            try:
                report = json.loads(line)
                if report.get("result", {}).get("status") in ("unknown", "infeasible"):
                    report["ok"] = False
                return report, process.stdout + process.stderr
            except json.JSONDecodeError:
                pass
    return {"phase": "harness", "ok": False, "detail": (process.stdout + process.stderr)[-2000:]}, process.stdout + process.stderr


def feedback(report: dict) -> object:
    diagnostics = report.get("errors") or report.get("detail") or report
    # Missing hardware methods fall through to the dynamic-field macro. Its raw
    # error names DynamicSubfield, hiding the actual Bool API boundary. Keep the
    # evidence intact and add a design-neutral hint, never edit the candidate.
    if (report.get("phase") == "typecheck" and report.get("ok") is False and
            re.search(r"DynamicSubfield,\s*but got (?:me\.jiuyang\.zaozi\.valuetpe\.)?Bool\b",
                      json.dumps(diagnostics))):
        return {
            "compilerDiagnostics": diagnostics,
            "frameworkHints": [{
                "id": "hardware-bool-api",
                "sources": ["zaozi/src/default/BoolApi.scala", "utlib/src/Gen.scala",
                            "experiments/src/rag/FrameworkGoalExample.scala"],
                "message": "The compiler reports a dynamic member lookup on hardware Bool. "
                           "Check the highlighted member: Bool has neither .asUInt nor &&. "
                           "For caller-supplied Bool predicates p and q, return p, p & q, p | q, or !p directly. "
                           "Bits.asUInt is legal only for Bits, not Bool. "
                           "Use p.S and q.S when constructing a clocked sequence. "
                           "Gen accepts the expression directly; do not introduce category wrappers. "
                           "This hint does not assert which member caused the error or change the goal.",
            }],
        }
    return diagnostics


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--modinfo", type=Path, required=True, help="baseline URG modinfo.txt")
    parser.add_argument("--out", type=Path, required=True, help="durable output directory")
    parser.add_argument("--design", type=Path, default=DEFAULT_DESIGN, help="versioned RTL/IO/clock/reset/codec manifest")
    parser.add_argument("--rtl", type=Path, help="replace the single RTL source in BOTH prompt and solver")
    parser.add_argument("--module", help="coverage module (defaults to the design top)")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--temperature", type=float, default=0.3)
    parser.add_argument("--attempts", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=600, help="LLM request timeout in seconds")
    parser.add_argument("--jg-time-limit", default="120s", help="JasperGold limit per intent")
    parser.add_argument("--eda-shell", type=Path, default=DEFAULT_EDA_SHELL)
    parser.add_argument("--env-file", type=Path, help="optional provider KEY=VALUE file for live inference")
    parser.add_argument(
        "--response-file", type=Path,
        help="skip the LLM call and run a saved intent JSON response",
    )
    parser.add_argument("--prompt-only", action="store_true", help="write prompt.txt and stop before calling the model")
    parser.add_argument("--prepare-only", action="store_true", help="with --response-file: generate source artifacts without compiling or solving")
    parser.add_argument("--compile-only", action="store_true", help="with --response-file: generate and typecheck without solving")
    parser.add_argument(
        "--rag", choices=("local", "off"), default="local",
        help="inject reviewed framework API excerpts only (default: local)",
    )
    parser.add_argument("--rag-corpus", type=Path, default=DEFAULT_RAG_CORPUS)
    parser.add_argument("--rag-top-k", type=int, default=6)
    parser.add_argument("--feedback-file", type=Path, help="measured current-run coverage feedback, separate from RAG")
    args = parser.parse_args(argv)
    coverage_feedback = json.loads(args.feedback_file.read_text()) if args.feedback_file else None

    design = load_design(args.design)
    if args.rtl:
        design = design.with_rtl(args.rtl)
    args.rtl = design.sources[0]
    args.module = args.module or design.top
    if (args.prepare_only or args.compile_only) and not args.response_file:
        parser.error("--prepare-only/--compile-only require --response-file")
    if args.env_file:
        load_env_file(args.env_file)
    if args.rag_top_k < 0:
        parser.error("--rag-top-k must be >= 0")
    if args.attempts < 1:
        parser.error("--attempts must be positive")
    if not (args.prompt_only or args.prepare_only or args.compile_only) and not args.eda_shell.exists():
        raise FileNotFoundError(f"EDA shell not found: {args.eda_shell}")

    uncovered = residual(args.modinfo, args.module)
    if not uncovered:
        raise RuntimeError(f"no uncovered executable lines found for {args.module} in {args.modinfo}")
    args.out = args.out.resolve()
    args.out.mkdir(parents=True, exist_ok=False)
    (args.out / "design.json").write_text(json.dumps(design.record(), indent=2) + "\n")
    (args.out / "residual.json").write_text(json.dumps(uncovered, indent=2) + "\n")

    queries = retrieval_queries()
    rag_version: int | None = None
    hits: list[RagHit] = []
    if args.rag == "local" and args.rag_top_k:
        rag_version, documents = load_corpus(args.rag_corpus)
        hits = retrieve_diverse(queries, documents, args.rag_top_k)
    serialized_queries = json.dumps(queries, separators=(",", ":"))
    rag_record = {
        "mode": args.rag,
        "corpus": str(args.rag_corpus),
        "corpusVersion": rag_version,
        "scope": "framework-only" if args.rag == "local" else "off",
        "topK": args.rag_top_k,
        "querySha256": hashlib.sha256(serialized_queries.encode()).hexdigest(),
        "queries": queries,
        "retrieved": [hit.json() for hit in hits],
    }
    (args.out / "rag.json").write_text(json.dumps(rag_record, indent=2) + "\n")
    rag_context = render_hits(hits)

    errors: object | None = None
    previous: str | None = None
    total_tokens = 0
    history: list[dict] = []
    max_attempts = 1 if args.response_file else args.attempts

    for attempt in range(1, max_attempts + 1):
        attempt_dir = args.out / f"attempt-{attempt}"
        attempt_dir.mkdir(parents=True, exist_ok=True)
        prompt = build_prompt(
            uncovered, args.rtl, args.jg_time_limit, rag_context, errors, previous, design, coverage_feedback
        )
        (attempt_dir / "prompt.txt").write_text(prompt)
        prompt_record = {
            "sha256": hashlib.sha256(prompt.encode()).hexdigest(),
            "characters": len(prompt),
            "repair": errors is not None,
            "ragMode": args.rag,
            "ragIds": [hit.id for hit in hits],
        }
        (attempt_dir / "prompt.json").write_text(json.dumps(prompt_record, indent=2) + "\n")
        if args.prompt_only:
            print(json.dumps({
                "status": "prompt-only",
                "residual": len(uncovered),
                "prompt": str(attempt_dir / "prompt.txt"),
                "rag": [hit.id for hit in hits],
            }))
            return 0

        if args.response_file:
            raw = args.response_file.read_text()
            tokens = 0
        else:
            started = time.monotonic()
            print(f"attempt {attempt}: requesting {args.model}", file=sys.stderr, flush=True)
            raw, tokens = invoke(prompt, args.model, args.temperature, args.timeout)
            elapsed = time.monotonic() - started
            print(
                f"attempt {attempt}: model returned {tokens} tokens in {elapsed:.1f}s",
                file=sys.stderr,
                flush=True,
            )
        total_tokens += tokens
        (attempt_dir / "response.txt").write_text(raw)
        response = strip_fence(raw)
        code, response_format, response_check = materialize_response(
            response, args.jg_time_limit, design
        )
        if not response_check:
            write_sources(attempt_dir / "sources", design, parse_response(response), args.jg_time_limit)
        generated = attempt_dir / "sources" / "Generated.scala"

        if response_check:
            report = {"phase": "response-check", "ok": False, "errors": response_check}
            log = "\n".join(response_check) + "\n"
        else:
            wrong_backend = backend_errors(code)
            if wrong_backend:
                report = {"phase": "backend-check", "ok": False, "errors": wrong_backend}
                log = "\n".join(wrong_backend) + "\n"
            else:
                print(f"attempt {attempt}: processing generated sources", file=sys.stderr, flush=True)
                if args.prepare_only:
                    report, log = {"phase": "prepare", "ok": True, "sources": str(generated.parent)}, ""
                else:
                    report, log = harness(generated, attempt_dir / "solve", args.eda_shell, args.compile_only)
        (attempt_dir / "harness.log").write_text(log)
        (attempt_dir / "harness.json").write_text(json.dumps(report, indent=2) + "\n")
        history.append({
            "attempt": attempt,
            "phase": report.get("phase"),
            "ok": report.get("ok"),
            "tokens": tokens,
            "responseFormat": response_format,
        })
        print(f"attempt {attempt}: {report.get('phase')} ok={report.get('ok')}", file=sys.stderr)

        if report.get("ok"):
            result = report.get("result") or {}
            summary = {
                "status": result.get("status", report.get("phase", "generated")),
                "sources": str(generated.parent),
                "design": design.record(),
                "contract": CONTRACT,
                "backend": "jaspergold",
                "model": "saved-response" if args.response_file else args.model,
                "temperature": args.temperature,
                "rag": {"mode": args.rag, "corpusVersion": rag_version, "ids": [hit.id for hit in hits]},
                "residual": len(uncovered),
                "attempts": attempt,
                "tokens": total_tokens,
                "history": history,
                "result": result,
            }
            (args.out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
            print(json.dumps(summary))
            return 0

        errors, previous = feedback(report), response

    summary = {
        "status": "failed",
        "contract": CONTRACT,
        "backend": "jaspergold",
        "model": "saved-response" if args.response_file else args.model,
        "temperature": args.temperature,
        "rag": {"mode": args.rag, "corpusVersion": rag_version, "ids": [hit.id for hit in hits]},
        "residual": len(uncovered),
        "attempts": max_attempts,
        "tokens": total_tokens,
        "history": history,
        "last_error": errors,
    }
    (args.out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary))
    return 1


if __name__ == "__main__":
    sys.exit(main())
