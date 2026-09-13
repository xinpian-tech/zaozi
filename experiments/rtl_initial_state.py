"""Generate a complete, known post-reset state from the original RTL, not LLM output.

The pinned Verilator frontend discovers clocked storage; VCS evaluates RTL initial
blocks and the manifest reset prefix. This intentionally fails closed outside the
supported single-module, positive-edge, finite integral-storage subset.
"""
import itertools
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess

from process_runner import run
from run_records import Records, fingerprint, save
from cycle_replay import digest
from sequence_framework import identifier

POLICY = "rtl-simulated-reset-state-v1"


def nodes(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from nodes(child)
    elif isinstance(value, list):
        for child in value:
            yield from nodes(child)


def state_ports(tree, design):
    if tree.get("type") != "NETLIST":
        raise ValueError("unsupported Verilator AST schema")
    modules = [m for m in tree.get("modulesp", []) if m.get("type") == "MODULE" and m.get("level") == 1]
    if len(modules) != 1 or modules[0].get("origName") != design.top:
        raise ValueError("snapshot AST top differs from the RTL manifest")
    module = modules[0]
    if any(n.get("type") == "CELL" for n in nodes(module)):
        raise ValueError("automatic snapshot currently requires a single-module DUT")
    types = {n["addr"]: n for n in nodes(tree.get("miscsp", [])) if n.get("type", "").endswith("DTYPE")}
    variables = {n["addr"]: n for n in module.get("stmtsp", []) if n.get("type") == "VAR"}
    writes = set()
    for block in nodes(module):
        if block.get("type") != "ALWAYS":
            continue
        if block.get("keyword") == "always_latch":
            raise ValueError("latch state is not supported by automatic snapshot")
        senses = [n for n in nodes(block.get("sentreep", [])) if n.get("type") == "SENITEM"]
        if not senses:
            continue
        clock_seen = False
        for sense in senses:
            refs = sense.get("sensp", [])
            if len(refs) != 1 or refs[0].get("type") != "VARREF" or sense.get("condp"):
                raise ValueError("unsupported snapshot clock/reset sensitivity")
            name, edge = refs[0]["name"], sense.get("edgeType")
            if name == design.clock and edge == "POS":
                clock_seen = True
            elif name == design.reset and edge == ("NEG" if design.reset_active_low else "POS"):
                pass
            else:
                raise ValueError("automatic snapshot requires the manifest positive-edge clock only")
        if not clock_seen:
            raise ValueError("snapshot state block has no manifest clock")
        writes.update(n["varp"] for n in nodes(block.get("stmtsp", []))
                      if n.get("type") == "VARREF" and n.get("access") in ("WR", "RW"))
    result = []
    total_bits = 0
    for address in sorted(writes):
        if address not in variables:
            raise ValueError("snapshot cannot map scoped storage to an RTL variable")
        variable = variables[address]
        name = identifier(variable.get("verilogName"), "snapshot state name")
        dtype = types[variable["dtypep"]]
        ranges = []
        while dtype["type"] == "UNPACKARRAYDTYPE":
            bounds = re.fullmatch(r"\[(-?\d+):(-?\d+)\]", dtype.get("declRange", ""))
            if not bounds:
                raise ValueError("snapshot requires constant array bounds")
            lo, hi = sorted(map(int, bounds.groups()))
            if hi-lo > 65535:
                raise ValueError("snapshot array exceeds supported size")
            ranges.append(range(lo, hi+1))
            dtype = types[dtype["refDTypep"]]
        if dtype["type"] != "BASICDTYPE" or dtype.get("keyword") not in ("logic", "bit", "reg", "integer", "int"):
            raise ValueError("unsupported snapshot storage type")
        bounds = re.fullmatch(r"(-?\d+):(-?\d+)", dtype.get("range", "0:0"))
        if not bounds:
            raise ValueError("snapshot requires a constant packed width")
        width = abs(int(bounds[1])-int(bounds[2]))+1
        for indices in itertools.product(*ranges):
            total_bits += width
            if total_bits > 1048576:
                raise ValueError("snapshot exceeds one million storage bits")
            result.append({"name": "dut."+name+"".join(f"[{i}]" for i in indices), "width": width})
    if not result:
        raise ValueError("no clocked storage found for snapshot")
    return sorted(result, key=lambda p: p["name"])


def render_probe(design, replay, ports):
    declarations, connections = [], []
    for p in design.ports:
        identifier(p.name, "probe IO")
        kind = "reg" if p.direction == "input" else "wire"
        initial = ""
        if p.direction == "input":
            value = 0 if p.name == design.clock else (int(not design.reset_active_low) if p.name == design.reset else replay["idle"][p.name])
            initial = f" = {p.width}'h{value:x}"
        declarations.append(f"{kind} [{p.width-1}:0] {p.name}{initial};")
        connections.append(f".{p.name}({p.name})")
    params = ""
    if design.parameters:
        params = " #("+", ".join(f".{identifier(k, 'parameter')}({v})" for k,v in design.parameters)+")"
    displays = "\n".join(f'$display("RVPROBE_INIT {p["name"]} %b", {p["name"]});' for p in ports)
    return ("`timescale 1ns/1ps\nmodule rvprobe_initial_state_probe;\n"+"\n".join(declarations)+
        f"\n{design.top}{params} dut ("+", ".join(connections)+
        f");\nalways #5 {design.clock} = ~{design.clock};\ninitial begin\n"
        f"repeat ({replay['reset_cycles']}) @(posedge {design.clock});\n"
        f"@(negedge {design.clock});\n{design.reset} = 1'b{int(design.reset_active_low)};\n#1;\n"+
        displays+'\n$display("RVPROBE_INIT_DONE");\n$finish;\nend\nendmodule\n')


def parse_state(log, ports):
    expected = {p["name"]: p["width"] for p in ports}
    values = {}
    for name, bits in re.findall(r"(?m)^RVPROBE_INIT (\S+) ([01xXzZ]+)\s*$", log):
        if name in values or name not in expected or len(bits) != expected[name]:
            raise ValueError("duplicate, unexpected or wrong-width snapshot state")
        if re.search(r"[xXzZ]", bits):
            raise ValueError(f"unknown post-reset state: {name}; do not zero-fill missing initialization")
        values[name] = f"{len(bits)}'h{int(bits, 2):x}"
    if set(values) != set(expected) or "RVPROBE_INIT_DONE" not in log.splitlines():
        raise ValueError("incomplete RTL snapshot simulation")
    return values


def validate_prior(text, values):
    words = text.split()
    if len(words) % 2:
        raise ValueError("invalid trusted snapshot format")
    seen = set()
    for name, literal in zip(words[::2], words[1::2]):
        match = re.fullmatch(r"(\d+)'([hb])([0-9a-fA-F]+)", literal)
        actual = re.fullmatch(r"(\d+)'h([0-9a-f]+)", values.get(name, ""))
        if name in seen or not match or not actual:
            raise ValueError(f"trusted snapshot entry is not discovered RTL state: {name}")
        seen.add(name)
        if int(match[1]) != int(actual[1]) or int(match[3], 16 if match[2]=='h' else 2) != int(actual[2], 16):
            raise ValueError(f"trusted snapshot conflicts with simulated RTL reset state: {name}")


def verify_prepared(job, design, replay):
    """Do not sample a historical partial snapshot under the repaired policy."""
    if replay.get('environment') and replay.get('formal_initial_state'):
        raise ValueError('event environment cannot reuse the single-clock RTL snapshot policy')
    if not replay.get("formal_initial_state"):
        if job.get("initialState"):
            raise ValueError("prepared snapshot has no matching replay policy")
        return
    record = job.get("initialStateRecord")
    if not isinstance(record, dict) or record.get("policy") != POLICY:
        raise ValueError("prepared initial state is incomplete; regenerate the UT solver job")
    path = Path(record["file"])
    if digest(path) != record["sha256"]:
        raise ValueError("prepared snapshot record changed")
    saved = json.loads(path.read_text())
    policy = replay["formal_initial_state"]
    if policy != {"mode": "rtl-reset-simulation"} and (
            set(policy) != {"file", "sha256"} or
            hashlib.sha256(saved.get("prior", "").encode()).hexdigest() != policy["sha256"]):
        raise ValueError("prepared snapshot trusted input policy changed")
    if (saved.get("design") != design.record() or saved.get("reset_cycles") != replay["reset_cycles"] or
            saved.get("idle") != replay["idle"] or saved.get("implementation") != digest(Path(__file__))):
        raise ValueError("prepared snapshot design/reset/implementation changed")
    if any(digest(Path(p)) != h for p,h in saved["artifact_sha256"].items()):
        raise ValueError("prepared snapshot artifacts changed")
    if job.get("initialState") != (path.parent / "initial.state").read_text():
        raise ValueError("solver and sampler initial states differ")


def generate(design, replay, directory, prior, eda_shell):
    if replay.get('environment'):
        raise ValueError('event environment requires native formal reset, not single-clock snapshot')
    directory = Path(directory).resolve()
    inputs = {"policy": POLICY, "design": design.record(), "reset_cycles": replay["reset_cycles"],
              "idle": replay["idle"], "prior": prior, "implementation": digest(Path(__file__))}
    key = fingerprint(inputs)
    if (directory / "snapshot.json").exists():
        saved = json.loads((directory / "snapshot.json").read_text())
        if saved["fingerprint"] != key or any(digest(Path(p)) != h for p,h in saved["artifact_sha256"].items()):
            raise ValueError("RTL snapshot inputs or artifacts changed")
        return (directory / "initial.state").read_text()
    directory.mkdir(parents=True, exist_ok=False)
    env = {k:v for k,v in os.environ.items() if k not in (
        "RVPROBE_LLM_API_KEY", "RVPROBE_LLM_BASE_URL", "OPENAI_API_KEY", "OPENAI_BASE_URL")}
    def execute(command, name):
        with Records(directory).phase(name):
            process = run(command, cwd=directory, env=env, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=300)
            (directory / (name+".log")).write_text(process.stdout)
            if process.returncode:
                raise ValueError(f"{name} failed: {process.stdout[-2000:]}")
            return process.stdout
    tree_path = directory / "rtl.tree.json"
    output = execute(["verilator", "--json-only", "--json-only-output", str(tree_path),
        "--Mdir", str(directory / "verilator"), "--top-module", design.top, "-Wno-fatal",
        *["-I"+str(p) for p in design.include_dirs], *[f"-G{k}={v}" for k,v in design.parameters],
        *map(str, design.sources)], "snapshot-discover")
    if re.search(r"%Warning-(?:LATCH|MULTIDRIVEN|UNSUPPORTED)", output):
        raise ValueError("snapshot discovery reported unsupported state semantics")
    ports = state_ports(json.loads(tree_path.read_text()), design)
    save(directory / "storage.json", ports)
    probe = directory / "probe.sv"
    probe.write_text(render_probe(design, replay, ports))
    compile_args = ["vcs", "-full64", "-sverilog", "-timescale=1ns/1ps", "-top", "rvprobe_initial_state_probe", "-o", "simv",
        *["+incdir+"+str(p) for p in design.include_dirs], *map(str, design.sources), str(probe)]
    execute([str(eda_shell), "-c", shlex.join(compile_args)], "snapshot-compile")
    log = execute([str(eda_shell), "-c", "./simv"], "snapshot-simulate")
    values = parse_state(log, ports)
    validate_prior(prior, values)
    state = "".join(f"{name}\n{value}\n" for name,value in sorted(values.items()))
    (directory / "initial.state").write_text(state)
    save(directory / "snapshot.json", {"fingerprint": key, **inputs, "register_words": len(values),
        "artifact_sha256": {str(p):digest(p) for p in [tree_path, probe, directory/"storage.json",
            directory/"snapshot-discover.log", directory/"snapshot-compile.log", directory/"snapshot-simulate.log",
            directory/"initial.state"]}})
    return state
