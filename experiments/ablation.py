#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
"""The 2x2 ablation: who supplies the operands, and when a mistake is caught.

Both axes are claims the harness makes, and each is worth isolating:

  form  = plan | goal
      `plan` has the model choose concrete operands and the solver merely schedule the
      handshake around them -- so the model must reason about IEEE-754 itself.  `goal` has
      the model name a destination over the DUT's outputs ("the overflow flag is set on
      FP_ADD") and the solver search the operand space.  This is "state the destination"
      against "compute the route".

  check = typed | untyped
      `typed` has the model write the Scala experiment, which scalac checks before anything
      runs, so a malformed intent comes back as a located compile error.  `untyped` has the
      model write JSON that a fixed driver (AblationDriver.scala) consumes, so the same
      mistake surfaces only as a runtime exception or a VCS error -- which is the shape of
      HAVEN's structural DSL, where the safety boundary is a template rather than a type.

Every cell is given the identical task -- close the coverage residual left by the bulk fill
on HAVEN's ALU -- and is measured the same way: attempts to a usable artifact, tokens, and
the coverage its sequence adds in HAVEN's own testbench under VCS/URG.

Usage: ablation.py --tb <testbench dir> --out <results dir> [--cells plan/typed,goal/typed,...]
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ZAOZI = Path(__file__).resolve().parent.parent
SCRATCH = Path("/tmp/claude-0/-root-yjh-workspace/491fc915-de96-4db8-85de-375362ef0be6/scratchpad")
RTL = ZAOZI / "stdlib/tests/resources/haven/alu_top.v"
SNPS_SHELL = Path("/root/yjh-workspace/rvprobe-workspace/artifact/haven-deepseek-2026-08-28/snps-shell")
MODEL = "deepseek-v4-flash-vision-exp"
DUT_MODULE = "alu_top"
CELLS = ["plan/typed", "goal/typed", "plan/untyped", "goal/untyped"]

OPCODES = "0=FP_ADD, 1=FP_SUB, 2=AND, 3=OR, 4=XOR, 5=SLL, 6=SRL, 7=SRA, 8=FP2INT, 9=NOT (10-15 reserved)"
FLAGS = "flags[3]=OVERFLOW, flags[2]=UNDERFLOW, flags[1]=ZERO, flags[0]=INVALID"


# ---- residual -------------------------------------------------------------------------------


def residual(modinfo: Path) -> list[tuple[int, str]]:
    """The uncovered RTL lines URG reports, as (line number, source text)."""
    return [
        (int(m.group(1)), m.group(2).strip())
        for m in re.finditer(r"^\s*(\d+)\s+0/\d+\s+(?:=+>)?\s*(.*)$", modinfo.read_text(), re.M)
    ]


def source_window(lines: list[int], radius: int = 6) -> str:
    src = RTL.read_text().splitlines()
    keep = {i for ln in lines for i in range(max(0, ln - radius), min(len(src), ln + radius))}
    chunks, prev = [], -2
    for i in sorted(keep):
        if i != prev + 1:
            chunks.append("...")
        chunks.append(f"{i+1:4d}| {src[i]}")
        prev = i
    return "\n".join(chunks)


# ---- the four prompts -----------------------------------------------------------------------

SCALA_PLAN = '''import me.jiuyang.stdlib.*
import me.jiuyang.utlib.*

object Generated extends UTExperiment:
  def run(outDir: os.Path): ujson.Value =
    val svFile = os.Path("%s")
    val ip     = SvImport.toHw(Seq(svFile), outDir / "imported")
    // (label, opcode, a, b) — each entry is one directed intent.
    val cases = Seq(
      ("nan_a", 0, 0x7fc00000L, 0x3f800000L)
    )
    val stimuli = cases.map { (label, op, a, b) =>
      val param  = HavenAluFpParameter(op, a, b)
      val model  = FormalUT.lowerGenerator(HavenAluFpUT, param, outDir / label)
      val merged = SvImport.mergeForBmc(model.hw, ip)
      FormalUT.generate(model.copy(hw = merged), bound = 2) match
        case GenerateOutcome.Generated(t) =>
          AbstractStimulus.fromTrace(t, UTGenerator(HavenAluFpUT, param, outDir / label).abi.spec)
        case other => throw RuntimeException(s"$label: $other")
    }
    val spec = UTGenerator(HavenAluFpUT, HavenAluFpParameter(0, 0L, 0L), outDir).abi.spec
    val all  = UvmSequence.concat(spec, stimuli)
    val sv   = UvmSequence("rvprobe_cell_seq", "alu_top_seq_item",
                 pinned = Some(Set("op", "a", "b", "start"))).write(all, outDir / "rvprobe_cell_seq.sv")
    ujson.Obj("status" -> "generated", "beats" -> all.cycles, "sequenceFile" -> sv.toString)
''' % RTL

SCALA_GOAL = '''import me.jiuyang.stdlib.*
import me.jiuyang.utlib.*

object Generated extends UTExperiment:
  def run(outDir: os.Path): ujson.Value =
    val svFile = os.Path("%s")
    val ip     = SvImport.toHw(Seq(svFile), outDir / "imported")
    // Each entry is one GOAL. You do not choose operands — the solver does.
    // flagsMask selects which flag bits you constrain; flagsValue gives their required values.
    // opIs names the opcode to launch. resultIs optionally pins the 32-bit result.
    val goals = Seq(
      HavenAluGoalParameter("overflow_add", flagsMask = 8, flagsValue = 8, opIs = Some(0))
    )
    val stimuli = goals.map { param =>
      val model  = FormalUT.lowerGenerator(HavenAluGoalUT, param, outDir / param.label)
      val merged = SvImport.mergeForBmc(model.hw, ip)
      FormalUT.generate(model.copy(hw = merged), bound = 12) match
        case GenerateOutcome.Generated(t) =>
          val st = AbstractStimulus.fromTrace(t, UTGenerator(HavenAluGoalUT, param, outDir / param.label).abi.spec)
          param.opIs.fold(st)(v =>
            AbstractStimulus(st.spec, st.beats.map(b => b.copy(values = b.values.updated("op", BigInt(v)))))
          )
        case other => throw RuntimeException(s"${param.label}: $other")
    }
    val spec = UTGenerator(HavenAluGoalUT, HavenAluGoalParameter("probe"), outDir).abi.spec
    val all  = UvmSequence.concat(spec, stimuli)
    val sv   = UvmSequence("rvprobe_cell_seq", "alu_top_seq_item",
                 pinned = Some(Set("op", "a", "b", "start"))).write(all, outDir / "rvprobe_cell_seq.sv")
    ujson.Obj("status" -> "generated", "beats" -> all.cycles, "sequenceFile" -> sv.toString)
''' % RTL

JSON_PLAN = '''{"form": "plan", "cases": [
  {"label": "nan_a", "op": 0, "a": "0x7fc00000", "b": "0x3f800000"}
]}'''

JSON_GOAL = '''{"form": "goal", "goals": [
  {"label": "overflow_add", "flagsMask": 8, "flagsValue": 8, "opIs": 0}
]}'''


def task_text(res: list[tuple[int, str]]) -> str:
    return f"""You are closing a coverage residual on a 32-bit ALU with an IEEE-754 single-precision FP unit.

These RTL lines are NOT covered by the current stimulus:
{chr(10).join(f"  line {ln}: {code}" for ln, code in res)}

Relevant RTL:
```verilog
{source_window([ln for ln, _ in res])}
```

Opcodes: {OPCODES}
Flag bits: {FLAGS}
"""


def build_prompt(cell: str, res, errors=None, previous=None) -> str:
    form, check = cell.split("/")
    body = task_text(res)
    if form == "plan":
        body += """
Each entry you write is one directed stimulus: an opcode and two 32-bit operand values. The DUT is
driven with exactly those operands. YOU must choose values that make the uncovered lines execute --
reason about the FP arithmetic: to hit a rounding path you need operands whose SUM requires rounding
with carry; to hit underflow you need a result too small to represent as a normal; and so on.
"""
    else:
        body += """
Each entry you write is one GOAL: a condition on the DUT's OUTPUTS that you want reached. You do NOT
choose operand values -- an SMT solver searches the 64-bit operand space for a pair that reaches your
goal, so state the destination, not the route. A goal names the opcode to launch (opIs), which flag
bits must hold (flagsMask selects the bits, flagsValue their values), and optionally an exact result.
A goal that cannot be reached comes back as Infeasible rather than a wrong answer.
"""
    if check == "typed":
        example = SCALA_PLAN if form == "plan" else SCALA_GOAL
        body += f"""
Reply with ONLY a Scala file, no markdown fence, exactly this shape (change only the list):

{example}"""
    else:
        example = JSON_PLAN if form == "plan" else JSON_GOAL
        body += f"""
Reply with ONLY a JSON object, no markdown fence, exactly this shape (change only the list):

{example}"""
    if errors:
        body += f"\n\nYour previous attempt failed:\n{errors}\n\nPrevious answer:\n{previous}\n\nFix it."
    return body


# ---- running one cell -----------------------------------------------------------------------


def run_typed(code: str, out_dir: Path) -> dict:
    """scalac checks the model's file before anything runs; a bad intent is a located compile error."""
    gen = out_dir / "Generated.scala"
    gen.write_text(code)
    proc = subprocess.run(
        ["nix", "develop", ".", "-c", "python3", "experiments/ut_harness.py", str(gen), "--out", str(out_dir)],
        cwd=ZAOZI, capture_output=True, text=True,
    )
    return harness_report(proc)


def run_untyped(code: str, out_dir: Path) -> dict:
    """The model's JSON goes straight to a fixed driver; mistakes surface at run time, not before."""
    (out_dir / "spec.json").write_text(code)
    try:
        json.loads(code)
    except ValueError as e:
        # Not a type error -- the driver would fail to parse it, which is exactly the late failure
        # this arm is meant to exhibit.  Report it the way the driver would.
        return {"phase": "run", "ok": False, "detail": f"spec.json is not valid JSON: {e}"}
    proc = subprocess.run(
        ["nix", "develop", ".", "-c", "python3", "experiments/ut_harness.py",
         str(ZAOZI / "experiments/AblationDriver.scala"), "--out", str(out_dir)],
        cwd=ZAOZI, capture_output=True, text=True,
    )
    return harness_report(proc)


def harness_report(proc) -> dict:
    for line in reversed(proc.stdout.strip().splitlines()):
        if line.strip().startswith("{"):
            try:
                return json.loads(line)
            except ValueError:
                continue
    return {"phase": "harness", "ok": False, "detail": proc.stdout[-1500:] + proc.stderr[-1500:]}


def feedback(report: dict) -> str:
    """What the model is shown after a failure -- and the difference between the arms.

    The typed arm hands back scalac's located diagnostics.  The untyped arm has no such thing to
    hand back: it can only report that something blew up while running, which is the whole point.
    """
    if report.get("errors"):
        return json.dumps(report["errors"], indent=2)
    return str(report.get("detail", report))[-1500:]


# ---- coverage -------------------------------------------------------------------------------


def measure(tb: Path, cell_seq: Path, work: Path) -> dict:
    """Build HAVEN's ALU testbench with the bulk fill plus this cell's sequence, and score the DUT."""
    if work.exists():
        shutil.rmtree(work)
    shutil.copytree(tb, work, ignore=shutil.ignore_patterns(
        "simv*", "csrc", "*.vdb", "urgReport*", "*.log", "ucli.key", "vc_hdrs.h"))
    shutil.copyfile(cell_seq, work / "rvprobe_cell_seq.sv")

    # Only the bulk fill and this cell's sequence: every cell starts from the same baseline.
    pkg = (work / "alu_top_pkg.sv").read_text()
    pkg = pkg.replace('`include "rvprobe_directed_seq.sv"\n', "")
    pkg = pkg.replace('`include "rvprobe_fp_seq.sv"\n', "")
    pkg = pkg.replace('`include "rvprobe_llm_seq.sv"', '`include "rvprobe_cell_seq.sv"')
    (work / "alu_top_pkg.sv").write_text(pkg)
    (work / "alu_top_test.sv").write_text(TEST_SV)

    env = {k: v for k, v in os.environ.items() if "proxy" not in k.lower()}
    def eda(cmd: str, timeout: int = 900) -> subprocess.CompletedProcess:
        return subprocess.run([str(SNPS_SHELL), "-c", f"cd {work} && {cmd}"],
                              capture_output=True, text=True, timeout=timeout, env=env)

    build = eda("vcs +vcs+lic+wait -sverilog -ntb_opts uvm -cm line+cond+tgl+fsm+branch "
                "-timescale=1ns/1ps +incdir+. +verilog2001ext+.v +error+100 -f filelist.f -o simv")
    if build.returncode != 0:
        return {"ok": False, "stage": "vcs", "detail": (build.stdout + build.stderr)[-2000:]}
    sim = eda("./simv +vcs+lic+wait +UVM_TESTNAME=alu_top_test +UVM_TIMEOUT=5000000000 "
              "+UVM_VERBOSITY=UVM_LOW -cm line+cond+tgl+fsm+branch")
    if sim.returncode != 0:
        return {"ok": False, "stage": "sim", "detail": (sim.stdout + sim.stderr)[-2000:]}
    eda("urg -dir simv.vdb -metric line+branch+tgl+cond -format text -report urgReport")

    sys.path.insert(0, str(ZAOZI / "experiments"))
    import urg_score
    report = urg_score.parse(str(work / "urgReport" / "modinfo.txt"))
    percent, score, _ = urg_score.score(report, [DUT_MODULE])
    return {"ok": True, "score": round(score, 2), **{k: round(v, 2) for k, v in percent.items()}}


TEST_SV = """// Ablation: the shared bulk fill, then this cell's sequence.
class alu_top_test extends uvm_test;
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
    rvprobe_bulk_seq bulk;
    rvprobe_cell_seq cell;
    phase.raise_objection(this);
    bulk = rvprobe_bulk_seq::type_id::create("bulk");
    bulk.start(m_env.m_agent.m_sequencer);
    cell = rvprobe_cell_seq::type_id::create("cell");
    cell.start(m_env.m_agent.m_sequencer);
    #1000;
    phase.drop_objection(this);
  endtask
endclass
"""


def run_cell(cell: str, res, out_root: Path, tb: Path, attempts: int, llm) -> dict:
    out_dir = out_root / cell.replace("/", "-")
    out_dir.mkdir(parents=True, exist_ok=True)
    errors, previous, tokens, history = None, None, 0, []
    started = time.time()

    for attempt in range(1, attempts + 1):
        reply = llm.invoke(build_prompt(cell, res, errors, previous))
        tokens += reply.response_metadata.get("token_usage", {}).get("total_tokens", 0)
        code = re.sub(r"^```[a-z]*\n|```$", "", reply.content.strip(), flags=re.M)
        (out_dir / f"attempt{attempt}.txt").write_text(code)

        report = run_typed(code, out_dir) if cell.endswith("/typed") else run_untyped(code, out_dir)
        history.append({"attempt": attempt, "phase": report.get("phase"), "ok": report.get("ok")})
        print(f"  {cell} attempt {attempt}: {report.get('phase')} ok={report.get('ok')}", file=sys.stderr)

        if report.get("ok"):
            seq = out_dir / "rvprobe_cell_seq.sv"
            cov = measure(tb, seq, out_dir / "tb") if seq.exists() else {"ok": False, "stage": "no-sequence"}
            return {"cell": cell, "attempts": attempt, "tokens": tokens, "history": history,
                    "caught_by": [h["phase"] for h in history if not h["ok"]],
                    "wall_clock_s": round(time.time() - started, 1),
                    "beats": report.get("result", {}).get("beats"), "coverage": cov}
        errors, previous = feedback(report), code

    return {"cell": cell, "attempts": attempts, "tokens": tokens, "history": history,
            "caught_by": [h["phase"] for h in history if not h["ok"]],
            "wall_clock_s": round(time.time() - started, 1), "failed": True}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--tb", type=Path, required=True, help="the ALU testbench directory to copy per cell")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--modinfo", type=Path, required=True, help="URG modinfo.txt of the bulk-fill baseline")
    ap.add_argument("--cells", default=",".join(CELLS))
    ap.add_argument("--attempts", type=int, default=3)
    args = ap.parse_args()

    from dotenv import load_dotenv
    load_dotenv(SCRATCH / "haven/.env")
    from langchain_openai import ChatOpenAI

    res = residual(args.modinfo)
    print(f"residual: {len(res)} uncovered lines", file=sys.stderr)
    llm = ChatOpenAI(model=MODEL, temperature=0.3, timeout=600)
    args.out.mkdir(parents=True, exist_ok=True)

    results = []
    for cell in args.cells.split(","):
        results.append(run_cell(cell, res, args.out, args.tb, args.attempts, llm))
        (args.out / "ablation.json").write_text(json.dumps(results, indent=2) + "\n")
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
