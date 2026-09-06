#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Design-neutral Scala bindings and per-run verification UTs for external RTL.

Port declarations are explicit, versioned experiment inputs. They describe an
interface, not a reimplementation of the DUT. The only executable scenario code
comes from this run's intent expressions; no stdlib benchmark UT is imported.
Generated Scala must be reviewed or run in an appropriate execution sandbox.
JSON validation and scalac type checking are not a security sandbox.
"""
from __future__ import annotations

from dataclasses import dataclass, replace
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DESIGN = ROOT / "experiments/designs/alu.json"
CONTRACT = "runtime-ut-v2"
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*\Z")
LABEL = re.compile(r"[a-z][a-z0-9_]{0,79}\Z")


def identifier(value: object, description: str) -> str:
    if not isinstance(value, str) or not IDENTIFIER.fullmatch(value):
        raise ValueError(f"{description} must be a simple identifier")
    return value


def scala_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=True)


@dataclass(frozen=True)
class Port:
    name: str
    direction: str
    width: int
    kind: str = "bits"

    @property
    def scala_type(self) -> str:
        return {"clock": "Clock()", "bool": "Bool()", "bits": f"Bits({self.width})",
                "sint": f"SInt({self.width})"}[self.kind]


@dataclass(frozen=True)
class Design:
    top: str
    sources: tuple[Path, ...]
    include_dirs: tuple[Path, ...]
    ports: tuple[Port, ...]
    clock: str
    reset: str
    reset_active_low: bool
    sequence_name: str
    item_type: str
    context: str
    parameters: tuple[tuple[str, int], ...] = ()

    @property
    def data_ports(self) -> tuple[Port, ...]:
        return tuple(p for p in self.ports if p.name not in (self.clock, self.reset))

    @property
    def drive_names(self) -> tuple[str, ...]:
        return tuple(p.name for p in self.data_ports if p.direction == "input")

    def with_rtl(self, path: Path) -> Design:
        if len(self.sources) != 1:
            raise ValueError("--rtl replacement requires a single-source design; edit the design manifest otherwise")
        path = path.resolve()
        if not path.is_file():
            raise ValueError(f"RTL does not exist: {path}")
        return replace(self, sources=(path,))

    def record(self) -> dict:
        return {
            "version": 1, "top": self.top,
            "sources": [{"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                        for p in self.sources],
            "include_dirs": [str(p) for p in self.include_dirs],
            "include_files": [{"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                              for root in self.include_dirs for p in sorted(root.rglob("*")) if p.is_file()],
            "ports": [vars(p) for p in self.ports],
            "clock": self.clock,
            "reset": {"port": self.reset, "active_low": self.reset_active_low},
            "sequence": {"name": self.sequence_name, "item_type": self.item_type},
            "context": self.context, "parameters": dict(self.parameters),
        }


def load_design(path: Path = DEFAULT_DESIGN) -> Design:
    path = path.resolve()
    raw = json.loads(path.read_text())
    if raw.get("version") != 1:
        raise ValueError("design manifest must use version 1")
    ports = []
    for item in raw["ports"]:
        name = identifier(item["name"], "port name")
        direction, width, kind = item["direction"], item["width"], item.get("kind", "bits")
        if direction not in ("input", "output"):
            raise ValueError("only input/output ports are supported; split inout into explicit signals first")
        if type(width) is not int or not 1 <= width <= 65536:
            raise ValueError("port width must be a positive integer no greater than 65536")
        if kind not in ("bits", "bool", "clock", "sint") or (kind in ("bool", "clock") and width != 1):
            raise ValueError("invalid port kind or scalar width")
        ports.append(Port(name, direction, width, kind))
    names = [p.name for p in ports]
    if len(set(names)) != len(names):
        raise ValueError("duplicate port name")
    clock = identifier(raw["clock"], "clock port")
    reset = identifier(raw["reset"]["port"], "reset port")
    by_name = {p.name: p for p in ports}
    if clock == reset or clock not in by_name or reset not in by_name:
        raise ValueError("distinct clock and reset ports must exist")
    if by_name[clock] != Port(clock, "input", 1, "clock"):
        raise ValueError("clock must be a one-bit input of kind clock")
    if by_name[reset] != Port(reset, "input", 1, "bool"):
        raise ValueError("reset must be a one-bit input of kind bool")
    if type(raw["reset"]["active_low"]) is not bool:
        raise ValueError("reset active_low must be a boolean")
    for port in ports:
        if port.name not in (clock, reset) and (port.name in ("clock", "reset") or port.kind == "clock"):
            raise ValueError("only one clock/reset domain is supported; clock and reset are reserved UT names")
    sources = tuple((path.parent / p).resolve() for p in raw["sources"])
    include_dirs = tuple((path.parent / p).resolve() for p in raw.get("include_dirs", []))
    if not sources or len(set(sources)) != len(sources) or any(not p.is_file() for p in sources):
        raise ValueError("sources must be a nonempty list of existing, distinct RTL files")
    if any(not p.is_dir() for p in include_dirs):
        raise ValueError("include directories must exist")
    # The current JgModel has one include root. Reject instead of silently dropping roots.
    if len(include_dirs) > 1:
        raise ValueError("the current JasperGold adapter supports one include directory")
    params = tuple(raw.get("parameters", {}).items())
    for name, value in params:
        identifier(name, "parameter name")
        if type(value) is not int:
            raise ValueError("only integer Verilog parameters are supported")
    sequence = raw["sequence"]
    return Design(identifier(raw["top"], "top"), sources, include_dirs, tuple(ports), clock, reset,
                  raw["reset"]["active_low"], identifier(sequence["name"], "sequence name"),
                  identifier(sequence["item_type"], "sequence item type"), str(raw.get("context", "")), params)


def parse_response(text: str) -> dict:
    """Validate the envelope, leaving actual intent typing to scalac."""
    try:
        raw = json.loads(text)
    except json.JSONDecodeError as error:
        raise ValueError(f"response must be intent JSON: {error.msg}") from error
    if not isinstance(raw, dict) or set(raw) != {"intents", "proofObligations"}:
        raise ValueError("response must contain exactly intents and proofObligations")
    seen = set()
    for key, field in (("intents", "expression"), ("proofObligations", "reason")):
        if not isinstance(raw[key], list) or len(raw[key]) > 64:
            raise ValueError(f"{key} must be a list of at most 64 records")
        for item in raw[key]:
            if not isinstance(item, dict) or set(item) != {"label", field}:
                raise ValueError(f"{key} records require exactly label and {field}")
            label = item["label"]
            if not isinstance(label, str) or not LABEL.fullmatch(label) or label in seen:
                raise ValueError("labels must be unique safe snake_case identifiers")
            seen.add(label)
            if not isinstance(item[field], str) or not item[field].strip() or len(item[field]) > 20000:
                raise ValueError(f"{field} must be a nonempty string of at most 20000 characters")
    return raw


IMPORTS = """// Generated for this run. No DUT behavior or historical scenario is implemented here.
import me.jiuyang.utlib.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
"""


def render_binding(design: Design) -> str:
    def fields(ports):
        return "\n".join(f"  val `{p.name}` = {'Flipped' if p.direction == 'input' else 'Aligned'}({p.scala_type})"
                         for p in ports)
    params = ", ".join(f"`{name}`: BigInt = BigInt({scala_string(str(value))})" for name, value in design.parameters)
    return IMPORTS + f"""
case class RunParameter() extends Parameter
given upickle.default.ReadWriter[RunParameter] = upickle.default.macroRW
class RunLayers(parameter: RunParameter) extends LayerInterface(parameter):
  def layers = Seq.empty
class RunProbe(parameter: RunParameter) extends DVBundle[RunParameter, RunLayers](parameter)
class DesignIO(parameter: RunParameter) extends HWBundle(parameter):
{fields(design.ports)}
case class ImportedParameters({params}) extends VerilogParameter

@generator
object ImportedDut extends VerilogWrapper[RunParameter, RunLayers, DesignIO, RunProbe, ImportedParameters]:
  def verilogModuleName(parameter: RunParameter) = {scala_string(design.top)}
  def verilogParameter(parameter: RunParameter) = ImportedParameters()
  override def moduleName(parameter: RunParameter): String = verilogModuleName(parameter)

class RunIO(parameter: RunParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
{fields(design.data_ports)}
"""


def render_program(design: Design, response: dict, time_limit: str = "120s") -> str:
    """One shared imported binding, one newly generated UT per intent, one fixed runner."""
    parse_response(json.dumps(response))
    connections = [f"    dut.io.`{design.clock}` := io.clock",
                   f"    dut.io.`{design.reset}` := {'!' if design.reset_active_low else ''}io.reset.asBool"]
    for port in design.data_ports:
        connections.append(f"    dut.io.`{port.name}` := io.`{port.name}`" if port.direction == "input" else
                           f"    io.`{port.name}` := dut.io.`{port.name}`")
    parts = [IMPORTS]
    for index, intent in enumerate(response["intents"]):
        expression = "\n".join("      " + line for line in intent["expression"].strip().splitlines())
        parts.append(f"""
@generator
object RunIntent{index} extends Generator[RunParameter, RunLayers, RunIO, RunProbe] with UT[RunParameter, RunIO]:
  override def moduleName(parameter: RunParameter): String = "RunIntent{index}"
  def architecture(parameter: RunParameter) =
    val io = summon[Interface[RunIO]]
    val dut = ImportedDut.instantiate(parameter)
{chr(10).join(connections)}
    Assume((!io.reset.asBool).I, "rst_low")
    given ClockEvent = posedge(io.clock)
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)
    Gen((
{expression}
    ), "rvprobe_generated_{index}")
""")
    sources = ", ".join(f"os.Path({scala_string(str(p))})" for p in design.sources)
    include = f"Some(os.Path({scala_string(str(design.include_dirs[0]))}))" if design.include_dirs else "None"
    rows = []
    for index, intent in enumerate(response["intents"]):
        rows.append(f"""    {{
      val label = {scala_string(intent['label'])}
      val dir = outDir / label
      val generator = UTGenerator(RunIntent{index}, parameter, dir)
      generator.saveAbi()
      val began = System.currentTimeMillis()
      val model = JasperGold.lower(RunIntent{index}, parameter, dir / "lowered", rtl,
        generationLabels = Set("rvprobe_generated_{index}"), include = {include})
      JasperGold.generate(model, dir / "jg", timeLimit = {scala_string(time_limit)}) match
        case GenerateOutcome.Generated(trace) =>
          val stimulus = AbstractStimulus.fromTrace(trace, generator.abi.spec)
          val beats = ujson.Arr(stimulus.beats.map(b =>
            ujson.Obj.from(b.values.toSeq.map((name, value) => name -> ujson.Str(value.toString))))*)
          os.write.over(dir / "stimulus.json", ujson.write(beats, indent = 2))
          val individual = UvmSequence({scala_string(design.sequence_name)}, {scala_string(design.item_type)})
            .write(stimulus, dir / {scala_string(design.sequence_name + '.sv')})
          rows += ujson.Obj("label" -> label, "status" -> "generated", "engine" -> "jaspergold",
            "ms" -> (System.currentTimeMillis() - began), "cycles" -> trace.cycles,
            "sequenceFile" -> individual.toString, "stimulusFile" -> (dir / "stimulus.json").toString,
            "witnessFile" -> (dir / "jg" / "witness.vcd").toString)
        case GenerateOutcome.Infeasible =>
          rows += ujson.Obj("label" -> label, "status" -> "infeasible", "engine" -> "jaspergold",
            "ms" -> (System.currentTimeMillis() - began))
        case GenerateOutcome.Unknown(detail) =>
          rows += ujson.Obj("label" -> label, "status" -> "unknown", "detail" -> detail,
            "engine" -> "jaspergold", "ms" -> (System.currentTimeMillis() - began))
    }}""")
    proofs = scala_string(json.dumps(response["proofObligations"]))
    parts.append(f"""
object Generated extends UTExperiment:
  def run(outDir: os.Path): ujson.Value =
    val parameter = RunParameter()
    val rtl = Seq({sources})
    val rows = collection.mutable.ArrayBuffer.empty[ujson.Obj]
{chr(10).join(rows)}
    val proofs = ujson.read({proofs})
    val status =
      if rows.exists(_("status").str == "unknown") then "unknown"
      else if rows.exists(_("status").str == "infeasible") then "infeasible"
      else if rows.nonEmpty then "generated"
      else if proofs.arr.nonEmpty then "proof-required"
      else "no-candidates"
    ujson.Obj("status" -> status, "engine" -> "jaspergold",
      "beats" -> rows.filter(_("status").str == "generated").map(_("cycles").num.toInt).sum,
      "replayContract" -> "cycle-replay-v1",
      "intents" -> ujson.Arr(rows.toSeq*), "proofObligations" -> proofs)
""")
    return "\n".join(parts)


def write_sources(directory: Path, design: Design, response: dict, time_limit: str = "120s") -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "DesignBinding.scala").write_text(render_binding(design))
    (directory / "Generated.scala").write_text(render_program(design, response, time_limit))
    (directory / "intent.json").write_text(json.dumps(response, indent=2) + "\n")
    (directory / "design.json").write_text(json.dumps(design.record(), indent=2) + "\n")
    return directory


def check_interface(design: Design, directory: Path) -> None:
    """Check explicit IO against CIRCT's elaborated HW module, not a regex of Verilog source."""
    if design.parameters:
        raise ValueError("CIRCT IO preflight for parameter overrides is not supported yet; use the backend elaboration")
    command = ["circt-verilog", *map(str, design.sources), "--ir-hw", f"--top={design.top}"]
    for include in design.include_dirs:
        command += ["-I", str(include)]
    imported = subprocess.run(command, check=True, capture_output=True, text=True)
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "imported.hw.mlir").write_text(imported.stdout)
    (directory / "import.log").write_text(imported.stderr)
    header = re.search(r"hw\.module @" + re.escape(design.top) + r"\((.*?)\)\s*\{", imported.stdout, re.S)
    if not header:
        raise ValueError(f"CIRCT import did not contain top {design.top}")
    actual = {}
    for field in header.group(1).split(","):
        port = re.fullmatch(r"\s*(in|out)\s+%?([A-Za-z_][A-Za-z0-9_]*)\s*:\s*i(\d+)\s*", field)
        if not port:
            raise ValueError(f"unsupported imported port type: {field.strip()}")
        actual[port[2]] = ("input" if port[1] == "in" else "output", int(port[3]))
    expected = {p.name: (p.direction, p.width) for p in design.ports}
    if actual != expected:
        raise ValueError(f"IO manifest disagrees with imported RTL: declared={expected}, imported={actual}")
    (directory / "io-check.json").write_text(json.dumps({"top": design.top, "ports": actual}, indent=2) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--design", type=Path, required=True)
    parser.add_argument("--response-file", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True, help="new run directory")
    parser.add_argument("--check-io", action="store_true", help="validate ports through circt-verilog (run inside nix develop)")
    parser.add_argument("--jg-time-limit", default="120s")
    args = parser.parse_args()
    design = load_design(args.design)
    response = parse_response(args.response_file.read_text())
    args.out.mkdir(parents=True, exist_ok=False)
    if args.check_io:
        check_interface(design, args.out / "interface")
    directory = write_sources(args.out / "sources", design, response, args.jg_time_limit)
    print(json.dumps({"status": "prepared", "top": design.top, "sources": str(directory.resolve()),
                      "intents": len(response["intents"])}))


if __name__ == "__main__":
    main()
