#!/usr/bin/env python3
"""Compile and run a lowered DPI UT with Verilator.

The UT trait emits SystemVerilog and CIRCT's JSON schema under out/ut/<case>.
This script assembles the DPI bridge, builds the simulator, and collects trace and coverage.
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import sysconfig
from pathlib import Path

sys.dont_write_bytecode = True

from generate_dpi import generate


REPO_ROOT = Path(__file__).resolve().parents[2]
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z_0-9]*\Z")
MODULE = re.compile(r"(?m)^\s*module\s+([A-Za-z_][A-Za-z_0-9]*)\s*[;(]")
COVERAGE = re.compile(r"^C\s+'(.*)'\s+(\d+)\s*$")


def python_link_flags():
    include = sysconfig.get_path("include")
    library_dir = sysconfig.get_config_var("LIBDIR")
    library_name = sysconfig.get_config_var("LDLIBRARY")
    if not include or not library_dir or not library_name or not library_name.startswith("libpython"):
        raise RuntimeError("embedded CPython headers or library are unavailable")
    stem = library_name.removeprefix("lib").split(".so")[0].split(".a")[0]
    return f"-I{include}", f"-L{library_dir} -l{stem}"


def coverage_report(path):
    hits = {}
    for line in path.read_text().splitlines():
        match = COVERAGE.fullmatch(line.strip())
        if match is None:
            continue
        fields = {}
        for field in match.group(1).split("\x01"):
            if "\x02" in field:
                key, value = field.split("\x02", 1)
                fields[key] = value
        name = fields.get("o") or fields.get("h", "<unnamed>").rsplit(".", 1)[-1]
        hits[name] = hits.get(name, 0) + int(match.group(2))
    return {"coverpoints": dict(sorted(hits.items())), "total": len(hits),
            "covered": sum(count > 0 for count in hits.values())}


def lower_dut_mlirbc(modules, dut_module, build_dir, timeout):
    for executable in ("firld", "firtool"):
        if shutil.which(executable) is None:
            raise RuntimeError(f"{executable} is not on PATH")
    for module in modules:
        if not module.is_file():
            raise ValueError(f"DUT MLIR bytecode is missing: {module}")
    linked = build_dir / "linked.mlir"
    source = build_dir / "dut.sv"
    commands = (
        ["firld", f"--base-circuit={dut_module}", "--no-mangle",
         *(str(module.resolve()) for module in modules), "-o", str(linked)],
        ["firtool", "--format=mlir", "--verilog", str(linked), "-o", str(source)],
    )
    logs = []
    for command in commands:
        result = subprocess.run(command, cwd=build_dir, text=True, capture_output=True, timeout=timeout)
        logs.append(result.stdout + result.stderr)
        if result.returncode:
            (build_dir / "lowering.log").write_text("".join(logs))
            raise RuntimeError(f"{command[0]} failed; see {build_dir / 'lowering.log'}")
    (build_dir / "lowering.log").write_text("".join(logs))
    return source


def prepare_testbench(source, top, build_dir):
    """Enable VCD tracing in a build copy of the selected SV top module."""
    match = re.search(r"\bmodule\s+" + re.escape(top) + r"\b", source)
    if match is None:
        raise ValueError(f"cannot find top module {top} in the SV testbench")
    end = re.search(r"\bendmodule\b", source[match.end():])
    if end is None:
        raise ValueError(f"cannot find endmodule for {top}")
    end_offset = match.end() + end.start()
    top_source = source[match.start():end_offset]
    if not re.search(r"\$dumpfile\s*\(", top_source):
        source = (source[:end_offset] +
                  '  initial begin\n    $dumpfile("trace.vcd");\n    $dumpvars(0);\n  end\n' +
                  source[end_offset:])
    path = build_dir / "testbench.sv"
    path.write_text(source)
    return path


def run(testbench, schema, dut_sources, dut_mlirbc,
        driver, stimulus, top, timeout, build_dir):
    if shutil.which("verilator") is None:
        raise RuntimeError("verilator is not on PATH")
    schema = schema.resolve()
    testbench = testbench.resolve()
    case_dir = testbench.parent
    if schema.parent != case_dir or not schema.is_file():
        raise ValueError("testbench input and JSON schema must be in the same UT directory")
    build_dir = build_dir.resolve()
    build_dir.mkdir(parents=True, exist_ok=True)
    if not testbench.is_file():
        raise ValueError(f"SV testbench is missing: {testbench}")
    schema_data = json.loads(schema.read_text())
    if any("abi" not in arg for func in schema_data["dpi_functions"]
           for arg in func["arguments"]):
        raise ValueError("DPI schema has no ABI annotations; regenerate it with the updated CIRCT pass")
    source = testbench.read_text()
    if top is None:
        match = MODULE.search(source)
        if match is None:
            raise ValueError("cannot find a top module in the SV testbench")
        top = match.group(1)
    if IDENTIFIER.fullmatch(top) is None:
        raise ValueError(f"invalid top module: {top}")
    driver = driver.resolve()
    stimulus = stimulus.resolve()
    if not driver.is_file() or driver.suffix != ".py" or not driver.stem.isidentifier():
        raise ValueError("driver must be a Python file with an identifier stem")
    if not stimulus.is_file():
        raise ValueError(f"stimulus JSON is missing: {stimulus}")
    json.loads(stimulus.read_text())
    if dut_sources and dut_mlirbc:
        raise ValueError("choose either DUT SV sources or DUT MLIR bytecode")
    prepared_testbench = prepare_testbench(source, top, build_dir)
    if not dut_sources and not dut_mlirbc:
        dut_mlirbc = sorted((case_dir / "dut").glob("*.mlirbc"))
    if dut_mlirbc:
        dut_module = json.loads(schema.read_text())["dut"]["module"]
        dut_sources = [lower_dut_mlirbc(dut_mlirbc, dut_module, build_dir, timeout)]
    elif not dut_sources:
        dut_sources = [REPO_ROOT / "stdlib" / "ut" / case_dir.name / "dut.sv"]
    for path in dut_sources:
        if not path.is_file():
            raise ValueError(f"DUT SV source is missing: {path}")
    generate(schema, build_dir)
    cflags, ldflags = python_link_flags()
    cflags += f" -I{build_dir} -include V{top}__Dpi.h"
    binary_dir = build_dir / "obj_dir"
    command = [
        "verilator", "--binary", "--timing", "--assert", "--coverage-user",
        "--trace", "--trace-structs", "--top-module", top, "-Wno-fatal",
        "-Mdir", str(binary_dir), "-I" + str(build_dir),
        *(str(path.resolve()) for path in dut_sources), str(prepared_testbench),
        str(REPO_ROOT / "utlib" / "native" / "dpi_bridge.cpp"),
        "-CFLAGS", cflags, "-LDFLAGS", ldflags,
    ]
    build = subprocess.run(command, cwd=build_dir, text=True, capture_output=True, timeout=timeout)
    (case_dir / "build.log").write_text(build.stdout + build.stderr)
    if build.returncode:
        raise RuntimeError(f"Verilator build failed; see {case_dir / 'build.log'}")
    env = os.environ.copy()
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    env["PYTHONPATH"] = os.pathsep.join(filter(None, [
        str(REPO_ROOT / "utlib" / "python"), str(driver.parent), env.get("PYTHONPATH")
    ]))
    env["ZAOZI_FRONTEND_MODULE"] = driver.stem
    env["ZAOZI_DPI_SCHEMA"] = str(schema)
    env["ZAOZI_STIMULUS_JSON"] = str(stimulus)
    coverage = case_dir / "coverage.dat"
    for stale in (coverage, case_dir / "coverage_report.json", case_dir / "trace.vcd"):
        stale.unlink(missing_ok=True)
    executable = binary_dir / ("V" + top)
    result = subprocess.run(
        [str(executable), "+verilator+coverage+file+" + str(coverage)],
        cwd=case_dir, env=env, text=True, capture_output=True, timeout=timeout,
    )
    (case_dir / "simulation.log").write_text(result.stdout + result.stderr)
    if coverage.is_file():
        (case_dir / "coverage_report.json").write_text(json.dumps(coverage_report(coverage), indent=2) + "\n")
    elif result.returncode == 0:
        raise RuntimeError("simulation completed without coverage.dat")
    if result.returncode == 0 and not (case_dir / "trace.vcd").is_file():
        raise RuntimeError("simulation completed without trace.vcd")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--testbench", required=True, type=Path)
    parser.add_argument("--schema", required=True, type=Path)
    parser.add_argument("--dut-source", type=Path, action="append", default=[])
    parser.add_argument("--dut-mlirbc", type=Path, action="append", default=[])
    parser.add_argument("--driver", type=Path)
    parser.add_argument("--stimulus", type=Path)
    parser.add_argument("--top")
    parser.add_argument("--build-dir", type=Path)
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    case_dir = args.testbench.resolve().parent
    fixture_dir = REPO_ROOT / "stdlib" / "ut" / case_dir.name
    try:
        result = run(
            args.testbench, args.schema,
            args.dut_source, args.dut_mlirbc,
            args.driver or fixture_dir / "driver.py",
            args.stimulus or fixture_dir / "stimulus.json",
            args.top, args.timeout,
            args.build_dir or case_dir / "build",
        )
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(error, file=sys.stderr)
        return 1
    print(result.stdout, end="")
    print(result.stderr, end="", file=sys.stderr)
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
