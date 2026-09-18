#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
"""Generate per-run verification UTs for external RTL and close coverage residuals.

Pipeline:

  Design manifest + URG residual + framework RAG -> LLM LTL -> fixed UT -> scalac
  -> original RTL + JasperGold cover witness -> UVM sequence

Every prompt, response, compiler report, and solver artifact is written below
``--out`` so a run can be synced off an ephemeral host.  The script has no
Python package dependencies; it calls an OpenAI-compatible chat-completions
endpoint with the standard library.
"""

from __future__ import annotations

import backend_imports
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
from rvprobe.backend.process import run as run_process
from run_records import Records, save, fingerprint, finish, utc, framework_hashes, model_usage, response_metadata
from prompt_context import RVPROBE_BATCH_INSTRUCTION, rvprobe_batch_instruction
from pathlib import Path

from prompt_rag import RagHit, load_corpus, render_hits, retrieve_diverse
from rvprobe_skill import snapshot, load_snapshot, SKILL_PROTOCOL, invoke_with_skill, journal_repairs
from sequence_framework import CONTRACT, DEFAULT_DESIGN, Design, load_design, parse_response, port_bindings, render_program, render_model_ut, write_sources, check_saved_sources
from task_context import TaskContext, RepairContext, POLICY as RTL_CONTEXT_POLICY, INSTRUCTION as TASK_ACCESS_INSTRUCTION
from ltl_source import normalize, OutputFormatError
from framework_runtime import runtime_hashes
from repair_policy import model_repair_allowed
from provider_failure import ProviderFailure, incomplete_response
from rvprobe_model_options import DEFAULT_REASONING_EFFORT, REASONING_EFFORTS


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


def require_provider_credentials(path: Path | None = None) -> None:
    """Check credential presence without importing secret values into this process."""
    present = {key for key in ("RVPROBE_LLM_API_KEY", "RVPROBE_LLM_BASE_URL",
                               "OPENAI_API_KEY", "OPENAI_BASE_URL")
               if os.environ.get(key)}
    if path is not None:
        for raw in path.read_text().splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            if value.strip().strip("'\""):
                present.add(key.strip())
    if (not present.intersection(("RVPROBE_LLM_API_KEY", "OPENAI_API_KEY")) or
            not present.intersection(("RVPROBE_LLM_BASE_URL", "OPENAI_BASE_URL"))):
        raise RuntimeError(
            "live LLM invocation requires RVPROBE_LLM_API_KEY and RVPROBE_LLM_BASE_URL "
            "(OPENAI_API_KEY/OPENAI_BASE_URL remain supported aliases)"
        )


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
        (int(match.group(1)), match.group(4).strip())
        for match in re.finditer(
            r"^\s*(\d+)\s+(\d+)/(\d+)\s+(?:=+>)?\s*(.*)$",
            module_body(modinfo, module),
            re.MULTILINE,
        ) if int(match.group(2)) < int(match.group(3))
    ]


def design_evidence(design: Design) -> str:
    """Full versioned sources/includes, never a lossy residual-line window or RAG answer."""
    paths = list(design.sources)
    paths += [p for directory in design.include_dirs for p in sorted(directory.rglob("*")) if p.is_file() and p not in paths]
    sections = []
    size = 0
    for path in paths:
        raw = path.read_bytes()
        try:
            text, encoding = raw.decode('utf-8'), 'utf-8'
        except UnicodeDecodeError:
            # Byte-preserving display for legacy RTL comments. EDA still reads
            # the original bytes and the evidence hash remains unchanged.
            text, encoding = raw.decode('latin-1'), 'latin-1 (legacy byte-preserving display)'
        size += len(text)
        if size > 1_000_000:
            raise ValueError("full RTL context exceeds 1,000,000 characters; provide an explicit smaller design manifest, never silently truncate")
        numbered = "\n".join(f"{index}: {line}" for index, line in enumerate(text.splitlines(), 1))
        sections.append(f"File: {path}\nSHA-256: {hashlib.sha256(raw).hexdigest()}\nSource text decoding: {encoding}\n```verilog\n{numbered}\n```")
    return "\n\n".join(sections)


def response_example() -> str:
    """Symbolic syntax only, not a DUT answer."""
    return 'Gen(p.##(gap)(q), "ordered_events")'


def retrieval_queries() -> list[str]:
    """Retrieve only expression APIs; UT construction is not model work."""
    return ["Gen Expr Referable Bool Sequence Property", "Some hi lo repeat goto",
            "unary_ property negation", "followed until strong weak", "past delay Node"]


def prompt_json(value):
    """Lossless JSON compaction; never truncate evidence or select DUT-specific fields."""
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def framework_catalog(hits):
    if not hits:
        return 'Core LTL semantics and examples are supplied by the frozen skill. No additional reference is required.'
    return ('The frozen skill owns core LTL semantics and usage. Additional references are available '
            'via read_framework(id); read only what the skill does not cover.\n'+
            prompt_json([{'id':h.id,'title':h.title,'characters':len(h.content)} for h in hits]))


def supplemental_references(hits, skill=None):
    """Only expression API references are indexed; no complete-UT compatibility cards."""
    return list(hits)


def prompt_sections(prompt):
    """Exact character/byte accounting, not an estimated provider token count."""
    starts = list(re.finditer(r"(?m)^# ([^\n]+)\n", prompt))
    boundaries = [0] + [m.start() for m in starts if m.start()] + [len(prompt)]
    sections = []
    for start, end in zip(boundaries, boundaries[1:]):
        part = prompt[start:end]
        heading = re.match(r"# ([^\n]+)", part)
        sections.append({"section": heading[1] if heading else "preamble",
                         "characters": len(part), "utf8_bytes": len(part.encode())})
    return sections


def io_contract(design):
    aliases = port_bindings(design)
    return "\n".join(
        f'{aliases[p.name]}: {p.scala_type} ({p.direction} of DUT'
        + (f'; RTL port {p.name}' if aliases[p.name] != p.name else '') + ')'
        for p in design.data_ports
    ) + (
        "\nclock: Clock (framework clock)\nreset: Reset (normalized active-high reset)\n"
        "Use these identifiers directly, without io.; they retain their original signal types. "
        "Do not redefine port identifiers. Bool/Sequence concatenation accepts ### and .##(...)(...) "
        "without explicit .S on Bool operands. "
        "clock and reset are scope handles, not hardware Bool predicates: never use either as a Gen operand. "
        "ClockEvent, ClockScope and ResetScope are already in scope. The framework owns DUT wiring, "
        "reset polarity and post-reset initialization; do not redeclare them or model reset transitions.")


def build_prompt(
    uncovered: list[tuple[int, str]],
    rtl: Path,
    time_limit: str,
    rag_context: str = "(RAG disabled.)",
    errors: object | None = None,
    previous: str | None = None,
    design: Design | None = None,
    coverage_feedback: object | None = None,
    sequences_per_intent: int = 1,
    skill_context: bool = False,
) -> str:
    design = design or load_design().with_rtl(rtl)
    output = """Return only raw Scala LTL: local vals, supplied helper calls and Gen(expression, "unique_snake_case_label").
Use built-in Ltl helpers; do not generate helper definitions (def or lambdas).
Use 1..64 independently solvable goals, subject to the task batch limit. No JSON or prose;
prefer no Markdown. Exactly one whole-response scala code fence is accepted without changing its body;
no imports, module, architecture, IO assignments, environment declarations or proof classification.
The framework extracts labels and inserts this fragment into one fixed UT.
If no new target remains, return STOP alone. STOP is not proof of unreachability or coverage closure."""
    if errors is not None:
        from repair_diagnostics import project
        format_only = RepairContext.is_format_only(errors)
        instruction = ('Correct only the response envelope. Return the same LTL body without surrounding prose, '
                       'JSON or multiple code blocks. Do not alter goals, operands or temporal conditions. '
                       'Framework references and RTL tools are unavailable for this format-only repair.' if format_only else
                       'Correct only syntax, types or API use in the previous LTL. Preserve all goals, operands and temporal '
                       'conditions; do not replan coverage, weaken intent or claim impossibility. The frozen skill supplies APIs.')
        capability_repair = isinstance(errors, list) and any(
            isinstance(error, dict) and error.get('code') == 'jg_unsupported_liveness_cover' for error in errors)
        shortfall_repair = isinstance(errors, list) and any(
            isinstance(error, dict) and str(error.get('code', '')).startswith('jg_goal_') for error in errors)
        if capability_repair:
            instruction = ('Repair only the unsupported Cover expression. Preserve every Gen label and its original '
                'verification intent and output checks. Do not return STOP, remove goals, add assumptions or invent '
                'a temporal bound. A finite event sequence must be justified by the specification/configuration; '
                'backend acceptance alone does not establish semantic equivalence.\n\n# DUT specification\n\n'
                + (design.context or '(No additional protocol contract supplied.)'))
        elif shortfall_repair:
            instruction = ('Repair only the goals named by the solver diagnostics. Keep every already-generated goal '
                'expression and every Gen label unchanged. Preserve each failed goal\'s verification intent and output '
                'checks; make only its necessary finite setup, handshake, history and temporal order satisfiable. Do '
                'not return STOP, remove or replace goals, add assumptions, weaken an output check into input-only '
                'activity, or invent a timing bound. Solver infeasible/unknown is feedback about the expression as '
                'written, not proof that the verification intent is impossible.\n\n# DUT specification\n\n'
                + (design.context or '(No additional protocol contract supplied.)'))
        return f'''# Local LTL repair

{instruction}
RTL search and coverage replanning are unavailable during local repair.

# DUT IO

{io_contract(design)}

# Diagnostics

{prompt_json(project(errors))}

# Previous LTL (untrusted source)

{previous or ""}

# Output

{output}
'''
    batch_limit = (coverage_feedback or {}).get('intent_batch_limit', 4) 
    if type(batch_limit) is not int or not 1 <= batch_limit <= 8:
        batch_limit = 4
    metric_priority = '''Coverage prioritization: compare the measured metric percentages and spend at least
one selected intent on the largest deficit. When toggle coverage is materially below line/condition/branch
coverage, make that intent exercise legal, observable input/control variation across repeated handshakes so
both transition directions can be reached; do not satisfy a toggle gap by adding a constant-only predicate,
an input-only surrogate for an output check, or an unrelated assumption.''' 
    return f'''# Objective

Express a small batch of finite verification intents using LTL on DUT IO.
{rvprobe_batch_instruction(batch_limit)}
{metric_priority}
Choose goals from spec and measured gaps, including condition, toggle, branch and FSM coverage.
Do not stop merely because all lines are covered; do not classify every residual.
Your deliverable is a symbolic temporal predicate, not a concrete test vector or waveform.
Leave witness search and input-value enumeration to the solver; encode the required handshake,
history and output checks without manually simulating candidate traces. Read RTL only to resolve
missing facts needed to express those checks. Do not solve or prove all remaining gaps in this response.
The frozen skill supplies LTL API semantics, usage and symbolic examples.

# DUT specification

{design.context or "(No additional protocol contract supplied.)"}

# DUT IO

{io_contract(design)}

# Read-only task access

{TASK_ACCESS_INSTRUCTION}
Read RTL only as needed using the supplied file IDs. Derive DUT-specific goals from current task evidence,
never historical answers. If the specification, IO and supplied feedback already determine a finite intent,
output LTL in the first response without a tool call. HAVEN transaction templates do not constrain these raw IO goals.

# Additional LTL references

{rag_context}

# Execution boundary

Use Gen only; no Assume, restrict, extra Assert/Cover, DUT-internal access or host operations.
Scenario conditions belong inside each goal. Express sufficient past history and necessary gap invariants.
Each Gen is solved independently with limit {time_limit}; other goals are not assumptions.
The framework samples up to {sequences_per_intent} distinct sequences per intent; do not duplicate goals for sampling.
When accepted_ltl includes native_replay_outcomes, treat only native-validated or partial witnesses as accepted
evidence. Do not repeat an unresolved expression unchanged; repair its legal initialization/handshake/history
or choose a different measured residual. A formal hit rejected by four-state replay is not coverage progress.
The fixed UT is compiled in the existing sandbox; original IO/wiring checks, native LTL replay and measured
coverage determine acceptance. A compiled or solved goal alone establishes neither coverage nor correctness.

# Output

{output}
'''


def endpoint(base_url: str) -> str:
    base = base_url.rstrip("/")
    return base if base.endswith("/chat/completions") else base + "/chat/completions"


def send_completion(payload: dict, timeout: int) -> dict:
    from request_deadline import request_deadline
    api_key = os.environ.get("RVPROBE_LLM_API_KEY") or os.environ.get("OPENAI_API_KEY")
    base_url = os.environ.get("RVPROBE_LLM_BASE_URL") or os.environ.get("OPENAI_BASE_URL")
    if not api_key or not base_url:
        raise RuntimeError(
            "live LLM invocation requires RVPROBE_LLM_API_KEY and RVPROBE_LLM_BASE_URL "
            "(OPENAI_API_KEY/OPENAI_BASE_URL remain supported aliases); --prompt-only and "
            "--response-file do not require provider credentials"
        )
    with request_deadline(timeout):
        request = urllib.request.Request(
            endpoint(base_url),
            data=json.dumps(payload).encode(),
            headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=timeout) as response:
            result = json.loads(response.read())
    return result


def invoke(prompt: str, model: str, temperature: float, timeout: int) -> tuple[str, dict]:
    result = send_completion({"model": model, "temperature": temperature,
                              "messages": [{"role": "user", "content": prompt}]}, timeout)
    return result["choices"][0]["message"].get("content"), {
        **model_usage(result), **response_metadata(result),
        "requested_model": model, "reported_model": result.get("model"), "response_id": result.get("id")}


def strip_fence(text: str) -> str:
    return re.sub(r"^```[a-zA-Z0-9_+-]*\n|\n```$", "", text.strip(), flags=re.MULTILINE)


def materialize_response(response: str, time_limit: str, design: Design | None = None, *, max_intents=None) -> tuple[str, str, list[object]]:
    """Validate the raw LTL and deterministically construct its compilation unit."""
    try:
        data = parse_response(response)
        if "stop" in data:
            return "", "stop-ltl", []
        if max_intents is not None and len(data['labels']) > max_intents:
            raise ValueError(f'This paired batch permits at most {max_intents} intents/Gen labels; submit a smaller complete batch without weakening its selected intents.')
        return "\n".join([render_model_ut(design or load_design(), data),
                         render_program(design or load_design(), data, time_limit)]), "ltl-source", []
    except OutputFormatError as error:
        return "", "ltl-source", [{'kind': 'response-envelope', 'message': str(error)}]
    except (ValueError, KeyError, TypeError) as error:
        return "", "ltl-source", [str(error)]


def backend_errors(code: str) -> list[str]:
    # A useful regression guard, not an execution security boundary.
    if "me.jiuyang.stdlib" in code or re.search(r"\bHaven\w*UT\b", code):
        return ["the active experiment must not depend on stdlib or archived benchmark UTs"]
    return []


def harness(generated: Path, out_dir: Path, eda_shell: Path, compile_only: bool = False,
            *, resume=False, time_limit="120s", replay_config=None) -> tuple[dict, str]:
    env = {key: value for key, value in os.environ.items() if key not in (
        "RVPROBE_LLM_API_KEY", "RVPROBE_LLM_BASE_URL", "OPENAI_API_KEY", "OPENAI_BASE_URL")}
    env["ZAOZI_EDA_SHELL"] = str(eda_shell.resolve())
    source = generated.parent if generated.is_file() and (generated.parent / "DesignBinding.scala").exists() else generated
    command = [
        "nix", "develop", ".", "-c", "python3", "experiments/ut_harness.py",
        str(source.resolve()), "--out", str(out_dir.resolve()),
        "--jg-time-limit", time_limit,
    ]
    if compile_only:
        command.append("--compile-only")
    if replay_config is not None:
        command += ["--replay-config", str(replay_config)]
    if resume:
        command.append("--resume")
    process = run_process(command, cwd=ZAOZI, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          text=True, timeout=21600)
    for line in reversed(process.stdout.strip().splitlines()):
        if line.lstrip().startswith("{"):
            try:
                report = json.loads(line)
                return report, process.stdout + process.stderr
            except json.JSONDecodeError:
                pass
    return {"phase": "harness", "ok": False, "detail": (process.stdout + process.stderr)[-2000:]}, process.stdout + process.stderr


def feedback(report: dict) -> object:
    # Zaozi reports the failing member and API hint at the source location.
    # Forward evidence unchanged; do not guess repairs from a receiver type.
    return report.get("errors") or report.get("detail") or report


def goal_shortfall_feedback(report: dict, labels: list[str]) -> dict | None:
    """Turn a valid partial solve into bounded, goal-local model feedback.

    The trusted solver report remains the authority.  This function neither
    rewrites an intent nor treats solver failure as proof that the intent is
    impossible; it only identifies expressions for which no usable witness was
    produced.  Unknown execution errors stay outside the model-repair path.
    """
    if report.get('phase') != 'solve' or not report.get('ok'):
        return None
    result = report.get('result')
    if not isinstance(result, dict) or result.get('status') not in ('partial', 'no-witness'):
        return None
    goals = result.get('goals')
    if not isinstance(goals, list) or not goals:
        return None
    expected = set(labels)
    reported = [goal.get('label') for goal in goals if isinstance(goal, dict)]
    if (len(expected) != len(labels) or len(reported) != len(goals) or
            len(reported) != len(set(reported)) or set(reported) != expected):
        return None
    unresolved = [goal for goal in goals if goal.get('status') != 'generated']
    if not unresolved:
        return None
    errors = []
    for goal in unresolved:
        status = goal.get('status')
        failure_kind = goal.get('failureKind')
        if status not in ('infeasible', 'unknown', 'error'):
            return None
        # Other error kinds were already classified by the trusted harness.
        # Only a goal-local property compilation timeout reaches this path.
        if status == 'error' and failure_kind != 'property_compile_timeout':
            return None
        if status == 'infeasible':
            message = ('No witness exists for this goal as written under the fixed DUT, reset and environment. '
                'Preserve its label, output-level verification intent and checks. Repair the finite expression by '
                'correcting missing protocol setup, handshake, required history or temporal ordering. Do not replace '
                'it with input-only activity, weaken an output check, add an assumption or invent a timing bound.')
        elif status == 'unknown':
            message = ('The solver did not establish a witness for this goal within the fixed limit. Preserve its '
                'label, output-level verification intent and checks. Simplify only unnecessary temporal structure or '
                'make justified finite setup/history explicit; do not weaken the goal or add assumptions.')
        else:
            message = ('The backend could not compile this goal within its fixed resource limit. Preserve its label, '
                'output-level verification intent and checks. Reduce expression complexity while retaining the same '
                'finite protocol setup and observation; do not weaken the goal or add assumptions.')
        errors.append({'file':'model.ltl','goal':goal['label'],
            'code':'jg_goal_' + ('compile_timeout' if status == 'error' else status),
            'status':status,'failureKind':failure_kind,'message':message,
            **({'backend_detail':goal['detail']} if isinstance(goal.get('detail'), str) and goal['detail'] else {})})
    return {'phase':'solve','ok':False,'kind':'model_goal_shortfall','result':result,'errors':errors}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--modinfo", type=Path, required=True, help="baseline URG modinfo.txt")
    parser.add_argument("--out", type=Path, required=True, help="durable output directory")
    parser.add_argument("--design", type=Path, default=DEFAULT_DESIGN, help="versioned RTL/IO/clock/reset/codec manifest")
    parser.add_argument("--replay-config", type=Path, help="trusted reset prefix shared with cycle replay")
    parser.add_argument("--rtl", type=Path, help="replace the single RTL source in BOTH prompt and solver")
    parser.add_argument("--module", help="coverage module (defaults to the design top)")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--temperature", type=float, default=0.3)
    parser.add_argument("--attempts", type=int, default=3)
    parser.add_argument('--dialogue-policy', choices=('staged','incremental'), default='staged')
    parser.add_argument('--feedback-mode',choices=('full','no_diagnostics','no_coverage'),default='full')
    parser.add_argument('--fixed-opportunities',action='store_true')
    parser.add_argument("--timeout", type=int, default=600, help="total wall-clock limit per LLM HTTP request in seconds")
    from rvprobe_model_options import token_limit
    parser.add_argument('--max-tokens',type=token_limit,help='total generation limit including reasoning; provider default if omitted')
    parser.add_argument('--reasoning-effort',choices=REASONING_EFFORTS,default=DEFAULT_REASONING_EFFORT,
                        help='RVProbe DeepSeek reasoning effort (default: max)')
    parser.add_argument('--evidence-steps',type=int,
                        help='maximum retrieval requests before the final LTL request')
    parser.add_argument('--evidence-tools',type=int,
                        help='maximum executed read-only tool entries per generation dialogue')
    parser.add_argument('--retrieval-max-tokens',type=token_limit,
                        help='token limit for evidence/tool requests; final LTL request uses --max-tokens')
    parser.add_argument('--retrieval-reasoning-effort',choices=REASONING_EFFORTS,
                        help='reasoning effort for evidence/tool requests; final LTL request uses --reasoning-effort')
    parser.add_argument("--jg-time-limit", default="120s", help="JasperGold limit per Gen goal")
    parser.add_argument("--eda-shell", type=Path, default=DEFAULT_EDA_SHELL)
    parser.add_argument("--env-file", type=Path, help="optional provider KEY=VALUE file for live inference")
    parser.add_argument(
        "--response-file", type=Path,
        help="skip the LLM call and run a saved LTL response",
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
    parser.add_argument("--skill-snapshot", type=Path, help="fixed framework skill shared across this experiment's rounds")
    parser.add_argument("--history-file", type=Path, help="only this run's accepted model UTs, not chat or historical benchmark answers")
    parser.add_argument("--resume", action="store_true", help="resume an interrupted run with identical inputs")
    parser.add_argument("--request-retries", type=int, default=3, help="bounded retries for explicit transient HTTP errors before any completed dialogue step; ambiguous transport failures are not resent")
    parser.add_argument("--sequences-per-intent", type=int, default=1, help="downstream replay sampling budget, recorded in the prompt")
    args = parser.parse_args(argv)
    return execute_generation(args)


def request_model(args, prompt, directory, records):
    skill = getattr(args, "rvprobe_skill_snapshot", None)
    context = getattr(args, 'task_context', None)
    if context is not None: save(directory/'task-context.json',context.record())
    if list(directory.glob("incomplete-response-*.json")):
        raise ProviderFailure("saved incomplete provider response; automatic resume regeneration is disabled; use a new run",
                              'provider_incomplete_response')
    existing = list(directory.glob("request-*.json"))
    if existing:
        # A process may have died after submitting a billable request, or after
        # receiving it but before persisting response.txt. Never infer that it
        # is safe to send the same task again from a stale status/error alone.
        raise ProviderFailure('saved provider request without a reusable response; automatic resume regeneration is disabled; '
                              'usage may be unknown; use a new run', 'provider_resume_without_response')
    for number in range(len(existing) + 1, args.request_retries + 1):
        try:
            with records.phase("model-dialogue" if skill or context is not None else "model-request", attempt=directory.name, request=number,
                               requested_model=args.model) as event:
                save(directory / f"request-{number}.json", event)
                if skill or context is not None:
                    raw, info = invoke_with_skill(prompt, args.model, args.temperature, args.timeout,
                                                  send_completion, records, directory, number, skill, context,
                                                  max_tokens=getattr(args,'max_tokens',None),
                                                  reasoning_effort=getattr(args,'reasoning_effort',DEFAULT_REASONING_EFFORT),
                                                  dialogue_policy=getattr(args,'dialogue_policy','staged'),
                                                  evidence_steps=getattr(args,'evidence_steps',None),
                                                  evidence_tools=getattr(args,'evidence_tools',None),
                                                  retrieval_max_tokens=getattr(args,'retrieval_max_tokens',None),
                                                  retrieval_reasoning_effort=getattr(args,'retrieval_reasoning_effort',None))
                else:
                    raw, info = invoke(prompt, args.model, args.temperature, args.timeout)
                event.update(info)
                if not isinstance(raw, str) or not raw.strip() or info.get("response_status") in ("truncated", "filtered"):
                    save(directory / f"incomplete-response-{number}.json", {
                        **info, "content": raw if isinstance(raw, str) else None,
                        "policy": "stop-without-automatic-regeneration-v1"})
                    raise incomplete_response(info, "incomplete provider response: " + str(info.get("response_status", "empty")) +
                                              "; cost preserved; not a compiler error; no automatic regeneration")
            save(directory / f"request-{number}.json", event)
            return raw, info
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            # No HTTP response means delivery/billing is ambiguous. Also do
            # not restart a multi-call dialogue after earlier paid steps.
            transient = isinstance(error, urllib.error.HTTPError) and error.code in (408, 429, 500, 502, 503, 504)
            prior_steps = bool(list(directory.glob('dialogue-*.json')))
            event.update(failure_kind='provider_http_error' if isinstance(error, urllib.error.HTTPError)
                         else 'provider_transport_unknown', model_repair_allowed=False)
            save(directory / f"request-{number}.json", event)
            if not transient or prior_steps or number == args.request_retries:
                raise ProviderFailure(f"provider request failed after {number} attempt(s): {type(error).__name__}; "
                                      'cost preserved; no automatic resend', event['failure_kind']) from error
            time.sleep(min(2 ** (number - 1), 5))
        except (ValueError, RuntimeError, KeyError, TypeError) as error:
            if getattr(error, 'failure_kind', None):
                event.update(failure_kind=error.failure_kind, model_repair_allowed=False)
            save(directory / f"request-{number}.json", event)
            raise
    raise RuntimeError("provider retry budget exhausted; no unrecorded extra request was made")


def execute_generation(args):
    began, started = time.monotonic(), utc()
    args.out = args.out.resolve()
    args.out.mkdir(parents=True, exist_ok=args.resume)
    records = Records(args.out)
    summary = {"status": "failed", "contract": CONTRACT, "history": []}
    comparison = {}
    resume_rejected = False
    best_partial = None
    try:
        with records.phase("generation-session") as session_event:
            args.rvprobe_skill_snapshot = load_snapshot(args.skill_snapshot) if getattr(args, 'skill_snapshot', None) else snapshot()
            design = load_design(args.design)
            if args.rtl:
                design = design.with_rtl(args.rtl)
            args.module = args.module or design.top
            if min(args.attempts, args.timeout, args.request_retries) < 1 or args.rag_top_k < 0:
                raise ValueError("budgets must be positive and rag-top-k nonnegative")
            if (args.prepare_only or args.compile_only) and not args.response_file:
                raise ValueError("prepare-only/compile-only require a saved response")
            if args.env_file and args.response_file:
                raise ValueError("offline execution must not load provider credentials")
            from feedback_ablation import redact_feedback, NoCoverageContext, GENERIC_ERROR, remove_coverage_instructions
            feedback_mode=getattr(args,'feedback_mode','full')
            uncovered = [] if feedback_mode=='no_coverage' else residual(args.modinfo, args.module)
            coverage_feedback = json.loads(args.feedback_file.read_text()) if args.feedback_file else None
            coverage_feedback=redact_feedback(coverage_feedback or {},feedback_mode)
            accepted_history = json.loads(args.history_file.read_text()) if getattr(args, 'history_file', None) else None
            if feedback_mode!='no_coverage' and not getattr(args,'fixed_opportunities',False) and not uncovered and not (isinstance(coverage_feedback, dict) and coverage_feedback.get("gaps")):
                raise ValueError("no uncovered executable lines or typed coverage gaps in the requested module")
            hits, version = [], None
            if args.rag == "local" and args.rag_top_k:
                version, documents = load_corpus(args.rag_corpus)
                hits = retrieve_diverse(retrieval_queries(), documents, args.rag_top_k)
            supplements = supplemental_references(hits, args.rvprobe_skill_snapshot)
            task_context = TaskContext(design, coverage_feedback or {'uncovered_lines':uncovered}, accepted_history,
                                       supplements, dialogue_policy='compact', evidence_steps=args.evidence_steps,
                                       evidence_tools=args.evidence_tools)
            if feedback_mode=='no_coverage':task_context=NoCoverageContext(task_context)
            args.task_context = task_context
            rag = {"mode": args.rag, "corpusVersion": version, "scope": "framework-only",
                   "queries": retrieval_queries(), "retrieved": [hit.json() for hit in hits],
                   "delivery":"ltl-api-on-demand-v1",
                   "core_covered": [h.id for h in hits if h not in supplements],
                   "supplements": [h.id for h in supplements]}
            rag_catalog = framework_catalog(supplements)
            comparison = {"generation_contract": CONTRACT, "model": "saved-response" if args.response_file else args.model,
                "temperature": args.temperature, "rag": rag, "design": design.record(),
                "attempt_budget": args.attempts, "request_retry_budget": args.request_retries,
                "mode": "prompt" if args.prompt_only else "prepare" if args.prepare_only else "compile" if args.compile_only else "solve",
                "request_timeout": args.timeout, "jg_time_limit": args.jg_time_limit,
                "max_tokens": getattr(args,'max_tokens',None),
                "dialogue_policy": getattr(args,'dialogue_policy','staged'),
                "feedback_mode": feedback_mode,
                "reasoning_effort": getattr(args,'reasoning_effort',DEFAULT_REASONING_EFFORT),
                "evidence_steps": args.evidence_steps,
                "evidence_tools": args.evidence_tools,
                "retrieval_max_tokens": args.retrieval_max_tokens,
                "retrieval_reasoning_effort": args.retrieval_reasoning_effort,
                "sequences_per_intent": args.sequences_per_intent,
                "task_sha256": hashlib.sha256(args.modinfo.read_bytes()).hexdigest(),
                "feedback": coverage_feedback, "rtl_context_policy": RTL_CONTEXT_POLICY,
                "task_access":args.task_context.record(),
                "framework_context_policy": "predefined-ltl-helpers-v4",
                "incomplete_response_policy": "stop-without-automatic-regeneration-v1",
                "skill_protocol": SKILL_PROTOCOL,
                "source_sha256": framework_hashes(ZAOZI),
                "skill": {k: v for k, v in args.rvprobe_skill_snapshot.items() if k != "content"},
                "saved_response_sha256": hashlib.sha256(args.response_file.read_bytes()).hexdigest() if args.response_file else None}
            manifest = args.out / "manifest.json"
            identity = fingerprint(comparison)
            if manifest.exists():
                previous_manifest = json.loads(manifest.read_text())
                if not args.resume or previous_manifest["fingerprint"] != identity:
                    resume_rejected = True
                    raise ValueError("resume input/config/framework fingerprint changed")
                started = previous_manifest["started_utc"]
            else:
                save(manifest, {**comparison, "fingerprint": identity, "started_utc": started})
            save(args.out / "design.json", design.record())
            save(args.out / "residual.json", uncovered)
            save(args.out / "rag.json", rag)
            save(args.out / "rtl-context.json", args.task_context.record())
            with records.phase('framework-preflight'):
                try:
                    save(args.out/'runtime-inputs.json', runtime_hashes(ZAOZI))
                except (OSError, RuntimeError):
                    summary.update(failure_kind='framework_infrastructure_failure',
                                   model_repair_allowed=False)
                    raise
            if args.env_file:
                load_env_file(args.env_file)
            summary.update(model=comparison["model"], temperature=args.temperature, backend="jaspergold",
                           design=design.record(), rag=rag, residual=len(uncovered))
            errors, previous = None, None
            required_repair_labels = None
            attempt_budget = 1 if args.response_file else args.attempts
            for attempt in range(1, attempt_budget + 1):
                if errors is not None and feedback_mode=='no_diagnostics':errors=GENERIC_ERROR
                args.task_context = RepairContext(task_context, errors) if errors is not None else task_context
                directory = args.out / f"attempt-{attempt}"
                directory.mkdir(exist_ok=args.resume)
                save(directory/'initial-evidence.json',args.task_context.initial_evidence())
                prompt = build_prompt(uncovered, design.sources[0], args.jg_time_limit, rag_catalog,
                                      errors, previous, design, coverage_feedback, args.sequences_per_intent,
                                      skill_context=True)
                if errors is None and feedback_mode=='no_coverage':prompt=remove_coverage_instructions(prompt)
                prompt_path = directory / "prompt.txt"
                if prompt_path.exists() and prompt_path.read_text() != prompt:
                    raise ValueError("resume prompt changed")
                prompt_path.write_text(prompt)
                save(directory / "prompt.json", {"sha256": hashlib.sha256(prompt.encode()).hexdigest(),
                    "characters": len(prompt), "sections": prompt_sections(prompt),
                    "framework_context_policy": "predefined-ltl-helpers-v4",
                    "repair": errors is not None, "ragIds": [h.id for h in hits]})
                if args.prompt_only:
                    summary.update(status="prompt-only", attempts=0)
                    break
                response_path = directory / "response.txt"
                if response_path.exists():
                    raw = response_path.read_bytes().decode('utf-8')
                elif args.response_file:
                    raw = args.response_file.read_bytes().decode('utf-8')
                    response_path.write_bytes(raw.encode())
                else:
                    print(f"attempt {attempt}: requesting {args.model}", file=sys.stderr, flush=True)
                    raw, provider = request_model(args, prompt, directory, records)
                    response_path.write_bytes(raw.encode())
                    save(directory / "provider.json", provider)
                # Raw provider output stays immutable in response.txt.  The
                # LTL boundary may remove one exact fence and canonicalize the
                # audited whitespace spelling of a curried bounded delay.
                response = raw
                code, response_format, checks = materialize_response(response, args.jg_time_limit, design,
                    max_intents=(coverage_feedback or {}).get('intent_batch_limit'))
                if not checks and required_repair_labels is not None:
                    if set(parse_response(response).get('labels', [])) != required_repair_labels:
                        checks = ['Solver-guided repair must preserve every original Gen label; '
                                  'STOP, removing goals and adding replacement goals are not permitted. '
                                  'Required labels: ' + ', '.join(sorted(required_repair_labels))]
                if not checks:
                    _, normalization = normalize(raw)
                    audit_path = directory / 'response-normalization.json'
                    if audit_path.exists() and json.loads(audit_path.read_text()) != normalization:
                        raise ValueError('saved response normalization changed')
                    save(audit_path, normalization)
                log = ""
                old_report = directory / "harness.json"
                cached = json.loads(old_report.read_text()) if old_report.exists() and args.resume else None
                if cached and (cached.get("ok") or model_repair_allowed(cached)):
                    if (directory / "sources").exists():
                        check_saved_sources(directory / "sources", design, parse_response(response))
                    report = cached
                elif checks:
                    report = {"phase": "response-check", "ok": False, "errors": checks}
                elif "stop" in parse_response(response):
                    data = parse_response(response)
                    report = {"phase": "stop", "ok": True, "result": {"status": "stopped", "utCount": 0,
                              "goals": [], "stopReason": "model supplied no new LTL target", "proofObligations": []}}
                elif backend_errors(code):
                    report = {"phase": "backend-check", "ok": False, "errors": backend_errors(code)}
                else:
                    sources = directory / "sources"
                    if not sources.exists():
                        write_sources(sources, design, parse_response(response), args.jg_time_limit)
                    if args.prepare_only:
                        report = {"phase": "prepare", "ok": True, "sources": str(sources)}
                    else:
                        report, log = harness(sources, directory / "solve", args.eda_shell, args.compile_only,
                                              resume=args.resume, time_limit=args.jg_time_limit, replay_config=args.replay_config)
                save(old_report, report)
                journal_repairs(args.out)
                if log:
                    (directory / "harness.log").write_text(log)
                provider = json.loads((directory / "provider.json").read_text()) if (directory / "provider.json").exists() else {}
                summary["history"].append({"attempt": attempt, "phase": report["phase"], "ok": report["ok"],
                    "responseFormat": response_format, "tokens": provider.get("usage", {}).get("total_tokens")})
                summary["attempts"] = attempt
                if report["ok"]:
                    result = report.get("result", {})
                    shortfall = goal_shortfall_feedback(report, parse_response(response).get('labels', []))
                    if shortfall is not None:
                        generated = sum(goal.get('status') == 'generated' for goal in result.get('goals', []))
                        if best_partial is None or generated > best_partial['generated_goals']:
                            best_partial = {'result':result, 'sources':str(directory / 'sources'),
                                'attempt':attempt, 'generated_goals':generated}
                        if attempt < attempt_budget:
                            save(directory / 'goal-shortfall-feedback.json', shortfall)
                            summary['history'][-1].update(repairScheduled=True,
                                unresolvedGoals=[error['goal'] for error in shortfall['errors']])
                            required_repair_labels = set(parse_response(response)['labels'])
                            errors, previous = shortfall['errors'], response
                            continue
                        chosen = best_partial
                        summary.update(status=chosen['result'].get('status', report['phase']),
                            result=chosen['result'], sources=chosen['sources'])
                        if chosen['attempt'] != attempt:
                            summary['partial_fallback'] = {'selected_attempt':chosen['attempt'],
                                'failed_repair_attempt':attempt,
                                'reason':'earlier partial generated more goals'}
                        break
                    summary.update(status=result.get("status", report["phase"]), result=result, sources=str(directory / "sources"))
                    break
                summary['last_error'] = feedback(report)
                if not model_repair_allowed(report):
                    if best_partial is not None:
                        summary.update(status=best_partial['result'].get('status', 'partial'),
                            result=best_partial['result'], sources=best_partial['sources'],
                            partial_fallback={'selected_attempt':best_partial['attempt'],
                                'failed_repair_attempt':attempt,
                                'reason':f"non-repairable follow-up failure: {report['phase']}"})
                        break
                    summary['failure_kind'] = 'framework_infrastructure_failure'
                    summary['model_repair_allowed'] = False
                    raise RuntimeError(f"infrastructure failure: {report['phase']}; resume the saved response without another model call")
                if report.get('kind') == 'model_goal_unsupported':
                    required_repair_labels = set(parse_response(response)['labels'])
                errors, previous = feedback(report), response
            if summary['status'] == 'failed' and best_partial is not None:
                summary.update(status=best_partial['result'].get('status', 'partial'),
                    result=best_partial['result'], sources=best_partial['sources'],
                    partial_fallback={'selected_attempt':best_partial['attempt'],
                        'failed_repair_attempt':summary.get('attempts'),
                        'reason':'model repair budget exhausted'})
            if summary["status"] == "failed":
                session_event["status"] = "failed"
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError, KeyError, TypeError, KeyboardInterrupt) as error:
        if best_partial is not None and not isinstance(error, KeyboardInterrupt):
            summary.update(status=best_partial['result'].get('status', 'partial'),
                result=best_partial['result'], sources=best_partial['sources'],
                partial_fallback={'selected_attempt':best_partial['attempt'],
                    'failed_repair_attempt':summary.get('attempts'),
                    'reason':f'follow-up repair failed: {type(error).__name__}'},
                partial_repair_error=str(error))
        else:
            summary.update(status="failed", error=str(error))
            if getattr(error, 'failure_kind', None):
                summary.update(failure_kind=error.failure_kind, model_repair_allowed=False)
    finally:
        if not resume_rejected:
            finish(args.out, summary, started, began, **comparison)
    print(json.dumps(summary))
    return int(summary["status"] == "failed")


if __name__ == "__main__":
    sys.exit(main())
