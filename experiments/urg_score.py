#!/usr/bin/env python3
"""Score a VCS/URG coverage report over a chosen set of modules.

Two runs are only comparable if their scores cover the same modules and the same
metrics, and neither holds by default:

  * Scope.  URG's headline SCORE spans the whole elaborated hierarchy, so it
    mixes the DUT with whatever testbench the arm happened to bring -- a BFM at
    12% drags the number down without saying anything about the DUT.  Restrict
    to the RTL modules and the two arms become comparable.

  * Metrics.  URG emits an FSM column only for designs where it infers a state
    machine, and HAVEN averages whatever columns it gets.  Six of its sixteen
    designs are therefore scored over five metrics and the rest over four --
    a silent difference worth up to ~4 points.  `--metrics` pins the set.

The score is the unweighted mean of the per-metric percentages, each of which is
covered-bins over total-bins summed across the kept modules -- URG's own rule,
re-applied to a subset.  Run with no module arguments to list what a report
contains.
"""
import argparse
import re
import sys

# Per metric: the modinfo section header, and the row within it holding the bin totals.
# FSM scores transitions -- URG prints the state row marked "(Not included in score)" --
# and a module may declare several state machines, so every match in the section is summed.
METRIC_ROWS = {
    "line":   ("Line",   r"^TOTAL\s+(\d+)\s+(\d+)\s"),
    "cond":   ("Cond",   r"^Conditions\s+(\d+)\s+(\d+)\s"),
    "toggle": ("Toggle", r"^Total Bits\s+(\d+)\s+(\d+)\s"),
    "branch": ("Branch", r"^Branches\s+(\d+)\s+(\d+)\s"),
    "fsm":    ("FSM",    r"^Transitions\s+(\d+)\s+(\d+)\s"),
}
DEFAULT_METRICS = ("line", "cond", "toggle", "branch")

_MODULE_SPLIT = re.compile(r"={70,}\nModule : (\S+)\n={70,}")
_SECTION = re.compile(
    r"^(Line|Cond|Toggle|Branch|FSM) Coverage for Module[^\n]*\n(.*?)"
    r"(?=^(?:Line|Cond|Toggle|Branch|FSM) Coverage for Module|\Z)",
    re.S | re.M,
)
# The per-module summary URG itself prints, used by --verify to check this parser.
_MODULE_SCORE = re.compile(r"^SCORE\s+[A-Z ]*\n\s*([\d.]+)\s", re.M)


def parse(path):
    """Read a URG modinfo.txt into {module: ({metric: (total_bins, covered_bins)}, urg_score)}."""
    with open(path, errors="replace") as source:
        parts = _MODULE_SPLIT.split(source.read())
    report = {}
    for name, body in zip(parts[1::2], parts[2::2]):
        sections = {m.group(1): m.group(2) for m in _SECTION.finditer(body)}
        bins = {}
        for metric, (header, row) in METRIC_ROWS.items():
            section = sections.get(header)
            if not section:
                continue
            hits = re.findall(row, section, re.M)
            if hits:
                bins[metric] = (sum(int(t) for t, _ in hits), sum(int(c) for _, c in hits))
        stated = _MODULE_SCORE.search(body)
        report[name] = (bins, float(stated.group(1)) if stated else None)
    return report


def score(report, modules, metrics=DEFAULT_METRICS):
    """-> (per-metric percentage, mean of them, summed bins). Metrics absent everywhere are dropped."""
    totals = {}
    for name in modules:
        for metric, (total, covered) in report[name][0].items():
            if metric not in metrics:
                continue
            acc = totals.setdefault(metric, [0, 0])
            acc[0] += total
            acc[1] += covered
    percent = {m: 100.0 * c / t for m, (t, c) in totals.items() if t}
    return percent, (sum(percent.values()) / len(percent) if percent else None), totals


def verify(report, tolerance=0.02):
    """Re-derive each module's SCORE from its bins and compare to the one URG printed.

    URG's SCORE is the mean of whichever metric columns it emitted for that module, so
    scoring a module in isolation over exactly those metrics must reproduce it.  A
    mismatch means this parser is reading the wrong row, not that the report is odd.
    """
    bad = []
    for name, (bins, stated) in sorted(report.items()):
        if stated is None or not bins:
            continue
        _, mine, _ = score(report, [name], tuple(bins))
        if abs(mine - stated) > tolerance:
            bad.append((name, stated, mine))
    return bad


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("modinfo", help="path to a URG report's modinfo.txt")
    ap.add_argument("modules", nargs="*", help="modules to score over; omit to list what the report holds")
    ap.add_argument("--metrics", default=",".join(DEFAULT_METRICS),
                    help=f"comma-separated subset of {','.join(METRIC_ROWS)} (default: {','.join(DEFAULT_METRICS)})")
    ap.add_argument("--verify", action="store_true",
                    help="check this parser against the per-module SCORE URG printed, and exit")
    args = ap.parse_args()

    report = parse(args.modinfo)
    if args.verify:
        bad = verify(report)
        for name, stated, mine in bad:
            print(f"MISMATCH {name}: urg {stated:.2f}, recomputed {mine:.2f}")
        print(f"{len(report) - len(bad)}/{len(report)} modules reproduce URG's own SCORE")
        sys.exit(1 if bad else 0)
    if not args.modules:
        for name in sorted(report):
            print(f"{name:28s} {','.join(sorted(report[name][0]))}")
        return

    missing = set(args.modules) - set(report)
    if missing:
        sys.exit(f"not in {args.modinfo}: {' '.join(sorted(missing))}")

    metrics = tuple(args.metrics.split(","))
    unknown = set(metrics) - set(METRIC_ROWS)
    if unknown:
        sys.exit(f"unknown metric(s): {' '.join(sorted(unknown))}")

    percent, mean, totals = score(report, args.modules, metrics)
    for metric in metrics:
        if metric in totals:
            total, covered = totals[metric]
            print(f"  {metric:7s} {covered:6d}/{total:6d} = {percent[metric]:6.2f}")
    print(f"  SCORE (mean of {len(percent)}) = {mean:.2f}")


if __name__ == "__main__":
    main()
