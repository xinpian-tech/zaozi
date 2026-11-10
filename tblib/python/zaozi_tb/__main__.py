# SPDX-License-Identifier: Apache-2.0
"""Run a generated testbench with a user Python driver, VCD and live console output."""

import argparse
import ast
import json
import math
import os
import shlex
import shutil
import subprocess
import sys
import sysconfig
from pathlib import Path

from .codegen import functions, generate_header

NATIVE = Path(__file__).parent / "native"


def run_command(command, *, cwd, env):
    result = subprocess.run(command, cwd=cwd, env=env, check=False)
    if result.returncode:
        raise RuntimeError(
            f"Command failed ({result.returncode}): {shlex.join(command)}"
        )


def configure_bindings(interface, overrides):
    symbols = [function["function"] for function in functions(interface)]
    bindings = {name: "step" if len(symbols) == 1 else name for name in symbols}
    seen = set()
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


def main():
    os.chdir(os.environ.pop("ZAOZI_TB_CWD", os.getcwd()))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "driver", type=Path, help="Python or C++ implementation of the DPI API"
    )
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--generator", help="Scala testbench main class to elaborate")
    source.add_argument(
        "--mlir", type=Path, help="Already generated testbench MLIR bytecode"
    )
    parser.add_argument(
        "--interface", type=Path, help="DPI JSON; defaults to the MLIR stem + .json"
    )
    parser.add_argument("--mill-module", help="Mill module containing the generator")
    parser.add_argument("--parameter", type=Path, help="Generator parameter JSON")
    parser.add_argument("--top", required=True, help="SystemVerilog top module")
    parser.add_argument(
        "--out",
        type=Path,
        required=True,
        help="Simulation directory; intermediate files go in .build/",
    )
    parser.add_argument(
        "--bind", action="append", default=[], metavar="DPI_SYMBOL=PYTHON_FUNCTION"
    )
    parser.add_argument("--max-time-ns", type=float, default=1000)
    parser.add_argument("--wall-timeout", type=float, default=30)
    parser.add_argument("-j", "--jobs", type=int, default=4)
    args = parser.parse_args()
    if args.generator and (not args.mill_module or not args.parameter):
        parser.error("--generator requires --mill-module and --parameter")
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
        ast.parse(driver.read_text(), filename=str(driver))
    elif args.bind:
        parser.error("--bind applies only to Python drivers")
    output.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    # A failed build/run must not leave an older waveform looking like a new result.
    (output / "wave.vcd").unlink(missing_ok=True)
    if args.generator:
        print("Elaborating testbench...", flush=True)
        env["ZAOZI_OUTDIR"] = str(output)
        native_paths = ":".join(
            str(Path(env[name]) / "lib")
            for name in ("MLIR_INSTALL_PATH", "CIRCT_INSTALL_PATH")
        )
        env["JAVA_TOOL_OPTIONS"] = (
            env.get("JAVA_TOOL_OPTIONS", "")
            + " --enable-native-access=ALL-UNNAMED -Djava.library.path="
            + native_paths
        )
        run_command(
            [
                "mill",
                args.mill_module + ".runMain",
                args.generator,
                "design",
                str(args.parameter.resolve()),
            ],
            cwd=Path.cwd(),
            env=env,
        )
        mlir = output / (args.top + ".mlirbc")
    else:
        mlir = args.mlir.resolve()
    interface_path = (
        args.interface.resolve() if args.interface else mlir.with_suffix(".json")
    )
    interface = json.loads(interface_path.read_text())
    bindings = configure_bindings(interface, args.bind) if python_driver else None

    build = output / ".build"
    dpi, rtl, objects = (build / name for name in ("dpi", "rtl", "obj_dir"))
    dpi.mkdir(parents=True, exist_ok=True)
    (dpi / "dpi_api.h").write_text(generate_header(interface))
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
        shared_header.write_text(
            "`ifndef ZAOZI_TB_SHARED_HEADER\n`define ZAOZI_TB_SHARED_HEADER\n"
            + contents
            + "\n`endif\n"
        )

    include_paths = ["-I" + str(dpi), "-I" + str(NATIVE)]
    if python_driver:
        from .python_backend import emit_bridge

        print("Building Python CFFI implementation...", flush=True)
        bridge = emit_bridge(dpi)
        python_config = str(Path(sys.executable).resolve().with_name("python3-config"))
        cflags = shlex.split(
            subprocess.check_output([python_config, "--cflags"], text=True)
        )
        ldflags = shlex.split(
            subprocess.check_output([python_config, "--embed", "--ldflags"], text=True)
        )
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
            "-Wl,-rpath," + sysconfig.get_config_var("LIBDIR"),
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
            "Verilator not found; use tblib/python/run.py to obtain it from nixpkgs"
        )
    print("Building simulator with waveform tracing...", flush=True)
    run_command(
        [
            verilator,
            "--cc",
            "--exe",
            "--build",
            "--timing",
            "--assert",
            "--trace",
            "--timescale",
            "1ns/1ps",
            "--prefix",
            "VTestbench",
            "--top-module",
            args.top,
            "-j",
            str(args.jobs),
            "--Mdir",
            str(objects),
            "-I" + str(rtl),
            "-F",
            str(rtl / "filelist.f"),
            str(NATIVE / "simulator.cpp"),
            "-CFLAGS",
            shlex.join(include_paths),
            "-LDFLAGS",
            shlex.join(["-L" + str(dpi), "-Wl,-rpath," + str(dpi), "-ldpi"]),
        ],
        cwd=output,
        env=env,
    )
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
    print(f"Waveform: {output / 'wave.vcd'}", flush=True)
    return code if code >= 0 else 128 - code


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, RuntimeError, SyntaxError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
