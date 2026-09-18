"""Generate native post-reset state from the original RTL, not LLM output.

The pinned Verilator frontend discovers clocked storage; VCS evaluates RTL initial
blocks and the manifest reset prefix. This intentionally fails closed outside the
supported single-module, positive-edge, finite integral-storage subset. Legacy
snapshot mode requires fully known state; native mode preserves unknown bits
and combines a post-reset snapshot with the original JG reset sequence.
"""
import backend_imports
import itertools
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess

from rvprobe.backend.process import run
from run_records import Records, fingerprint, save
from cycle_replay import digest
from sequence_framework import identifier

POLICY = "rtl-simulated-reset-state-v1"
NATIVE_RESET_POLICY = "rtl-simulated-native-reset-v1"


class UnsupportedNativeReset(ValueError):
    """No native snapshot claim outside the deliberately small supported subset."""


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


def initialized_ports(tree, design, ports):
    """Only deterministic, untimed initial assignments; no design-specific values.

    VCS, not this code, evaluates constants and loops. AST inspection just rules
    out external data, randomness, delays and cross-process initial races.
    """
    module = next(m for m in tree['modulesp'] if m.get('origName') == design.top)
    variables = {n['addr']:n for n in module['stmtsp'] if n.get('type') == 'VAR'}
    loops = {k for k,v in variables.items() if v.get('isUsedLoopIdx')}
    allowed = {'INITIAL', 'INITIALSTATIC', 'BEGIN', 'ASSIGN', 'VARREF', 'CONST',
               'ARRAYSEL', 'SEL', 'LOOP', 'LOOPTEST', 'ADD', 'SUB', 'MUL',
               'GTS', 'LTS', 'GT', 'LT', 'GTES', 'LTES', 'GTE', 'LTE', 'EXTEND', 'EXTENDS'}
    initialized, writers = set(), set()
    for block in module['stmtsp']:
        if block.get('type') not in ('INITIAL', 'INITIALSTATIC'): continue
        writes = set()
        for n in nodes(block):
            if n.get('type') not in allowed or n.get('timingControlp'):
                raise UnsupportedNativeReset('initial block has unsupported timing, operation or side effect')
            if n.get('type') == 'VARREF':
                key = n['varp']
                if n.get('access') in ('RD', 'RW') and key not in loops:
                    raise UnsupportedNativeReset('initial expression reads non-loop state or DUT IO')
                if n.get('access') in ('WR', 'RW'): writes.add(key)
        if writers & writes: raise UnsupportedNativeReset('multiple initial blocks write the same variable')
        writers.update(writes)
        initialized.update(writes-loops)
    names = {'dut.'+variables[k]['verilogName'] for k in initialized if k in variables}
    selected = [p for p in ports if p['name'].split('[')[0] in names]
    if not selected: raise UnsupportedNativeReset('no supported explicitly initialized clocked storage')
    return selected


def native_reset_environment(design, replay):
    env = replay.get('environment')
    return not env or (env.get('boundary') == 'independent-dut-v1' and
        len(env.get('clocks', [])) == 1 and env['clocks'][0]['port'] == design.clock and
        not any(env.get(k) for k in ('extra_resets', 'open_drain', 'feedback', 'static')))


def prepare_native_reset(design, replay, directory, eda_shell):
    """Automatic best-effort discovery; infrastructure failures still propagate."""
    directory = Path(directory)
    identity = fingerprint({'design':design.record(), 'replay':replay, 'implementation':digest(Path(__file__))})
    if (directory/'unsupported.json').exists():
        saved = json.loads((directory/'unsupported.json').read_text())
        if saved.get('fingerprint') != identity: raise ValueError('native initialization support inputs changed')
        return None, None
    try:
        if not native_reset_environment(design, replay):
            raise UnsupportedNativeReset('native reset snapshot requires an independent single-clock environment')
        state = generate(design, {k:v for k,v in replay.items() if k != 'environment'},
                         directory, '', eda_shell, native_reset=True)
    except UnsupportedNativeReset as error:
        directory.mkdir(parents=True, exist_ok=True)
        save(directory/'unsupported.json', dict(policy=NATIVE_RESET_POLICY, reason=str(error), fingerprint=identity))
        return None, None
    path = directory/'snapshot.json'
    return state, {'policy':NATIVE_RESET_POLICY, 'file':str(path.resolve()), 'sha256':digest(path)}


def verify_native_reset(job, design, replay):
    state, record = job.get('resetSnapshotState'), job.get('resetSnapshotRecord')
    if not state and not record: return  # historical jobs retain their original semantics
    if not state or not record or record.get('policy') != NATIVE_RESET_POLICY or not native_reset_environment(design, replay):
        raise ValueError('invalid native reset state provenance')
    path = Path(record['file'])
    if digest(path) != record['sha256']: raise ValueError('native reset record changed')
    saved = json.loads(path.read_text())
    if (saved.get('policy') != NATIVE_RESET_POLICY or saved.get('design') != design.record() or
            saved.get('idle') != replay['idle'] or saved.get('reset_cycles') != replay['reset_cycles'] or
            saved.get('implementation') != digest(Path(__file__)) or
            any(digest(Path(p)) != h for p,h in saved['artifact_sha256'].items()) or
            (path.parent/'initial.state').read_text() != state or job.get('initialState')):
        raise ValueError('native reset state or inputs changed')


def render_probe(design, replay, ports, *, power_on=False):
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
        f");\nalways #5 {design.clock} = ~{design.clock};\ninitial begin\n"+
        ("#1;\n" if power_on else
         f"repeat ({replay['reset_cycles']}) @(posedge {design.clock});\n"
         f"@(negedge {design.clock});\n{design.reset} = 1'b{int(design.reset_active_low)};\n#1;\n")+
        displays+'\n$display("RVPROBE_INIT_DONE");\n$finish;\nend\nendmodule\n')


def parse_state(log, ports, *, allow_unknown=False):
    expected = {p["name"]: p["width"] for p in ports}
    values = {}
    for name, bits in re.findall(r"(?m)^RVPROBE_INIT (\S+) ([01xXzZ]+)\s*$", log):
        if name in values or name not in expected or len(bits) != expected[name]:
            raise ValueError("duplicate, unexpected or wrong-width snapshot state")
        if re.search(r"[xXzZ]", bits) and not allow_unknown:
            raise ValueError(f"unknown post-reset state: {name}; do not zero-fill missing initialization")
        values[name] = (f"{len(bits)}'b{bits.lower().replace('z', 'x')}" if re.search(r"[xXzZ]", bits)
                        else f"{len(bits)}'h{int(bits, 2):x}")
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


def generate(design, replay, directory, prior, eda_shell, *, power_on=False, native_reset=False):
    if replay.get('environment'):
        raise ValueError('event environment requires native formal reset, not single-clock snapshot')
    directory = Path(directory).resolve()
    if power_on and native_reset: raise ValueError('choose one snapshot sampling point')
    inputs = {"policy": NATIVE_RESET_POLICY if native_reset else 'rtl-power-on-diagnostic-v1' if power_on else POLICY,
              "design": design.record(), "reset_cycles": replay["reset_cycles"],
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
        if power_on or native_reset: raise UnsupportedNativeReset('snapshot discovery reported unsupported state semantics')
        raise ValueError("snapshot discovery reported unsupported state semantics")
    tree = json.loads(tree_path.read_text())
    try:
        ports = state_ports(tree, design)
    except ValueError as error:
        if power_on or native_reset: raise UnsupportedNativeReset(str(error)) from error
        raise
    if power_on or native_reset:
        initial = initialized_ports(tree, design, ports)
        if power_on:
            ports = initial
        else:
            # Capture the complete DUT post-reset state, not only initialized
            # RAM. Cumulative JG reset overlays values; it does not simulate
            # reset starting from the supplied power-on file.
            loop_names = {'dut.'+n['verilogName'] for n in nodes(tree)
                          if n.get('type') == 'VAR' and n.get('isUsedLoopIdx') and n.get('verilogName')}
            ports = [p for p in ports if p['name'].split('[')[0] not in loop_names]
    save(directory / "storage.json", ports)
    probe = directory / "probe.sv"
    probe.write_text(render_probe(design, replay, ports, power_on=power_on))
    compile_args = ["vcs", "-full64", "-sverilog", "-timescale=1ns/1ps", "-top", "rvprobe_initial_state_probe", "-o", "simv",
        *["+incdir+"+str(p) for p in design.include_dirs], *map(str, design.sources), str(probe)]
    execute([str(eda_shell), "-c", shlex.join(compile_args)], "snapshot-compile")
    log = execute([str(eda_shell), "-c", "./simv"], "snapshot-simulate")
    values = parse_state(log, ports, allow_unknown=power_on or native_reset)
    validate_prior(prior, values)
    if power_on or native_reset:
        values = {n:v for n,v in values.items() if not re.fullmatch(r"\d+'bx+", v)}
        if not values: raise UnsupportedNativeReset('explicit initial state contains no known bits')
    state = "".join(f"{name}\n{value}\n" for name,value in sorted(values.items()))
    (directory / "initial.state").write_text(state)
    save(directory / "snapshot.json", {"fingerprint": key, **inputs, "register_words": len(values),
        "artifact_sha256": {str(p):digest(p) for p in [tree_path, probe, directory/"storage.json",
            directory/"snapshot-discover.log", directory/"snapshot-compile.log", directory/"snapshot-simulate.log",
            directory/"initial.state"]}})
    return state
