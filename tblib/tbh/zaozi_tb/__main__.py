# SPDX-License-Identifier: Apache-2.0
"""Run a generated testbench with a user Python driver, VCD and live console output."""

import argparse
import ast
import json
import math
import os
import re
import shlex
import shutil
import subprocess
import sys
import sysconfig
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import cast

from .codegen import functions, generate_header
from .data import json_value
from .design import elaborate, ir_top, scala_command

NATIVE = Path(__file__).parent / "native"


@dataclass
class Options(argparse.Namespace):
    driver: Path = Path(".")
    mlir: Path | None = None
    testbench: Path | None = None
    dut_mlir: Path | None = None
    clock_mlir: Path | None = None
    interface: Path | None = None
    parameter: Path | None = None
    out: Path | None = None
    bind: list[str] = field(default_factory=list)
    max_time_ns: float = 1000
    wall_timeout: float = 30
    jobs: int = 4
    check_only: bool = False
    skip_build: bool = False
    report: Path | None = None


def run_command(command: Sequence[str], *, cwd: Path, env: Mapping[str, str]) -> None:
    result = subprocess.run(command, cwd=cwd, env=env, check=False)
    if result.returncode:
        raise RuntimeError(
            f"Command failed ({result.returncode}): {shlex.join(command)}"
        )


def configure_bindings(interface: object, overrides: Sequence[str]) -> dict[str, str]:
    symbols = [function["function"] for function in functions(interface)]
    bindings = {name: "step" if len(symbols) == 1 else name for name in symbols}
    seen: set[str] = set()
    for binding in overrides:
        name, separator, callback = binding.partition("=")
        if (
            not separator
            or name not in bindings
            or not callback.isidentifier()
            or name in seen
        ):
            raise ValueError(f"Invalid or duplicate --bind: {binding}")
        seen.add(name)
        bindings[name] = callback
    return bindings


def elaboration_command(args: Options) -> list[str]:
    """Translate a validated source selection to the corresponding Scala entry point."""
    if args.testbench:
        assert args.parameter is not None
        return scala_command(args.testbench, ["design", str(args.parameter)])
    assert args.dut_mlir is not None and args.clock_mlir is not None
    return scala_command(
        Path(__file__).resolve().parents[1] / "TestbenchMain.scala",
        [str(args.dut_mlir.resolve()), str(args.clock_mlir.resolve())],
    )


def main() -> int:
    os.chdir(os.environ.pop("ZAOZI_TB_CWD", os.getcwd()))
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument(
        "driver", type=Path, help="Simulation directory, or Python/C++ DPI source"
    )
    source = parser.add_mutually_exclusive_group()
    _ = source.add_argument(
        "--mlir", type=Path, help="Already generated testbench MLIR bytecode"
    )
    _ = source.add_argument("--testbench", type=Path)
    _ = source.add_argument("--dut-mlir", type=Path)
    _ = parser.add_argument("--clock-mlir", type=Path)
    _ = parser.add_argument(
        "--interface", type=Path, help="DPI JSON; defaults to the MLIR stem + .json"
    )
    _ = parser.add_argument("--parameter", type=Path, help="Generator parameter JSON")
    _ = parser.add_argument(
        "--out",
        type=Path,
        help="Simulation directory; intermediate files go in .build/",
    )
    _ = parser.add_argument(
        "--bind", action="append", default=[], metavar="DPI_SYMBOL=PYTHON_FUNCTION"
    )
    _ = parser.add_argument("--max-time-ns", type=float, default=1000)
    _ = parser.add_argument("--wall-timeout", type=float, default=30)
    _ = parser.add_argument("-j", "--jobs", type=int, default=4)
    phase = parser.add_mutually_exclusive_group()
    _ = phase.add_argument(
        "--check-only", action="store_true", help="Build and link without simulation"
    )
    _ = phase.add_argument("--skip-build", action="store_true", help=argparse.SUPPRESS)
    _ = parser.add_argument(
        "--report", type=Path, help="Output .txt coverage or .vcd waveform"
    )
    args = parser.parse_args(namespace=Options())
    if args.driver.is_dir():
        if any(
            (
                args.mlir,
                args.dut_mlir,
                args.testbench,
                args.clock_mlir,
                args.interface,
                args.parameter,
                args.out,
            )
        ):
            parser.error("A simulation directory supplies its own design inputs")
        directory = args.driver.resolve()
        mlirs = list(directory.glob("*.mlirbc"))
        drivers = [
            path
            for suffix in (".py", ".cc", ".cpp", ".cxx")
            if (path := directory / f"dpi_impl{suffix}").is_file()
        ]
        if len(mlirs) != 1 or not mlirs[0].is_file() or len(drivers) != 1:
            parser.error(
                "A simulation directory needs one *.mlirbc and one dpi_impl.py/.cc/.cpp/.cxx"
            )
        args.driver = drivers[0]
        args.mlir = mlirs[0]
        args.interface = mlirs[0].with_suffix(".json")
        args.out = directory
    elif not (args.mlir or args.dut_mlir or args.testbench) or not args.out:
        parser.error("An explicit driver requires a design source and --out")
    if bool(args.dut_mlir) != bool(args.clock_mlir):
        parser.error("--dut-mlir and --clock-mlir must be supplied together")
    if args.dut_mlir and (args.parameter or args.interface):
        parser.error("IR inputs determine their parameters, interface and top")
    if args.testbench and args.parameter is None:
        parser.error("Scala input requires a parameter file")
    if (args.dut_mlir or args.testbench) and args.skip_build:
        parser.error("--skip-build requires an existing MLIR input")
    if args.check_only and args.report:
        parser.error("--report requires a simulation run")
    if args.report and args.report.suffix not in (".txt", ".vcd"):
        parser.error("--report must end in .txt or .vcd")
    if (
        not all(
            math.isfinite(value) and value > 0
            for value in (args.max_time_ns, args.wall_timeout)
        )
        or args.jobs < 1
    ):
        parser.error("Time limits and job count must be positive and finite")

    driver, output = args.driver.resolve(), args.out.resolve()
    if driver.suffix not in (".py", ".cc", ".cpp", ".cxx"):
        parser.error("Driver must be a Python or C++ source file")
    python_driver = driver.suffix == ".py"
    if python_driver:
        _ = ast.parse(driver.read_text(), filename=str(driver))
    elif args.bind:
        parser.error("--bind applies only to Python drivers")
    output.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    report = args.report.resolve() if args.report else output / "wave.vcd"
    if args.mlir is not None:
        mlir = args.mlir.resolve()
    else:
        mlir = elaborate(elaboration_command(args), output, env)
    top = ir_top(mlir)
    interface_path = (
        args.interface.resolve() if args.interface else mlir.with_suffix(".json")
    )
    interface = json_value(interface_path.read_text())
    bindings = configure_bindings(interface, args.bind) if python_driver else None

    # The simulation-local entry points at the same harness implementation.
    runner = output / "run.py"
    harness = Path(__file__).resolve().parents[1] / "run.py"
    if runner != harness:
        if runner.exists() or runner.is_symlink():
            runner.unlink()
        runner.symlink_to(os.path.relpath(harness, output))

    build = output / ".build"
    dpi, rtl, objects = (build / name for name in ("dpi", "rtl", "obj_dir"))
    if not args.skip_build:
        dpi.mkdir(parents=True, exist_ok=True)
        _ = (dpi / "dpi_api.h").write_text(generate_header(interface))
        print("Exporting RTL...", flush=True)
        run_command(
            [
                "firtool",
                str(mlir),
                "--split-verilog",
                "--lowering-options=emitReplicatedOpsToHeader",
                "-o",
                str(rtl),
            ],
            cwd=output,
            env=env,
        )
        # This CIRCT version emits a shared DPI header without a guard.
        shared_header = rtl / "circt_header.svh"
        if shared_header.exists():
            contents = shared_header.read_text()
            _ = shared_header.write_text(
                "`ifndef ZAOZI_TB_SHARED_HEADER\n`define ZAOZI_TB_SHARED_HEADER\n"
                + contents
                + "\n`endif\n"
            )

        include_paths = ["-I" + str(dpi), "-I" + str(NATIVE)]
        if python_driver:
            from .python_backend import emit_bridge

            print("Building Python CFFI implementation...", flush=True)
            bridge = emit_bridge(dpi)
            python_config = str(
                Path(sys.executable).resolve().with_name("python3-config")
            )
            cflags = shlex.split(
                subprocess.check_output([python_config, "--cflags"], text=True)
            )
            ldflags = shlex.split(
                subprocess.check_output(
                    [python_config, "--embed", "--ldflags"], text=True
                )
            )
            libdir = cast(object, sysconfig.get_config_var("LIBDIR"))
            if not isinstance(libdir, str):
                raise RuntimeError("Python LIBDIR is not configured")
            command = [
                *shlex.split(env.get("CC", "cc")),
                "-std=c11",
                "-shared",
                "-fPIC",
                *cflags,
                *include_paths,
                str(bridge),
                "-o",
                str(dpi / "libdpi.so"),
                *ldflags,
                "-Wl,-rpath," + libdir,
            ]
        else:
            print("Building C++ DPI implementation...", flush=True)
            command = [
                *shlex.split(env.get("CXX", "c++")),
                "-std=c++17",
                "-shared",
                "-fPIC",
                *include_paths,
                str(driver),
                "-o",
                str(dpi / "libdpi.so"),
            ]
        run_command(command, cwd=output, env=env)
        verilator = env.get("ZAOZI_TB_VERILATOR") or shutil.which("verilator")
        if not verilator:
            raise RuntimeError(
                "Verilator not found; use tblib/tbh/run.py to obtain it from nixpkgs"
            )
        print("Building simulator with coverage and waveform support...", flush=True)
        version = subprocess.run(
            [verilator, "--version"], check=True, capture_output=True, text=True
        ).stdout
        coverage_options = ["--coverage"]
        if re.search(r"\bVerilator 5\.050\b", version):
            # https://github.com/verilator/verilator/issues/7898
            # Expression instrumentation corrupts labelled inline SVA in 5.050.
            coverage_options.append("--no-coverage-expr")
            print(
                "Verilator 5.050: expression coverage disabled for inline SVA "
                + "compatibility; user Cover points remain enabled.",
                flush=True,
            )
        run_command(
            [
                verilator,
                "--cc",
                "--exe",
                "--build",
                "--timing",
                "--assert",
                *coverage_options,
                "--trace",
                "--timescale",
                "1ns/1ps",
                "--prefix",
                "VTestbench",
                "--top-module",
                top,
                "-j",
                str(args.jobs),
                "--Mdir",
                str(objects),
                "-I" + str(rtl),
                "-F",
                str(rtl / "filelist.f"),
                # firtool emits layer bindings separately from filelist.f.
                # Compile the top's bindings to instantiate verification logic.
                *map(str, sorted(rtl.glob(f"layers-{top}-*.sv"))),
                str(NATIVE / "simulator.cpp"),
                "-CFLAGS",
                shlex.join(include_paths),
                "-LDFLAGS",
                shlex.join(["-L" + str(dpi), "-Wl,-rpath," + str(dpi), "-ldpi"]),
            ],
            cwd=output,
            env=env,
        )
    elif not (objects / "VTestbench").is_file() or not (dpi / "libdpi.so").is_file():
        raise RuntimeError("No linked simulator found; run tbh to build it")
    env["ZAOZI_TB_MAX_TIME_NS"] = str(args.max_time_ns)
    if python_driver:
        env.update(
            ZAOZI_TB_DRIVER=str(driver),
            ZAOZI_TB_INTERFACE=str(interface_path),
            ZAOZI_TB_BINDINGS=json.dumps(bindings),
            PYTHONHOME=sys.base_prefix,
            PYTHONUNBUFFERED="1",
            PYTHONDONTWRITEBYTECODE="1",
            PYTHONPATH=str(Path(__file__).resolve().parents[1]),
        )
    if python_driver and not args.skip_build:
        print("Checking Python DPI implementation...", flush=True)
        run_command(
            [sys.executable, "-m", "zaozi_tb.preflight"], cwd=output, env=env
        )
    if args.check_only:
        print(f"CHECK OK: {top} linked successfully", flush=True)
        return 0

    report.parent.mkdir(parents=True, exist_ok=True)
    report.unlink(missing_ok=True)
    coverage_data = build / "coverage.dat"
    if report.suffix == ".vcd":
        env["ZAOZI_TB_WAVE_FILE"] = str(report)
        _ = env.pop("ZAOZI_TB_COVERAGE_FILE", None)
    else:
        coverage_data.unlink(missing_ok=True)
        env["ZAOZI_TB_COVERAGE_FILE"] = str(coverage_data)
        _ = env.pop("ZAOZI_TB_WAVE_FILE", None)
    print(f"Running {'Python' if python_driver else 'C++'} stimulus...", flush=True)
    try:
        result = subprocess.run(
            [str(objects / "VTestbench")],
            cwd=output,
            env=env,
            timeout=args.wall_timeout,
            check=False,
        )
        code = result.returncode
    except subprocess.TimeoutExpired:
        print(
            f"ERROR: wall-clock timeout after {args.wall_timeout}s",
            file=sys.stderr,
            flush=True,
        )
        code = 124
    if code == 0 and report.suffix == ".txt":
        verilator = env.get("ZAOZI_TB_VERILATOR") or shutil.which("verilator")
        if not coverage_data.is_file() or not verilator:
            raise RuntimeError("Verilator did not produce coverage data")
        analyzer = str(Path(verilator).with_name("verilator_coverage"))
        annotations = build / "coverage"
        if annotations.exists():
            shutil.rmtree(annotations)
        with report.open("w") as stream:
            _ = subprocess.run(
                [
                    analyzer,
                    "--report",
                    "summary,hier",
                    "--annotate",
                    str(annotations),
                    "--annotate-all",
                    "--annotate-points",
                    "--annotate-min",
                    "1",
                    str(coverage_data),
                ],
                cwd=output,
                env=env,
                stdout=stream,
                check=True,
            )
    if report.is_file():
        print(
            f"{'Coverage report' if report.suffix == '.txt' else 'Waveform'}: {report}",
            flush=True,
        )
    return code if code >= 0 else 128 - code


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (
        OSError,
        ValueError,
        KeyError,
        RuntimeError,
        SyntaxError,
        subprocess.CalledProcessError,
    ) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
