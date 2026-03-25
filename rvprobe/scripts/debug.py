#!/usr/bin/env python3
"""
Debug UNSAT constraint problems in rvprobe test cases.

Two modes:

  DSL-level (recommended):
    python3 rvprobe/scripts/debug.py dsl <case-fqcn>
    # e.g.: python3 rvprobe/scripts/debug.py dsl me.jiuyang.rvprobe.cases.cache.DCacheWriteHit

    Binary-searches at the instruction level inside the Scala solver.
    Reports which DSL instruction causes UNSAT.

  SMT-LIB level:
    python3 rvprobe/scripts/debug.py smtlib <file.smt2>

    Binary-searches assertions in an exported SMT-LIB file.
    Generate the file first:
      RVPROBE_DEBUG_SMTLIB=/tmp/debug mill rvprobe.runMain <case> <output.S>
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


# ============================================================
# DSL-level debug
# ============================================================
def debug_dsl(fqcn: str, workdir: Path) -> int:
    """Invoke RVGenerator.debugSolve() via RVPROBE_DEBUG env var."""
    print(f"[dsl] debugging {fqcn}...")
    env = {**os.environ, "RVPROBE_DEBUG": "1"}
    proc = subprocess.run(
        ["mill", "-i", "rvprobe.runMain", fqcn, "/dev/null"],
        cwd=workdir,
        env=env,
        text=True,
    )
    return proc.returncode


# ============================================================
# SMT-LIB level debug
# ============================================================
def parse_sexpr_blocks(content: str) -> list[str]:
    """Split SMT-LIB into top-level S-expression blocks."""
    blocks: list[str] = []
    lines = [line for line in content.split("\n") if not line.lstrip().startswith(";")]
    cleaned = "\n".join(lines)
    depth = 0
    current: list[str] = []
    for char in cleaned:
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        current.append(char)
        if depth == 0 and current:
            block = "".join(current).strip()
            if block:
                blocks.append(block)
            current = []
    return blocks


def classify_blocks(blocks: list[str]) -> tuple[list[str], list[int]]:
    """Return all blocks (sans solver commands) and indices of assert blocks."""
    filtered: list[str] = []
    assert_indices: list[int] = []
    for block in blocks:
        stripped = block.lstrip()
        if stripped.startswith("(check-sat") or stripped.startswith("(get-model") or stripped.startswith("(reset"):
            continue
        idx = len(filtered)
        filtered.append(block)
        if stripped.startswith("(assert"):
            assert_indices.append(idx)
    return filtered, assert_indices


def run_z3(smtlib: str, timeout_ms: int = 5000) -> str:
    proc = subprocess.run(
        ["z3", "-in", f"-t:{timeout_ms}"],
        input=smtlib, capture_output=True, text=True,
    )
    return proc.stdout.strip().split("\n")[0] if proc.stdout.strip() else "error"


def check_sat_prefix(all_blocks: list[str], up_to: int, timeout_ms: int) -> bool:
    smtlib = "\n".join(all_blocks[:up_to] + ["(check-sat)"])
    return run_z3(smtlib, timeout_ms) == "sat"


def debug_smtlib(path: Path, timeout_ms: int) -> int:
    import re

    content = path.read_text(encoding="utf-8")
    blocks = parse_sexpr_blocks(content)
    all_blocks, assert_indices = classify_blocks(blocks)
    n = len(assert_indices)
    print(f"[smtlib] {len(all_blocks)} blocks, {n} assertions")

    if not assert_indices:
        print("No assertions found.")
        return 0

    if check_sat_prefix(all_blocks, len(all_blocks), timeout_ms):
        print("All assertions are SAT.")
        return 0

    if not check_sat_prefix(all_blocks, assert_indices[0] + 1, timeout_ms):
        print("UNSAT at first assertion:")
        print(all_blocks[assert_indices[0]])
        return 1

    lo, hi = 0, n
    while hi - lo > 1:
        mid = (lo + hi) // 2
        cutoff = assert_indices[mid - 1] + 1
        print(f"  [{lo}..{hi}] trying {mid}/{n}...", end=" ", flush=True)
        if check_sat_prefix(all_blocks, cutoff, timeout_ms):
            print("sat")
            lo = mid
        else:
            print("unsat")
            hi = mid

    block_idx = assert_indices[hi - 1]
    text = all_blocks[block_idx]
    print(f"\nFirst UNSAT: assertion #{hi - 1} (block {block_idx})")
    print(text[:500] + ("..." if len(text) > 500 else ""))

    variables = sorted(set(re.findall(r"\b(?:nameId|rd|rs1|rs2|imm12|csr|freg)_\d+\b", text)))
    if variables:
        print(f"Variables: {', '.join(variables)}")
    return 1


# ============================================================
# CLI
# ============================================================
def main() -> int:
    ap = argparse.ArgumentParser(description="Debug UNSAT constraint problems in rvprobe")
    sub = ap.add_subparsers(dest="mode", required=True)

    dsl_p = sub.add_parser("dsl", help="DSL-level binary search (recommended)")
    dsl_p.add_argument("fqcn", help="Fully qualified case name")

    smt_p = sub.add_parser("smtlib", help="SMT-LIB assertion-level binary search")
    smt_p.add_argument("file", help="Path to .smt2 file")
    smt_p.add_argument("--timeout", type=int, default=5000, help="Z3 timeout per check (ms)")

    args = ap.parse_args()

    if args.mode == "dsl":
        return debug_dsl(args.fqcn, REPO_ROOT)
    elif args.mode == "smtlib":
        return debug_smtlib(Path(args.file), args.timeout)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
