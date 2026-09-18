#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Design-neutral Scala bindings and per-run verification UTs for external RTL.

Port declarations are explicit, versioned experiment inputs. They describe an
interface, not a reimplementation of the DUT. The only executable scenario code
comes from this run's LTL fragment; the UT shell is deterministic. No benchmark UT is imported.
Generated Scala must be reviewed or run in an appropriate execution sandbox.
LTL syntax validation and scalac type checking are not a security sandbox.
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
CONTRACT = "runtime-ltl-v4"
MODEL_MODULE = "ModelUT"
from ltl_source import parse as parse_response, FORBIDDEN

# Avoid capturing framework terms or Scala keywords. Ordinary ports keep their
# exact names; exceptional mappings are explicit in the model-facing IO table.
PORT_RESERVED = FORBIDDEN | set("""
abstract case catch do else extends final finally for forSome if implicit lazy
match new override private protected return sealed super this throw try type val
var while with yield true false null end export extension inline opaque open
transparent using derives then infix erased into
io dut parameter Gen Ltl past BigInt Some None Seq Option List Map Set Array
Bool Bits UInt SInt Clock Reset Referable Sequence Property Immediate
posedge negedge always eventually ClockEvent ClockScope ResetScope
RunLayers ModelUT Generated IMPORTS _ given_ClockEvent given_ClockScope given_ResetScope
""".split())
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*\Z")


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
        return tuple(p.name for p in self.data_ports if p.direction == "input" and p.kind != 'clock')

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
        if kind == 'clock' and direction != 'input':
            raise ValueError('Clock role must identify an input')
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
        if port.name not in (clock, reset) and port.name in ("clock", "reset"):
            raise ValueError("clock and reset are reserved UT names")
    sources = tuple((path.parent / p).resolve() for p in raw["sources"])
    include_dirs = tuple((path.parent / p).resolve() for p in raw.get("include_dirs", []))
    if not sources or len(set(sources)) != len(sources) or any(not p.is_file() for p in sources):
        raise ValueError("sources must be a nonempty list of existing, distinct RTL files")
    if any(not p.is_dir() for p in include_dirs):
        raise ValueError("include directories must exist")
    params = tuple(raw.get("parameters", {}).items())
    for name, value in params:
        identifier(name, "parameter name")
        if type(value) is not int:
            raise ValueError("only integer Verilog parameters are supported")
    sequence = raw["sequence"]
    return Design(identifier(raw["top"], "top"), sources, include_dirs, tuple(ports), clock, reset,
                  raw["reset"]["active_low"], identifier(sequence["name"], "sequence name"),
                  identifier(sequence["item_type"], "sequence item type"), str(raw.get("context", "")), params)


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


def port_bindings(design: Design) -> dict[str, str]:
    """Normalized IO name -> lexical alias. No RTL or model content influences it."""
    names = ["clock", "reset", *(p.name for p in design.data_ports)]
    occupied = set(names) | PORT_RESERVED
    aliases = {}
    for name in names:
        alias = name
        if alias in PORT_RESERVED:
            alias = "port_" + name
            while alias in occupied:
                alias = "port_" + alias
        aliases[name] = alias
        occupied.add(alias)
    return aliases


def render_model_ut(design: Design, response: dict) -> str:
    """Deterministic environment; the model supplies only the LTL body."""
    body = parse_response(response["ltl"])["ltl"]
    connections = [f'    dut.io.`{design.clock}` := io.clock',
                   f'    dut.io.`{design.reset}` := {"!" if design.reset_active_low else ""}io.reset.asBool']
    for port in design.data_ports:
        connections.append(f'    dut.io.`{port.name}` := io.`{port.name}`' if port.direction == 'input' else
                           f'    io.`{port.name}` := dut.io.`{port.name}`')
    aliases = [f'    val `{alias}` = io.`{name}`' for name, alias in port_bindings(design).items()]
    return IMPORTS + f'''
@generator
object {MODEL_MODULE} extends Generator[RunParameter, RunLayers, RunIO, RunProbe] with UT[RunParameter, RunIO]:
  override def moduleName(parameter: RunParameter): String = "{MODEL_MODULE}"
  def architecture(parameter: RunParameter) =
    val io = summon[Interface[RunIO]]
    val dut = ImportedDut.instantiate(parameter)
{chr(10).join(connections)}
    given ClockEvent = posedge(io.clock)
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)
{chr(10).join(aliases)}
    // BEGIN MODEL LTL
''' + "\n".join("    " + line for line in body.split("\n")) + "\n    // END MODEL LTL\n"


def render_program(design: Design, response: dict, time_limit: str = "120s") -> str:
    """Sandbox entry: elaborate only. No solver, license, host processes or model classpath on the trusted side."""
    response = parse_response(response["ltl"])
    labels = ", ".join(scala_string(label) for label in response["labels"])
    return IMPORTS + f"""
object Generated extends UTExperiment:
  def run(outDir: os.Path): ujson.Value =
    val parameter = RunParameter()
    val generator = UTGenerator({MODEL_MODULE}, parameter, outDir / "ut")
    generator.saveAbi()
    val model = JasperGold.lower({MODEL_MODULE}, parameter, outDir / "ut" / "lowered", Seq.empty,
      generationLabels = Set({labels}))
    ujson.Obj("status" -> "lowered", "top" -> model.top, "sv" -> model.sv.toString,
      "abiFile" -> (outDir / "ut" / "abi.json").toString)
"""


def write_sources(directory: Path, design: Design, response: dict, time_limit: str = "120s") -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "DesignBinding.scala").write_text(render_binding(design))
    (directory / "Generated.scala").write_text(render_program(design, response, time_limit))
    response = parse_response(response["ltl"])
    source = render_model_ut(design, response)
    (directory / "model.ltl").write_bytes(response["ltl"].encode())
    (directory / "ModelUT.scala").write_bytes(source.encode())
    authored = [{"file": "ModelUT.scala", "module": MODEL_MODULE, "generationLabels": response["labels"],
                "sha256": hashlib.sha256(source.encode()).hexdigest(),
                "ltlSha256": hashlib.sha256(response["ltl"].encode()).hexdigest()}]
    (directory / "model-sources.json").write_text(json.dumps(authored, indent=2) + "\n")
    (directory / "response.json").write_text(json.dumps(response, indent=2) + "\n")
    (directory / "design.json").write_text(json.dumps(design.record(), indent=2) + "\n")
    return directory


def verilog_import_options(sources):
    options = ['--timescale=1ns/1ps']
    if all(Path(p).suffix == '.v' for p in sources):
        options.append('--Xslang=--std=1364-2005')
    return options


def check_interface(design: Design, directory: Path) -> None:
    """Check explicit IO against CIRCT's elaborated HW module, not a regex of Verilog source."""
    if design.parameters:
        raise ValueError("CIRCT IO preflight for parameter overrides is not supported yet; use the backend elaboration")
    command = ["circt-verilog", *map(str, design.sources), "--ir-hw", f"--top={design.top}",
               *verilog_import_options(design.sources)]
    for include in design.include_dirs:
        command += ["-I", str(include)]
    imported = subprocess.run(command, check=True, capture_output=True, text=True, errors='replace')
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


def check_saved_sources(directory: Path, design: Design, response: dict) -> None:
    expected = {"ModelUT.scala": render_model_ut(design, response), "DesignBinding.scala": render_binding(design),
                "Generated.scala": render_program(design, response)}
    if {p.name for p in directory.glob("*.scala")} != set(expected):
        raise ValueError("saved source inventory differs from the fixed runner/binding and model UT")
    expected["model.ltl"] = response["ltl"]
    if json.loads((directory / "response.json").read_text()) != parse_response(response["ltl"]):
        raise ValueError("saved LTL manifest changed")
    for name, text in expected.items():
        if (directory / name).read_bytes() != text.encode():
            raise ValueError(f"saved source changed: {name}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--design", type=Path, required=True)
    parser.add_argument("--response-file", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True, help="new run directory")
    parser.add_argument("--check-io", action="store_true", help="validate ports through circt-verilog (run inside nix develop)")
    parser.add_argument("--jg-time-limit", default="120s")
    args = parser.parse_args()
    design = load_design(args.design)
    from ltl_source import normalize
    raw = args.response_file.read_bytes().decode('utf-8')
    response = parse_response(raw)
    _, normalization = normalize(raw)
    args.out.mkdir(parents=True, exist_ok=False)
    (args.out / 'response.txt').write_bytes(raw.encode())
    (args.out / 'response-normalization.json').write_text(json.dumps(normalization, indent=2) + '\n')
    if "stop" in response:
        (args.out / "response.json").write_text(json.dumps(response, indent=2) + "\n")
        print(json.dumps({"status": "stopped", "top": design.top, "utCount": 0,
                          "stopReason": "model supplied no new LTL target", "proofObligations": []}))
        return
    if args.check_io:
        check_interface(design, args.out / "interface")
    directory = write_sources(args.out / "sources", design, response, args.jg_time_limit)
    print(json.dumps({"status": "prepared", "top": design.top, "sources": str(directory.resolve()),
                      "utCount": 1, "goals": len(response["labels"])}))


if __name__ == "__main__":
    main()
