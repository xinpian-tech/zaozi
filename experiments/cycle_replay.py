#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Manifest-generated, cycle-exact UVM replay of independent formal witnesses.

No DUT implementation, protocol handshake inference, or benchmark answer lives
here. The input schedule is data, and the same compiled bench measures every arm.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import random
import re
import shlex
import shutil
import subprocess

from sequence_framework import ROOT, Design, load_design
import urg_score

CONTRACT = "cycle-replay-v1"
METRICS = "line+cond+tgl+branch"


def save(path: Path, value) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_config(path: Path) -> tuple[Design, dict]:
    raw = json.loads(path.read_text())
    if raw.get("version") != 1 or raw.get("contract") != CONTRACT:
        raise ValueError("replay config must use version 1 and cycle-replay-v1")
    design = load_design((path.parent / raw["design"]).resolve())
    for key, maximum in (("reset_cycles", 100), ("drain_cycles", 10000)):
        if type(raw[key]) is not int or not 1 <= raw[key] <= maximum:
            raise ValueError(f"invalid {key}")
    check_drive(design, raw["idle"])
    baseline = raw["baseline"]
    for key in ("samples", "active_cycles", "idle_cycles"):
        if type(baseline[key]) is not int or not 1 <= baseline[key] <= 100000:
            raise ValueError(f"invalid baseline {key}")
    if baseline["samples"] * (baseline["active_cycles"] + baseline["idle_cycles"]) > 2000000:
        raise ValueError("baseline exceeds two million cycles")
    if type(baseline["seed"]) is not int:
        raise ValueError("seed must be an integer")
    names = set(design.drive_names)
    if set(baseline["random_ports"]) | set(baseline["asserted"]) != names:
        raise ValueError("baseline random_ports and asserted must cover every drive port")
    if set(baseline["random_ports"]) & set(baseline["asserted"]):
        raise ValueError("baseline random and asserted ports must not overlap")
    check_drive(design, {**raw["idle"], **baseline["asserted"]})
    return design, raw


def check_drive(design: Design, values: dict) -> None:
    if set(values) != set(design.drive_names):
        raise ValueError("stimulus must contain exactly the manifest drive ports")
    for port in design.data_ports:
        if port.direction == "input":
            value = values[port.name]
            if type(value) is not int or not 0 <= value < (1 << port.width):
                raise ValueError(f"out-of-width drive value for {port.name}")


def frame(kind, drive, *, expected=None, segment=-1, beat=-1):
    return {"kind": kind, "drive": dict(drive), "expected": expected or {},
            "segment": segment, "beat": beat}


def baseline_frames(design: Design, config: dict) -> list[dict]:
    spec = config["baseline"]
    rng = random.Random(spec["seed"])
    widths = {p.name: p.width for p in design.data_ports}
    frames = [frame("reset", config["idle"]) for _ in range(config["reset_cycles"])]
    for _ in range(spec["samples"]):
        # Explicit seed + getrandbits: independent of simulator randomization and model output.
        drive = {name: rng.getrandbits(widths[name]) for name in spec["random_ports"]}
        drive.update(spec["asserted"])
        frames += [frame("baseline", drive) for _ in range(spec["active_cycles"])]
        quiet = {**drive, **{name: config["idle"][name] for name in spec["asserted"]}}
        frames += [frame("baseline", quiet) for _ in range(spec["idle_cycles"])]
    return frames


def read_vcd(path: Path, clock="clock") -> list[dict[str, tuple[int, int]]]:
    """JG pre-edge samples, preserving known-bit masks (never turn X into evidence)."""
    names, scope, current, rows = {}, [], {}, []
    definitions, previous_clock, clock_id = True, 0, None
    for line in path.read_text().splitlines():
        line = line.strip()
        words = line.split()
        if not words:
            continue
        if definitions:
            if words[0] == "$scope":
                scope.append(words[2])
            elif words[0] == "$upscope":
                scope.pop()
            elif words[0] == "$var":
                name = "/".join(scope[1:] + [words[4]])
                names.setdefault(words[3], []).append((name, int(words[2])))
                if name == clock:
                    clock_id = words[3]
            elif words[0] == "$enddefinitions":
                definitions = False
            continue
        if line[0] in "$#":
            continue
        if line[0] in "bB":
            bits, symbol = line[1:].split()
        elif line[0].lower() in "01xz":
            bits, symbol = line[0], line[1:].strip()
        else:
            continue
        if symbol not in names:
            continue
        width = names[symbol][0][1]
        bits = bits.lower().rjust(width, bits[0] if bits[0] in "xz" else "0")
        value = int("".join("1" if bit == "1" else "0" for bit in bits), 2)
        mask = int("".join("1" if bit in "01" else "0" for bit in bits), 2)
        if symbol == clock_id:
            if mask != 1:
                raise ValueError("unknown formal clock")
            if value == 1 and previous_clock == 0:
                rows.append({name: current.get(key, (0, 0))
                             for key, aliases in names.items() for name, _ in aliases})
            previous_clock = value
        current[symbol] = value, mask
    if clock_id is None or not rows:
        raise ValueError("VCD has no sampled formal clock")
    return rows


def witness_frames(design: Design, config: dict, row: dict, segment: int) -> list[dict]:
    stimulus = Path(row["stimulusFile"])
    witness = Path(row.get("witnessFile", stimulus.parent / "jg/witness.vcd"))
    beats = json.loads(stimulus.read_text())
    trace = read_vcd(witness)
    if len(beats) != len(trace) or len(beats) != row["cycles"] or not beats:
        raise ValueError("witness / stimulus / reported cycle counts disagree")
    frames = [frame("reset", config["idle"], segment=segment)
              for _ in range(config["reset_cycles"])]
    for index, (beat, sample) in enumerate(zip(beats, trace)):
        if not isinstance(beat, dict) or any(type(value) is not int and
                not (isinstance(value, str) and re.fullmatch(r"[0-9]+", value)) for value in beat.values()):
            raise ValueError("stimulus values must be integer bit patterns, not coerced floats or booleans")
        drive = {key: int(value) for key, value in beat.items()}
        check_drive(design, drive)
        if sample.get("reset") != (0, 1):
            raise ValueError("witness is not entirely post-reset")
        for name, value in drive.items():
            # Inputs optimized out of the cone are allowed; known inputs must agree.
            expected, mask = sample.get(name, sample.get("dut/" + name, (0, 0)))
            if value & mask != expected & mask:
                raise ValueError(f"stimulus disagrees with witness: {name} beat {index}")
        observed = {}
        for port in design.data_ports:
            if port.direction == "output":
                value, mask = sample.get(port.name, sample.get("dut/" + port.name, (0, 0)))
                if mask:
                    observed[port.name] = [value, mask]
        frames.append(frame("witness", drive, expected=observed, segment=segment, beat=index))
    # Drain is explicit protocol configuration, not part of the formal witness.
    # Preserve data; deassert configured request controls instead of changing the operation.
    quiet = {**drive, **{name: config["idle"][name] for name in config["baseline"]["asserted"]}}
    frames += [frame("drain", quiet, segment=segment) for _ in range(config["drain_cycles"])]
    return frames


def render_bench(design: Design) -> str:
    """One UVM transport template, generated entirely from interface metadata."""
    inputs = [p for p in design.data_ports if p.direction == "input"]
    outputs = [p for p in design.data_ports if p.direction == "output"]
    decl = lambda p: f"logic [{p.width - 1}:0] {p.name};"
    fields = "\n".join(decl(p) for p in design.data_ports)
    item_fields = "\n".join(decl(p) for p in inputs)
    checks = "\n".join(f"logic [{p.width-1}:0] expected_{p.name}, mask_{p.name};" for p in outputs)
    writes = "\n".join(f"vif.{p.name} = txn.{p.name};" for p in inputs)
    compares = "\n".join(
        f'if ((vif.rvprobe_sample.{p.name} & txn.mask_{p.name}) !== (txn.expected_{p.name} & txn.mask_{p.name})) '
        f'`uvm_fatal("WITNESS", $sformatf("row %0d output {p.name} disagrees with formal pre-edge sample", txn.ordinal))'
        for p in outputs)
    print_format = " ".join(["%h"] * len(inputs) + ["%b"] * len(outputs))
    print_values = "".join(f", vif.rvprobe_sample.{p.name}" for p in inputs + outputs)
    scan_fields = ["txn.reset_active"] + [f"txn.{p.name}" for p in inputs]
    for p in outputs:
        scan_fields += [f"txn.expected_{p.name}", f"txn.mask_{p.name}"]
    scan = " ".join("%h" for _ in scan_fields)
    reset_on = "1'b0" if design.reset_active_low else "1'b1"
    reset_off = "1'b1" if design.reset_active_low else "1'b0"
    params = " #(" + ", ".join(f".{name}({value})" for name, value in design.parameters) + ")" if design.parameters else ""
    connections = ", ".join(f".{p.name}(vif.{p.name})" for p in design.ports)
    return f'''// Generated interface + transport only. No DUT behavior or coverage answers.
`timescale 1ns/1ps
`include "uvm_macros.svh"
interface rvprobe_cycle_if;
  logic {design.clock} = 0;
  logic {design.reset} = {reset_on};
  {fields}
  always #5 {design.clock} = ~{design.clock};
  clocking rvprobe_sample @(posedge {design.clock});
    default input #1step;
    input {', '.join([design.reset] + [p.name for p in design.data_ports])};
  endclocking
endinterface
package rvprobe_cycle_pkg;
import uvm_pkg::*;
class rvprobe_cycle_item extends uvm_sequence_item;
  `uvm_object_utils(rvprobe_cycle_item)
  bit reset_active;
  int ordinal;
  {item_fields}
  {checks}
  function new(string name="rvprobe_cycle_item"); super.new(name); endfunction
endclass
class rvprobe_cycle_driver extends uvm_driver #(rvprobe_cycle_item);
  `uvm_component_utils(rvprobe_cycle_driver)
  virtual rvprobe_cycle_if vif;
  int cycle = 0;
  function new(string name, uvm_component parent); super.new(name,parent); endfunction
  function void build_phase(uvm_phase phase);
    super.build_phase(phase);
    if (!uvm_config_db#(virtual rvprobe_cycle_if)::get(this,"","vif",vif))
      `uvm_fatal("CONFIG", "missing interface")
  endfunction
  task run_phase(uvm_phase phase);
    rvprobe_cycle_item txn;
    forever begin
      seq_item_port.get_next_item(txn);
      @(negedge vif.{design.clock});
      vif.{design.reset} = txn.reset_active ? {reset_on} : {reset_off};
      {writes}
      @(vif.rvprobe_sample); // #1step inputs: before both blocking and NBA edge updates
      {compares}
      $display("RVPROBE_SAMPLE %0d %0t %b {print_format}", txn.ordinal, $time, vif.rvprobe_sample.{design.reset}{print_values});
      cycle++;
      seq_item_port.item_done(); // no done/ready wait and no per-item extra clock
    end
  endtask
endclass
class rvprobe_cycle_sequence extends uvm_sequence #(rvprobe_cycle_item);
  `uvm_object_utils(rvprobe_cycle_sequence)
  function new(string name="rvprobe_cycle_sequence"); super.new(name); endfunction
  task body();
    string path;
    int fd, count, ordinal = 0;
    rvprobe_cycle_item txn;
    if (!$value$plusargs("RVPROBE_SCHEDULE=%s",path)) `uvm_fatal("CONFIG","missing schedule")
    fd = $fopen(path,"r");
    if (!fd) `uvm_fatal("CONFIG","cannot open schedule")
    forever begin
      txn = rvprobe_cycle_item::type_id::create("txn");
      count = $fscanf(fd,"{scan}\\n", {', '.join(scan_fields)});
      if (count == -1) break;
      if (count != {len(scan_fields)}) `uvm_fatal("SCHEDULE","malformed schedule")
      txn.ordinal = ordinal++;
      start_item(txn);
      finish_item(txn);
    end
    $fclose(fd);
    #1; // finish only after the final edge's NBA updates and coverage sampling
    $display("RVPROBE_REPLAY_PASS %0d", ordinal);
  endtask
endclass
class rvprobe_cycle_test extends uvm_test;
  `uvm_component_utils(rvprobe_cycle_test)
  uvm_sequencer #(rvprobe_cycle_item) sequencer;
  rvprobe_cycle_driver driver;
  function new(string name, uvm_component parent); super.new(name,parent); endfunction
  function void build_phase(uvm_phase phase);
    super.build_phase(phase);
    sequencer = new("sequencer",this);
    driver = rvprobe_cycle_driver::type_id::create("driver",this);
  endfunction
  function void connect_phase(uvm_phase phase); driver.seq_item_port.connect(sequencer.seq_item_export); endfunction
  task run_phase(uvm_phase phase);
    rvprobe_cycle_sequence sequence_;
    phase.raise_objection(this);
    sequence_ = rvprobe_cycle_sequence::type_id::create("sequence_");
    sequence_.start(sequencer);
    phase.drop_objection(this);
  endtask
endclass
endpackage
module rvprobe_cycle_top;
  import uvm_pkg::*;
  import rvprobe_cycle_pkg::*;
  rvprobe_cycle_if vif();
  {design.top}{params} dut({connections});
  initial begin
    uvm_config_db#(virtual rvprobe_cycle_if)::set(null,"*","vif",vif);
    run_test("rvprobe_cycle_test");
  end
  initial begin #100000000; $fatal(1,"replay timeout"); end
endmodule
'''


def preflight(design_path: Path, directory: Path) -> None:
    """Validate the manifest against CIRCT's elaborated ports in the development environment."""
    command = ["nix", "develop", ".", "-c", "python3", "-c",
               "import sys; from pathlib import Path; sys.path.insert(0, 'experiments'); "
               "from sequence_framework import load_design, check_interface; "
               "check_interface(load_design(Path(sys.argv[1])), Path(sys.argv[2]))",
               str(design_path), str(directory / "interface")]
    with (directory / "interface.log").open("w") as log:
        subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)


class Replay:
    def __init__(self, design: Design, config: dict, root: Path, eda_shell: Path):
        self.design, self.config, self.root = design, config, root.resolve()
        self.eda_shell = eda_shell.resolve()
        self.build = self.root / "build"

    def command(self, cwd, command, log, timeout=900):
        with (self.root / "commands.jsonl").open("a") as stream:
            stream.write(json.dumps({"cwd": str(cwd), "command": command, "log": str(log)}) + "\n")
        with log.open("w") as stream:
            env = {key: value for key, value in os.environ.items() if key not in (
                "RVPROBE_LLM_API_KEY", "RVPROBE_LLM_BASE_URL", "OPENAI_API_KEY", "OPENAI_BASE_URL")}
            subprocess.run([str(self.eda_shell), "-c", shlex.join(command)], cwd=cwd,
                           env=env, stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=timeout)

    def compile(self):
        self.build.mkdir(parents=True, exist_ok=False)
        bench = self.build / "rvprobe_cycle.sv"
        bench.write_text(render_bench(self.design))
        command = ["vcs", "+vcs+lic+wait", "-sverilog", "-ntb_opts", "uvm", "-cm", METRICS,
                   "-timescale=1ns/1ps", "+verilog2001ext+.v", "-top", "rvprobe_cycle_top"]
        command += ["+incdir+" + str(p) for p in self.design.include_dirs]
        command += [str(p) for p in self.design.sources] + [str(bench), "-o", "simv"]
        self.command(self.build, command, self.build / "build.log")
        save(self.build / "manifest.json", {"design": self.design.record(), "bench_sha256": digest(bench),
                                           "contract": CONTRACT})

    def simulate(self, name: str, frames: list[dict]) -> dict:
        compiled = json.loads((self.build / "manifest.json").read_text())
        if compiled["design"] != self.design.record():
            raise ValueError("design inputs changed since VCS compilation")
        if compiled["bench_sha256"] != digest(self.build / "rvprobe_cycle.sv"):
            raise ValueError("replay bench changed since VCS compilation")
        validate_schedule(self.design, frames, self.config["reset_cycles"])
        directory = self.root / name
        directory.mkdir(exist_ok=False)
        save(directory / "schedule.json", frames)
        inputs = [p for p in self.design.data_ports if p.direction == "input"]
        outputs = [p for p in self.design.data_ports if p.direction == "output"]
        lines = []
        for row in frames:
            check_drive(self.design, row["drive"])
            values = [int(row["kind"] == "reset")] + [row["drive"][p.name] for p in inputs]
            for p in outputs:
                values.extend(row["expected"].get(p.name, [0, 0]))
            lines.append(" ".join(format(value, "x") for value in values))
        schedule = directory / "schedule.txt"
        schedule.write_text("\n".join(lines) + "\n")
        db = directory / "simv.vdb"
        # A runtime -cm_dir only receives test data; copy the immutable compiled model too.
        shutil.copytree(self.build / "simv.vdb", db)
        self.command(self.build, [str(self.build / "simv"), "+vcs+lic+wait",
                     "+ntb_random_seed=1", "+UVM_VERBOSITY=UVM_LOW",
                     "+RVPROBE_SCHEDULE=" + str(schedule), "-cm", METRICS, "-cm_dir", str(db)],
                     directory / "sim.log")
        log = (directory / "sim.log").read_text()
        validation = validate_samples(self.design, frames, log)
        save(directory / "replay-validation.json", validation)
        self.command(self.build, ["urg", "-dir", str(db), "-metric", METRICS,
                     "-format", "text", "-report", str(directory / "urgReport")], directory / "urg.log")
        modinfo = directory / "urgReport/modinfo.txt"
        if not modinfo.is_file():
            raise ValueError(f"URG produced no module report; inspect {directory / 'urg.log'}")
        parsed = urg_score.parse(str(modinfo))
        if urg_score.verify(parsed):
            raise ValueError("coverage parser disagrees with URG")
        percent, score, bins = urg_score.score(parsed, [self.design.top])
        if "line" not in percent or score is None:
            raise ValueError("replay requires measurable DUT line coverage")
        from sequence_experiment import residual
        result = {"score": score, "percent": percent, "bins": bins,
                  "requested_metrics": list(urg_score.DEFAULT_METRICS), "scored_metrics": list(percent),
                  "uncovered": residual(modinfo, self.design.top), "modinfo": str(modinfo),
                  "replay": validation, "coverage_database": str(db)}
        save(directory / "coverage.json", result)
        return result


def validate_schedule(design: Design, frames: list[dict], reset_cycles: int) -> None:
    outputs = {port.name: port.width for port in design.data_ports if port.direction == "output"}
    last_segment, next_beat = None, 0
    for index, row in enumerate(frames):
        if row["kind"] not in ("baseline", "reset", "witness", "drain"):
            raise ValueError("unknown schedule frame kind")
        check_drive(design, row["drive"])
        for name, (value, mask) in row["expected"].items():
            if name not in outputs or any(type(v) is not int or not 0 <= v < (1 << outputs[name])
                                          for v in (value, mask)):
                raise ValueError("invalid output expectation/mask")
        if row["kind"] != "witness":
            last_segment = None
            continue
        if row["segment"] != last_segment:
            prefix = frames[max(0, index-reset_cycles):index]
            if len(prefix) != reset_cycles or any(p["kind"] != "reset" or p["segment"] != row["segment"] for p in prefix):
                raise ValueError("each independent witness must have its own reset preamble")
            next_beat = 0
        if row["beat"] != next_beat:
            raise ValueError("witness beats must be contiguous and start at zero")
        next_beat += 1
        last_segment = row["segment"]


def validate_samples(design: Design, frames: list[dict], log: str) -> dict:
    if any(not re.search(rf"UVM_{kind}\s*:\s*0\b", log) for kind in ("ERROR", "FATAL")):
        raise ValueError("UVM did not finish without errors")
    if not re.search(rf"^RVPROBE_REPLAY_PASS {len(frames)}$", log, re.M):
        raise ValueError("missing or incomplete replay completion")
    samples = re.findall(r"^RVPROBE_SAMPLE (.*)$", log, re.M)
    if len(samples) != len(frames):
        raise ValueError("sample count differs from schedule")
    prior = None
    for index, (row, sample) in enumerate(zip(frames, samples)):
        ordinal, stamp, reset, *values = sample.split()
        active = row["kind"] == "reset"
        want_reset = int(not active if design.reset_active_low else active)
        if int(ordinal) != index or reset != str(want_reset):
            raise ValueError("replay order/reset differs from schedule")
        # Bench time precision is 1 ps, clock period is 10 ns.
        if prior is not None and int(stamp) - prior != 10000:
            raise ValueError("driver inserted or dropped a clock cycle")
        prior = int(stamp)
        if len(values) != len(design.data_ports):
            raise ValueError("sample columns differ from manifest")
        for port, value in zip(design.drive_names, values[:len(design.drive_names)]):
            if not re.fullmatch(r"[0-9a-fA-F]+", value) or int(value, 16) != row["drive"][port]:
                raise ValueError(f"sampled drive differs: row {index}, port {port}")
        outputs = [p for p in design.data_ports if p.direction == "output"]
        for port, value in zip(outputs, values[len(design.drive_names):]):
            if not re.fullmatch(r"[01xXzZ]+", value):
                raise ValueError("invalid sampled output")
            actual = int(re.sub("[xXzZ]", "0", value), 2)
            known = int("".join("0" if bit.lower() in "xz" else "1" for bit in value), 2)
            expected, mask = row["expected"].get(port.name, [0, 0])
            if known & mask != mask or actual & mask != expected & mask:
                raise ValueError(f"sampled output disagrees: row {index}, port {port.name}")
    return {"contract": CONTRACT, "passed": True, "sampled_cycles": len(frames),
            "witness_cycles": sum(row["kind"] == "witness" for row in frames),
            "output_checks": sum(len(row["expected"]) for row in frames),
            "reset_per_witness": True, "sample_phase": "clocking input #1step before posedge",
            "clock_period_ns": 10, "arithmetic_correctness_checked": False,
            "limitation": "known formal output bits only; no whole-state equivalence or independent functional proof"}
