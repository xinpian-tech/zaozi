"""Per-goal evidence: independent witness-prefix coverage versus full replay with drain."""
from pathlib import Path
import subprocess
from cycle_replay import witness_frames
from run_records import save


def line_delta(before, after):
    if set(before["bins"]) != set(after["bins"]):
        raise ValueError("per-goal coverage metric universe changed")
    for metric, (total, hit) in before["bins"].items():
        new_total, new_hit = after["bins"][metric]
        if total != new_total or new_hit < hit:
            raise ValueError("per-goal replay coverage regressed or universe changed")
    if not {line for line, _ in after["uncovered"]} <= {line for line, _ in before["uncovered"]}:
        raise ValueError("per-goal replay introduced residual lines")
    return sorted({line for line, _ in before["uncovered"]} - {line for line, _ in after["uncovered"]})


def measure_goals(replay, design, config, prefix, baseline, goals, number, directory):
    rows, accepted_frames = [], []
    sequences = []
    for goal in goals:
        if "sequences" not in goal:
            sequences.append(goal)
        else:
            for sample, sequence in enumerate(goal["sequences"], 1):
                sequences.append({**sequence, "intent_label": goal["label"], "sample_index": sample,
                                  "label": f"{goal['label']}__sample_{sample}"})
            if not goal["sequences"]:
                sequences.append(goal)
    for index, goal in enumerate(sequences):
        row = {"label": goal["label"], "solver_status": goal["status"], "solver_ms": goal.get("ms"),
               "intent_label": goal.get("intent_label", goal["label"]), "sample_index": goal.get("sample_index", 1),
               "status": "not-generated", "witness_closed_lines": [], "drain_added_lines": [], "closed_lines": []}
        if goal["status"] == "generated":
            try:
                frames = witness_frames(design, config, goal, number * 100000 + index)
                witness = [f for f in frames if f["kind"] != "drain"]
                short = replay.simulate(f"goal-{number}-{goal['label']}-witness", prefix + witness)
                full = replay.simulate(f"goal-{number}-{goal['label']}-full", prefix + frames)
                row.update(status="replayed", witness_closed_lines=line_delta(baseline, short),
                           drain_added_lines=line_delta(short, full), closed_lines=line_delta(baseline, full),
                           witness_coverage=short["percent"], full_coverage=full["percent"],
                           witness_checks=short["replay"], full_checks=full["replay"],
                           witness_modinfo=short["modinfo"], full_modinfo=full["modinfo"])
                accepted_frames += frames
            except (ValueError, OSError, subprocess.SubprocessError, KeyError) as error:
                row.update(status="replay-failed", error=str(error))
        rows.append(row)
        save(Path(directory) / "goal-coverage.json", rows)
    return rows, accepted_frames
