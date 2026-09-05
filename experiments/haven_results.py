#!/usr/bin/env python3
"""Rebuild the HAVEN reproduction table from a sweep's run directories.

Emits `haven-deepseek-results.{csv,json}` under --out.

The one thing this script exists to make explicit: HAVEN's headline coverage
number is the mean of whichever URG metric columns that design produced, and URG
emits an FSM column only where it infers a state machine.  Six of the sixteen
designs are therefore scored over five metrics and the rest over four, so the
published column is not one quantity.  Every row here carries `metrics` (what
went into it), `score_published` (HAVEN's own rule, kept for traceability), and
`score_lctb` (line/cond/toggle/branch only).

Which delta to quote depends on what is being compared:

  * `delta_published` against the GPT baselines.  Those come from HAVEN's paper
    and cannot be re-scored -- we hold no URG reports for them -- so the only
    defensible comparison is same-rule against same-rule, mixed metric sets and
    all.
  * `delta_lctb` for anything where both reports are in hand: design against
    design, or this sweep against another tool's run.  There, pin the metric set
    (`urg_score.py --metrics`) instead of averaging whatever URG happened to
    emit, since a design with an FSM is otherwise scored on a different scale
    from one without.

Per-design coverage is HAVEN's best iteration, matching how it reports; the
final-iteration URG report saved under each run may be lower where a late
sequence regeneration lost ground.
"""
import argparse
import csv
import json
from pathlib import Path

# HAVEN's published GPT numbers, from scripts/generate_paper_tables.py (best run per design).
GPT_BASELINE = {
    "aes_core": 96.5, "alu_top": 96.1, "keccak": 95.7, "spi_top": 93.1, "can_top": 90.6,
    "i2c_master_top": 90.0, "uart_top": 87.2, "sdrc_top": 70.0, "ethmac": 69.7,
}
LCTB = ("line", "cond", "toggle", "branch")
COLUMNS = [
    "design", "run", "attempts", "compile_status", "metrics", "score_published", "score_lctb",
    "line", "cond", "toggle", "fsm", "branch", "cov_iterations",
    "gpt_baseline", "delta_published", "delta_lctb",
    "prompt_tokens", "completion_tokens", "total_tokens", "llm_calls",
    "wall_clock_s", "scoreboard_pass", "scoreboard_fail",
]


def load(path, default=None):
    try:
        return json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return default


def best_iteration(run):
    """HAVEN's own reporting rule: the iteration with the highest total_coverage."""
    prog = load(run / "ir" / "metrics_coverage_progression.json", [])
    entries = prog if isinstance(prog, list) else prog.get("iterations", prog.get("progression", []))
    best = None
    for entry in entries:
        if isinstance(entry, dict) and isinstance(entry.get("total_coverage"), (int, float)):
            if best is None or entry["total_coverage"] > best["total_coverage"]:
                best = entry
    return best, len(entries)


def row_for(run):
    status = load(run / "status.json", {})
    best, iterations = best_iteration(run)
    summary = (best or {}).get("summary") or {}
    present = [m for m in ("line", "cond", "toggle", "fsm", "branch") if summary.get(m) is not None]
    lctb = [summary[m] for m in LCTB if summary.get(m) is not None]

    published = (best or {}).get("total_coverage")
    score_lctb = sum(lctb) / len(lctb) if lctb else None
    gpt = GPT_BASELINE.get(status.get("module_name", run.name))
    tokens = (load(run / "ir" / "metrics_token_usage.json", {}) or {}).get("total", {})
    scoreboard = (load(run / "ir" / "phase6_sim_result.json", {}) or {}).get("scoreboard", {})

    return {
        "design": status.get("module_name", run.name),
        "run": run.name,
        "compile_status": (status.get("phases", {}).get("5", {}) or {}).get("status"),
        "metrics": "+".join(present),
        "score_published": None if published is None else round(published, 3),
        "score_lctb": None if score_lctb is None else round(score_lctb, 3),
        **{m: summary.get(m) for m in ("line", "cond", "toggle", "fsm", "branch")},
        "cov_iterations": iterations,
        "gpt_baseline": gpt,
        "delta_published": None if (published is None or gpt is None) else round(published - gpt, 2),
        "delta_lctb": None if (score_lctb is None or gpt is None) else round(score_lctb - gpt, 2),
        "prompt_tokens": tokens.get("prompt_tokens"),
        "completion_tokens": tokens.get("completion_tokens"),
        "total_tokens": tokens.get("total_tokens"),
        "llm_calls": tokens.get("calls"),
        "wall_clock_s": (load(run / "ir" / "metrics_timing.json", {}) or {}).get("wall_clock_seconds"),
        "scoreboard_pass": scoreboard.get("pass_count"),
        "scoreboard_fail": scoreboard.get("fail_count"),
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("runs", type=Path, help="directory of per-design run directories")
    ap.add_argument("--out", type=Path, required=True, help="directory to write the CSV and JSON into")
    ap.add_argument("--stem", default="haven-deepseek-results")
    args = ap.parse_args()

    # A design can appear several times -- the sweep retried designs that crashed early on.
    # Report the attempt that got furthest, the way HAVEN reports its best run, but say how
    # many there were so a design that needed five tries is not indistinguishable from one.
    by_design = {}
    for run in sorted(p for p in args.runs.iterdir() if p.is_dir()):
        row = row_for(run)
        by_design.setdefault(row["design"], []).append(row)

    rows = []
    for design, attempts in by_design.items():
        best = max(attempts, key=lambda r: (r["score_lctb"] is not None, r["score_lctb"] or 0, r["run"]))
        rows.append({**best, "attempts": len(attempts)})
    rows.sort(key=lambda r: (r["score_lctb"] is None, -(r["score_lctb"] or 0)))
    args.out.mkdir(parents=True, exist_ok=True)

    (args.out / f"{args.stem}.json").write_text(json.dumps(rows, indent=2) + "\n")
    with (args.out / f"{args.stem}.csv").open("w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=COLUMNS, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow({k: ("" if v is None else v) for k, v in row.items()})

    scored = [r for r in rows if r["score_lctb"] is not None]
    mixed = [r for r in scored if "fsm" in r["metrics"]]
    print(f"{len(rows)} runs, {len(scored)} scored; {len(mixed)} scored with FSM by HAVEN's rule:")
    for r in mixed:
        print(f"  {r['design']:16s} published {r['score_published']:6.2f} -> lctb {r['score_lctb']:6.2f}"
              f"  ({r['score_lctb'] - r['score_published']:+.2f})")


if __name__ == "__main__":
    main()
