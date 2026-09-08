"""Shared HAVEN experiment contract. No model calls or EDA work on import.

The external HAVEN checkout is an explicit, hashed dependency, not copied from a
machine-local scratchpad. Both generators consume the same frozen Stage-1 bench.
"""
from copy import deepcopy
import hashlib
import json
import math
from pathlib import Path
import re

from cycle_replay import check_drive
from run_records import fingerprint
from sequence_framework import identifier

CONTRACT = "haven-shared-v1"
METRICS = ("line", "cond", "toggle", "branch", "fsm")


def checkout_hashes(root):
    root = Path(root).resolve()
    paths = sorted(p for p in (root / "src/haven").rglob("*")
                   if p.is_file() and p.suffix in (".py", ".j2", ".md"))
    if not paths or not (root / "src/haven/eda/urg_utils.py").is_file():
        raise ValueError("--haven-root must contain the HAVEN verification implementation")
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}


def coverage_score(bins):
    percentages = {}
    for metric, pair in bins.items():
        if metric not in METRICS or len(pair) != 2:
            raise ValueError("unsupported coverage metric")
        total, hit = pair
        if type(total) is not int or type(hit) is not int or not 0 <= hit <= total:
            raise ValueError("invalid coverage bin counts")
        if total:
            percentages[metric] = 100 * hit / total
    if not percentages:
        raise ValueError("no measurable DUT coverage")
    return percentages, sum(percentages.values()) / len(percentages)


def coverage_progress(before, after):
    if set(before["bins"]) != set(after["bins"]):
        raise ValueError("coverage metric universe changed")
    for metric, (total, _) in before["bins"].items():
        if after["bins"][metric][0] != total:
            raise ValueError("coverage denominator changed")
    # Fresh cumulative simulations may regress: retain and report it, never hide it.
    return {"score_gain": after["score"] - before["score"],
            "bin_gains": {m: after["bins"][m][1] - before["bins"][m][1] for m in before["bins"]}}


def stop_reason(previous, current, rounds_done, budget, min_gain=0.1, target=100.0):
    """Evaluate ONLY after a completed simulation, not after sequence generation."""
    if budget < 1 or not math.isfinite(min_gain) or min_gain <= 0 or not 0 < target <= 100:
        raise ValueError("invalid coverage stopping policy")
    if current["score"] >= target:
        return "coverage_target"
    if rounds_done >= budget:
        return "round_budget"
    if previous is not None and coverage_progress(previous, current)["score_gain"] < min_gain - 1e-10:
        return "coverage_stalled"
    return None


def compact_feedback(coverage, previous=None):
    """One copy of each typed gap; no per-sequence repetition of whole reports."""
    gaps = {fingerprint(g): g for g in coverage["uncovered"]}
    return {"contract": CONTRACT, "scope": coverage["modules"],
            "coverage": coverage["percent"], "bins": coverage["bins"], "score": coverage["score"],
            "gaps": sorted(gaps.values(), key=lambda g: json.dumps(g, sort_keys=True)),
            "progress": coverage_progress(previous, coverage) if previous else None,
            "instruction": "Target remaining typed coverage gaps, including toggle directions and conditions. "
                           "A generated intent is not coverage evidence. Do not exclude pending proofs."}


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise ValueError(f"unsupported HAVEN template; expected one occurrence of {old!r}")
    return source.replace(old, new, 1)


def repair_components(components, ports, *, owned_signals=(), clock_connections=None):
    """Ground field widths in IO; keep static/clock/BFM pins single-owner.

    clock_connections is an explicit signal -> top-level clock map. Never infer
    a clock from its name, and never force a configuration pin to a guessed value.
    """
    result = deepcopy(components)
    changes = []
    known = {p["name"]: p for p in ports}
    for name in known:
        identifier(name, "IO name")
    item = result["seq_item"]
    references = set()
    for key, code in result.items():
        if key.endswith(("driver", "monitor")):
            references.update(re.findall(r"\b(?:item|txn|req)\.(\w+)", code))
    additions = []
    for name in sorted(references & known.keys()):
        port = known[name]
        width = int(port["width"])
        declaration = re.search(rf"\b(?:rand\s+)?(?:bit|logic|reg|int)\s+(?:signed\s+)?(?:\[[^]]+\]\s*)?{name}\s*;", item)
        if declaration:
            old = declaration.group()
            rand = "rand " if old.startswith("rand ") and port["direction"] == "input" else ""
            desired = f"{rand}logic [{width - 1}:0] {name};"
            # Widths and output ownership come from the DUT, not a hallucinated field.
            bits = re.search(r"\[(\d+):0\]", old)
            old_width = int(bits[1]) + 1 if bits else (32 if re.search(r"\bint\b", old) else 1)
            if old_width != width or (old.startswith("rand ") and port["direction"] == "output"):
                item = item[:declaration.start()] + desired + item[declaration.end():]
                changes.append(f"seq_item: correct IO field {name} to width {width} and direction {port['direction']}")
            continue
        if re.search(rf"\b\w+_t\s+{name}\s*;", item):
            raise ValueError(f"typed field {name} requires elaborated width validation; refusing duplicate declaration")
        rand = "rand " if port["direction"] == "input" and name not in owned_signals else ""
        additions.append(f"  {rand}bit [{width - 1}:0] {name};")
        changes.append(f"seq_item: add IO-grounded field {name}[{width}]")
    if additions:
        item = replace_once(item, "endclass", "\n".join(additions) + "\nendclass")
    result["seq_item"] = item
    owned = set(owned_signals) | set(clock_connections or {})
    for key, code in list(result.items()):
        if not key.endswith("driver"):
            continue
        for name in sorted(owned):
            identifier(name, "owned signal")
            code, count = re.subn(rf"(?m)^\s*vif\.{name}\s*(?:<=|=(?!=))[^;]*;[^\n]*$", "", code)
            if count:
                changes.append(f"{key}: remove {count} writes to externally owned {name}")
        result[key] = code
    for signal, clock in (clock_connections or {}).items():
        identifier(clock, "top clock")
        top = result["top"]
        if not re.search(rf"\b(?:logic|wire|reg)\s+{clock}\s*;", top):
            raise ValueError(f"clock source not declared in top: {clock}")
        assignment = f"  assign vif.{signal} = {clock};"
        if re.search(rf"\bvif\.{signal}\s*(?:<=|=(?!=))", top):
            raise ValueError(f"clock {signal} already driven; inspect its owner")
        result["top"] = replace_once(top, "endmodule", assignment + "\nendmodule")
        changes.append(f"top: connect {signal} to explicit clock {clock}")
    return result, changes


def install_cycle_transport(components, design, config, driver_key):
    """Add raw-cycle item mode to the SAME HAVEN driver used by normal sequences.

    Normal transactions retain HAVEN semantics. Raw witnesses must not be stretched
    by ready/done waits. The hook is common to both arms, including baseline.
    """
    result = deepcopy(components)
    if any("rvp_raw" in code for code in result.values()):
        raise ValueError("cycle transport already installed")
    check_drive(design, config["idle"])
    for key in ("interface", "top", "pkg"):
        if re.search(r"`timescale\s+(?!1ns\s*/\s*1ps)", result[key]):
            raise ValueError("paired transport requires a 1ns/1ps testbench timebase")
        if "`timescale" not in result[key]:
            result[key] = "`timescale 1ns/1ps\n" + result[key]
    inputs = [p for p in design.data_ports if p.direction == "input"]
    outputs = [p for p in design.data_ports if p.direction == "output"]
    fields = ["  bit rvp_raw = 0;", "  bit rvp_reset;", "  int rvp_ordinal;"]
    for p in inputs:
        fields.append(f"  bit [{p.width-1}:0] rvp_drive_{p.name};")
    for p in outputs:
        fields.extend([f"  bit [{p.width-1}:0] rvp_expected_{p.name};", f"  bit [{p.width-1}:0] rvp_mask_{p.name};"])
    result["seq_item"] = replace_once(result["seq_item"], "endclass", "\n".join(fields) + "\nendclass")
    sampling = ("  bit rvp_reset_request = 0;\n"
                f"  clocking rvp_sample @(posedge {design.clock});\n    default input #1step;\n"
                f"    input {', '.join([design.reset] + [p.name for p in design.data_ports])};\n  endclocking\n")
    result["interface"] = replace_once(result["interface"], "endinterface", sampling + "endinterface")
    # Keep the top's power-on reset; only the interface/DUT connection uses combined reset.
    top = result["top"]
    combined = f"({design.reset} {'& ~' if design.reset_active_low else '|'}vif.rvp_reset_request)"
    top = replace_once(top, f"vif(clk, {design.reset})", f"vif(clk, {combined})")
    top = replace_once(top, f".{design.reset}({design.reset})", f".{design.reset}({combined})")
    initialization = "\n  initial begin\n" + "\n".join(
        f"    vif.{p.name} = {p.width}'h{config['idle'][p.name]:x};" for p in inputs) + "\n  end\n"
    result["top"] = replace_once(top, "endmodule", initialization + "endmodule")
    code = result[driver_key]
    calls = re.findall(r"seq_item_port\.get_next_item\((\w+)\);", code)
    if len(calls) != 1:
        raise ValueError("unsupported HAVEN driver get_next_item hook")
    var = calls[0]
    hook = (f"seq_item_port.get_next_item({var});\n"
            f"      if ({var}.rvp_raw) begin\n        rvp_drive_cycle({var});\n"
            "        seq_item_port.item_done();\n        continue;\n      end")
    code = replace_once(code, f"seq_item_port.get_next_item({var});", hook)
    writes = "\n".join(f"    vif.{p.name} = item.rvp_drive_{p.name};" for p in inputs)
    checks = "\n".join(
        f'    if ((vif.rvp_sample.{p.name} & item.rvp_mask_{p.name}) !== '
        f'(item.rvp_expected_{p.name} & item.rvp_mask_{p.name})) `uvm_fatal("WITNESS", "{p.name} mismatch")'
        for p in outputs)
    fmt = " ".join(["%h"] * len(inputs) + ["%b"] * len(outputs))
    values = "".join(f", vif.rvp_sample.{p.name}" for p in inputs + outputs)
    task = f'''\n  task rvp_drive_cycle({design.top}_seq_item item);
    @(negedge vif.{design.clock});
    vif.rvp_reset_request = item.rvp_reset;
{writes}
    @(vif.rvp_sample);
{checks}
    $display("RVPROBE_SAMPLE %0d %0t %b {fmt}", item.rvp_ordinal, $time, vif.rvp_sample.{design.reset}{values});
  endtask
'''
    result[driver_key] = replace_once(code, "endclass", task + "endclass")
    return result


def repair_direct_handshake(components, design, config, driver_key="driver"):
    """Fix the known template race without inventing a DUT behavior model.

    Derive the existing completion condition from the HAVEN template. Send a
    one-cycle request according to the versioned replay protocol, and fail on
    timeout. Unsupported/non-handshake drivers are left untouched.
    """
    result = deepcopy(components)
    code = result[driver_key]
    found = re.search(r"while \((vif\.\w+\s*!==\s*1'b[01]) && _hs_cnt < (\d+)\)", code)
    if not found:
        return result, []
    if len(config["request"]) != 1:
        raise ValueError("direct handshake repair needs one explicit request signal")
    request, active = next(iter(config["request"].items()))
    if config["idle"][request] == active:
        raise ValueError("request idle/active values must differ")
    match = re.search(r"  task drive_item\([^)]*\);.*?  endtask", code, re.S)
    if not match:
        raise ValueError("unsupported direct handshake task")
    writes = "\n".join(f"    vif.{p.name} = item.{p.name};" for p in design.data_ports if p.direction == "input")
    condition, limit = found.groups()
    task = f'''  task drive_item({design.top}_seq_item item);
    bit completed;
    int count;
    @(negedge vif.{design.clock});
{writes}
    @(posedge vif.{design.clock});
    #1; // inspect the response after this request's edge, not stale prior done
    completed = !({condition});
    @(negedge vif.{design.clock});
    vif.{request} = {config['idle'][request]};
    if (item.{request} == {active}) begin
      count = 0;
      while (!completed && count < {limit}) begin
        @(posedge vif.{design.clock});
        #1;
        completed = !({condition});
        count++;
      end
      if (!completed) `uvm_fatal("HANDSHAKE_TIMEOUT", "Request did not complete")
    end
  endtask'''
    result[driver_key] = code[:match.start()] + task + code[match.end():]
    return result, ["direct handshake: falling-edge drive, one-cycle request, fresh completion, fatal timeout"]


def render_witness_sequence(design, frames, label, ordinal=0):
    identifier(label, "sequence class")
    body = []
    for index, row in enumerate(frames, ordinal):
        check_drive(design, row["drive"])
        body += [f'    item = {design.top}_seq_item::type_id::create("beat_{index}");',
                 "    item.rvp_raw = 1;", f"    item.rvp_reset = {int(row['kind'] == 'reset')};",
                 f"    item.rvp_ordinal = {index};"]
        for p in design.data_ports:
            if p.direction == "input":
                body.append(f"    item.rvp_drive_{p.name} = {p.width}'h{row['drive'][p.name]:x};")
            else:
                value, mask = row["expected"].get(p.name, [0, 0])
                body += [f"    item.rvp_expected_{p.name} = {p.width}'h{value:x};",
                         f"    item.rvp_mask_{p.name} = {p.width}'h{mask:x};"]
        body += ["    start_item(item);", "    finish_item(item);"]
    return f'''class {label} extends uvm_sequence #({design.top}_seq_item);
  `uvm_object_utils({label})
  function new(string name="{label}"); super.new(name); endfunction
  task body();
    {design.top}_seq_item item;
{chr(10).join(body)}
    #1;
  endtask
endclass
'''


def check_sequence_set(sequences):
    names = []
    for code in sequences:
        found = re.findall(r"\bclass\s+(\w+)\s+extends\s+uvm_sequence\b", code)
        if len(found) != 1:
            raise ValueError("expected exactly one UVM sequence class per source")
        names.extend(found)
    if len(set(names)) != len(names):
        raise ValueError("duplicate sequence class; do not replace prior sequences")
    return names
