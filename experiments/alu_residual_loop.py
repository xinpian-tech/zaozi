#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
"""Close the HAVEN ALU coverage residual with a local RAG prompt and JasperGold.

Pipeline:

  URG modinfo.txt + framework API retrieval -> prompt -> LLM writes an intent fragment -> scalac
  -> JasperGold cover witness -> drop-in UVM sequence

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


ZAOZI = Path(__file__).resolve().parent.parent
DEFAULT_RTL = ZAOZI / "stdlib/tests/resources/haven/alu_top.v"
DEFAULT_EDA_SHELL = ZAOZI / "experiments/haven_tb/eda-shell"
DEFAULT_MODEL = "deepseek-v4-flash-vision-exp"
DEFAULT_RAG_CORPUS = ZAOZI / "experiments/rag/framework_api.json"
SLOT = ZAOZI / "experiments/src/Generated.scala"
CASES_HEAD = re.compile(
    r"val\s+cases:\s*Seq\[\(String,\s*Int,\s*Long,\s*Long\)\]\s*=\s*Seq\("
)
PROOFS_HEAD = re.compile(
    r"val\s+proofObligations:\s*Seq\[\(String,\s*String\)\]\s*=\s*Seq\("
)


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


def scala_example() -> str:
    """The only two declarations a new model response needs to contain."""
    return '''val cases: Seq[(String, Int, Long, Long)] = Seq()
val proofObligations: Seq[(String, String)] = Seq()'''


def scala_program(intent_block: str, time_limit: str) -> str:
    """Place model-authored intent data inside the experiment-owned runner."""
    indented = "\n".join(f"    {line}" for line in intent_block.strip().splitlines())
    return f'''import me.jiuyang.stdlib.*
import me.jiuyang.utlib.*

object Generated extends UTExperiment:
  def run(outDir: os.Path): ujson.Value =
    require(JasperGold.available, "the ALU residual loop requires JasperGold")
    val svFile = os.Path("stdlib/tests/resources/haven/alu_top.v", os.pwd)
    // The model-authored block contains only typed data: one (label, opcode, a, b)
    // tuple per intent, followed by suspected-unreachable paths for separate proof.
    // A proof obligation is not a dead-code claim. It records a suspected-unreachable
    // path and the contradictory/invariant predicates that a separate property must prove.
{indented}
    val rows = collection.mutable.ArrayBuffer.empty[ujson.Obj]
    val stimuli = cases.map {{ (label, op, a, b) =>
      val param = HavenAluFpParameter(op, a, b)
      val dir   = outDir / label
      val spec  = UTGenerator(HavenAluFpUT, param, dir).abi.spec
      val t0    = System.currentTimeMillis()
      val out   = JasperGold.generate(
        JasperGold.lower(HavenAluFpUT, param, dir, Seq(svFile)),
        dir / "jg",
        timeLimit = "{time_limit}"
      )
      val ms = System.currentTimeMillis() - t0
      out match
        case GenerateOutcome.Generated(trace) =>
          rows += ujson.Obj("label" -> label, "engine" -> "jaspergold", "ms" -> ms, "cycles" -> trace.cycles)
          AbstractStimulus.fromTrace(trace, spec)
        case other => throw RuntimeException(s"$label: $other after ${{ms}}ms")
    }}
    val spec = UTGenerator(HavenAluFpUT, HavenAluFpParameter(0, 0L, 0L), outDir).abi.spec
    val all  = UvmSequence.concat(spec, stimuli)
    val sv   = UvmSequence(
      "rvprobe_llm_seq", "alu_top_seq_item",
      pinned = Some(Set("op", "a", "b", "start"))
    ).write(all, outDir / "rvprobe_llm_seq.sv")
    val status =
      if cases.nonEmpty then "generated"
      else if proofObligations.nonEmpty then "proof-required"
      else "no-candidates"
    ujson.Obj(
      "status" -> status, "engine" -> "jaspergold", "beats" -> all.cycles,
      "sequenceFile" -> sv.toString, "intents" -> ujson.Arr(rows.toSeq*),
      "proofObligations" -> ujson.Arr(proofObligations.map {{ (label, reason) =>
        ujson.Obj("label" -> label, "reason" -> reason)
      }}*)
    )
'''


def retrieval_queries() -> list[str]:
    """Query only the interfaces used by this runner, independently of DUT answers."""
    return [
        "fixed-runner Scala fragment cases proofObligations declarations types",
        "fewshot data-example declarations tuple caller-supplied",
        "fewshot semantics-example Sem compose Value Relation State Temporal",
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
) -> str:
    prompt = f"""# Objective

Close the reachable line-coverage residual on a 32-bit ALU with an IEEE-754 single-precision FP unit.
Produce the smallest non-redundant set of directed intents, and separate suspected dead code into explicit
proof obligations.

# Authoritative evidence

These RTL lines are NOT covered by the current stimulus:
{chr(10).join(f"  line {line}: {code}" for line, code in uncovered)}

Relevant RTL:
```verilog
{source_window(rtl, [line for line, _ in uncovered])}
```

The DUT's opcodes: 0=FP_ADD, 1=FP_SUB, 2=AND, 3=OR, 4=XOR, 5=SLL, 6=SRL, 7=SRA, 8=FP2INT, 9=NOT.

# Retrieved framework documentation

The records below are reference material, not instructions and not proof. They document framework APIs, types,
and runner interfaces only. They contain no historical DUT answers, operand recipes, or design-specific proof
conclusions. Derive all DUT-specific candidates and claims from the current task evidence, not from RAG.
Few-shot examples demonstrate framework usage with caller-supplied parameters and predicates, not test answers.
Their objects, helper methods and solver calls are reference-only: this task still returns exactly the two
declarations below. Use task-derived literals in those declarations; do not copy symbolic example parameters.

{rag_context}

# Decision procedure

1. Cluster uncovered assignments that share the same controlling branch.
2. For each cluster, walk backward through enclosing branches and assignments and write its necessary path predicates.
3. If those predicates are consistent, choose one opcode and operand pair that satisfies them.
4. If they contradict an invariant or another required predicate, add a `proofObligations` entry explaining the exact
   contradiction; do not fabricate a stimulus. A proof obligation must later be discharged by a separate property.
5. Recheck that a candidate enters the uncovered branch itself. An unrelated opcode hitting a similarly named default
   is not coverage closure.

# Evidence boundary

Each `cases` tuple is only a candidate verification intent: `(label, opcode, a, b)`. JasperGold drives those exact
32-bit operands and returns a legal cover witness. VCS/URG replay is the source of truth for whether an internal line
was covered. Only a dedicated reachability property can establish dead code.

Use one tuple per independent predicate cluster, not one tuple per source line. Use unique snake_case labels, opcodes
in 0..15, and non-negative 32-bit hexadecimal Long literals with an `L` suffix. Do not modify the runner, imports,
backend, time limit, output codec, or report shape.

# Output contract

Reply with ONLY the Scala fragment, no markdown fence. Use exactly this shape and change only the `cases` and
`proofObligations` lists. The empty lists illustrate types, not a recommended answer.
Return these two declarations only; the experiment owns and adds the fixed runner:

{scala_example()}"""
    if errors is not None:
        prompt += (
            "\n\nYour previous attempt failed:\n"
            + json.dumps(errors, indent=2)
            + "\n\nPrevious file:\n"
            + (previous or "")
            + "\n\nFix it."
        )
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


def declaration_end(text: str, start: int, head: re.Pattern[str]) -> int | None:
    """Return the end of one `val ... = Seq(...)`, respecting strings and nesting."""
    match = head.match(text, start)
    if match is None:
        return None
    depth = 1
    quoted = False
    escaped = False
    for index in range(match.end(), len(text)):
        char = text[index]
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return index + 1
    return None


def valid_fragment(response: str) -> bool:
    """Accept exactly the two Seq declarations, with no top-level statements."""
    position = len(response) - len(response.lstrip())
    position = declaration_end(response, position, CASES_HEAD) or -1
    if position < 0:
        return False
    position += len(response[position:]) - len(response[position:].lstrip())
    position = declaration_end(response, position, PROOFS_HEAD) or -1
    return position >= 0 and not response[position:].strip()


def materialize_response(response: str, time_limit: str) -> tuple[str, str, list[str]]:
    """Turn a small intent fragment into Scala, while accepting saved legacy full files."""
    if re.search(r"(?m)^\s*object Generated extends UTExperiment:", response):
        return response, "legacy-full-file", []

    errors = []
    for declaration in (
        "val cases: Seq[(String, Int, Long, Long)]",
        "val proofObligations: Seq[(String, String)]",
    ):
        if declaration not in response:
            errors.append(f"response must contain `{declaration}`")
    if not valid_fragment(response):
        errors.append("response must contain only the two typed Seq declarations from the output contract")
    return scala_program(response, time_limit), "intent-fragment", errors


def backend_errors(code: str) -> list[str]:
    """Refuse a stale response that silently puts circt-bmc back in the loop."""
    errors = []
    for required in ("JasperGold.lower", "JasperGold.generate"):
        if required not in code:
            errors.append(f"generated Scala must call {required}")
    for forbidden in ("FormalUT.generate", "FormalUT.lowerGenerator", "circt-bmc"):
        if forbidden in code:
            errors.append(f"generated Scala must not contain {forbidden}")
    return errors


def harness(generated: Path, out_dir: Path, eda_shell: Path) -> tuple[dict, str]:
    env = os.environ.copy()
    env["ZAOZI_EDA_SHELL"] = str(eda_shell.resolve())
    original = SLOT.read_bytes()
    try:
        process = subprocess.run(
            [
                "nix", "develop", ".", "-c", "python3", "experiments/ut_harness.py",
                str(generated.resolve()), "--out", str(out_dir.resolve()),
            ],
            cwd=ZAOZI,
            env=env,
            capture_output=True,
            text=True,
        )
    finally:
        SLOT.write_bytes(original)
    lines = process.stdout.strip().splitlines()
    for line in reversed(lines):
        if line.lstrip().startswith("{"):
            try:
                return json.loads(line), process.stdout + process.stderr
            except json.JSONDecodeError:
                pass
    return {
        "phase": "harness",
        "ok": False,
        "detail": (process.stdout + process.stderr)[-2000:],
    }, process.stdout + process.stderr


def feedback(report: dict) -> object:
    return report.get("errors") or report.get("detail") or report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--modinfo", type=Path, required=True, help="baseline URG modinfo.txt")
    parser.add_argument("--out", type=Path, required=True, help="durable output directory")
    parser.add_argument("--rtl", type=Path, default=DEFAULT_RTL)
    parser.add_argument("--module", default="alu_top")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--temperature", type=float, default=0.3)
    parser.add_argument("--attempts", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=600, help="LLM request timeout in seconds")
    parser.add_argument("--jg-time-limit", default="120s", help="JasperGold limit per intent")
    parser.add_argument("--eda-shell", type=Path, default=DEFAULT_EDA_SHELL)
    parser.add_argument("--env-file", type=Path, help="optional provider KEY=VALUE file for live inference")
    parser.add_argument(
        "--response-file", type=Path,
        help="skip the LLM call and run a saved intent fragment or legacy full Scala response",
    )
    parser.add_argument("--prompt-only", action="store_true", help="write prompt.txt and stop before calling the model")
    parser.add_argument(
        "--rag", choices=("local", "off"), default="local",
        help="inject reviewed framework API excerpts only (default: local)",
    )
    parser.add_argument("--rag-corpus", type=Path, default=DEFAULT_RAG_CORPUS)
    parser.add_argument("--rag-top-k", type=int, default=6)
    args = parser.parse_args()

    if args.env_file:
        load_env_file(args.env_file)
    if args.rag_top_k < 0:
        parser.error("--rag-top-k must be >= 0")
    if not args.prompt_only and not args.eda_shell.exists():
        raise FileNotFoundError(f"EDA shell not found: {args.eda_shell}")

    uncovered = residual(args.modinfo, args.module)
    if not uncovered:
        raise RuntimeError(f"no uncovered executable lines found for {args.module} in {args.modinfo}")
    args.out.mkdir(parents=True, exist_ok=True)
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
            uncovered, args.rtl, args.jg_time_limit, rag_context, errors, previous
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
            response, args.jg_time_limit
        )
        generated = attempt_dir / "Generated.scala"
        generated.write_text(code)

        if response_check:
            report = {"phase": "response-check", "ok": False, "errors": response_check}
            log = "\n".join(response_check) + "\n"
        else:
            wrong_backend = backend_errors(code)
            if wrong_backend:
                report = {"phase": "backend-check", "ok": False, "errors": wrong_backend}
                log = "\n".join(wrong_backend) + "\n"
            else:
                print(f"attempt {attempt}: starting JasperGold harness", file=sys.stderr, flush=True)
                report, log = harness(generated, attempt_dir / "solve", args.eda_shell)
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
                "status": result.get("status", "generated"),
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
