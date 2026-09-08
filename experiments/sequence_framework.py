#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Design-neutral Scala bindings and per-run verification UTs for external RTL.

Port declarations are explicit, versioned experiment inputs. They describe an
interface, not a reimplementation of the DUT. The only executable scenario code
comes from this run's complete model-authored UT sources; no stdlib benchmark UT is imported.
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
CONTRACT = "runtime-ut-v5"
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
    """One complete UT per response; goals share the same unmodified environment."""
    try:
        raw = json.loads(text)
    except json.JSONDecodeError as error:
        raise ValueError(f"response must be UT JSON: {error.msg}") from error
    stopping = isinstance(raw, dict) and set(raw) == {"stop", "proofObligations"}
    if stopping:
        if not isinstance(raw["stop"], dict) or set(raw["stop"]) != {"reason"} or not isinstance(raw["stop"]["reason"], str) or not 1 <= len(raw["stop"]["reason"].strip()) <= 20000:
            raise ValueError("stop requires a nonempty reason; stopping is not a proof or coverage closure")
        # Reuse proof validation without inventing a UT or goal.
        seen = set()
        validate_proofs(raw["proofObligations"], seen)
        return raw
    if not isinstance(raw, dict) or set(raw) != {"ut", "proofObligations"}:
        raise ValueError("response must contain exactly ut and proofObligations; multi-UT and expression-only responses are not supported")
    ut = raw["ut"]
    if not isinstance(ut, dict) or set(ut) != {"module", "generationLabels", "source"}:
        raise ValueError("ut must be one object with exactly module, generationLabels and source")
    module = identifier(ut["module"], "UT module")
    if module in {"Generated", "ImportedDut", "RunParameter", "RunLayers", "RunProbe", "RunIO", "DesignIO", "ImportedParameters"}:
        raise ValueError("UT module must not replace framework definitions")
    if not isinstance(ut["source"], str) or not ut["source"].strip() or len(ut["source"]) > 100000:
        raise ValueError("source must be a nonempty string of at most 100000 characters")
    # Early feedback, not a security boundary. The lowered SV is checked again before any solver runs.
    code = re.sub(r'""".*?"""|"(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/', ' ', ut["source"], flags=re.S)
    if re.search(r"\bAssume\b", code):
        raise ValueError("model-authored Assume is forbidden; put scenario conditions and timing inside Gen")
    if len(re.findall(r"@generator\b", code)) != 1:
        raise ValueError("source must declare exactly one @generator UT")
    labels = ut["generationLabels"]
    if not isinstance(labels, list) or not 1 <= len(labels) <= 64:
        raise ValueError("generationLabels must contain 1..64 goals in the single UT")
    seen = set()
    for label in labels:
        if not isinstance(label, str) or not LABEL.fullmatch(label) or label in seen:
            raise ValueError("goal labels must be unique safe snake_case identifiers")
        seen.add(label)
    validate_proofs(raw["proofObligations"], seen)
    return raw


def validate_proofs(proofs, seen):
    if not isinstance(proofs, list) or len(proofs) > 64:
        raise ValueError("proofObligations must be a list of at most 64 records")
    for item in proofs:
        if not isinstance(item, dict) or set(item) != {"label", "reason"}:
            raise ValueError("proofObligations records require exactly label and reason")
        label, reason = item["label"], item["reason"]
        if not isinstance(label, str) or not LABEL.fullmatch(label) or label in seen:
            raise ValueError("proof labels must be unique safe snake_case identifiers")
        seen.add(label)
        if not isinstance(reason, str) or not reason.strip() or len(reason) > 20000:
            raise ValueError("reason must be a nonempty string of at most 20000 characters")


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
    """Sandbox entry: elaborate only. No solver, license, host processes or model classpath on the trusted side."""
    parse_response(json.dumps(response))
    ut = response["ut"]
    labels = ", ".join(scala_string(label) for label in ut["generationLabels"])
    return IMPORTS + f"""
object Generated extends UTExperiment:
  def run(outDir: os.Path): ujson.Value =
    val parameter = RunParameter()
    val generator = UTGenerator({ut['module']}, parameter, outDir / "ut")
    generator.saveAbi()
    val model = JasperGold.lower({ut['module']}, parameter, outDir / "ut" / "lowered", Seq.empty,
      generationLabels = Set({labels}))
    ujson.Obj("status" -> "lowered", "top" -> model.top, "sv" -> model.sv.toString,
      "abiFile" -> (outDir / "ut" / "abi.json").toString)
"""


def write_sources(directory: Path, design: Design, response: dict, time_limit: str = "120s") -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "DesignBinding.scala").write_text(render_binding(design))
    (directory / "Generated.scala").write_text(render_program(design, response, time_limit))
    ut = response["ut"]
    # Preserve exactly the model's decoded source, including whitespace.
    (directory / "ModelUT.scala").write_bytes(ut["source"].encode())
    authored = [{"file": "ModelUT.scala", "module": ut["module"], "generationLabels": ut["generationLabels"],
                "sha256": hashlib.sha256(ut["source"].encode()).hexdigest()}]
    (directory / "model-sources.json").write_text(json.dumps(authored, indent=2) + "\n")
    (directory / "response.json").write_text(json.dumps(response, indent=2) + "\n")
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


def check_saved_sources(directory: Path, design: Design, response: dict) -> None:
    expected = {"ModelUT.scala": response["ut"]["source"], "DesignBinding.scala": render_binding(design),
                "Generated.scala": render_program(design, response)}
    if {p.name for p in directory.glob("*.scala")} != set(expected):
        raise ValueError("saved source inventory differs from the fixed runner/binding and model UT")
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
    response = parse_response(args.response_file.read_text())
    args.out.mkdir(parents=True, exist_ok=False)
    if "stop" in response:
        (args.out / "response.json").write_text(json.dumps(response, indent=2) + "\n")
        print(json.dumps({"status": "stopped", "top": design.top, "utCount": 0,
                          "stopReason": response["stop"]["reason"], "proofObligations": response["proofObligations"]}))
        return
    if args.check_io:
        check_interface(design, args.out / "interface")
    directory = write_sources(args.out / "sources", design, response, args.jg_time_limit)
    print(json.dumps({"status": "prepared", "top": design.top, "sources": str(directory.resolve()),
                      "utCount": 1, "goals": len(response["ut"]["generationLabels"])}))


if __name__ == "__main__":
    main()
