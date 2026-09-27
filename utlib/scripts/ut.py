#!/usr/bin/env python3
"""Lower and run a unit testbench with selectable frontend and simulator backend."""

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

sys.dont_write_bytecode = True

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "utlib"))

from backend.run import run as run_backend
from frontend.prepare import prepare as prepare_frontend


def lower(circuit_dir, build_dir, timeout):
    if shutil.which("firtool") is None:
        raise RuntimeError("firtool is not on PATH")
    source_dir = build_dir / "sv"
    hw = circuit_dir / "testbench.hw.mlir"
    if not hw.is_file():
        raise ValueError(f"unit testbench HW IR is missing: {hw}")
    command = [
        "firtool",
        "--format=mlir",
        "--split-verilog",
        str(hw),
        "-o",
        str(source_dir),
    ]
    result = subprocess.run(
        command, cwd=build_dir, text=True, capture_output=True, timeout=timeout
    )
    (build_dir / "lowering.log").write_text(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError(
            f"firtool failed while lowering testbench; see {build_dir / 'lowering.log'}"
        )
    return source_dir


def run(
    circuit_dir,
    top,
    driver,
    stimulus,
    frontend_name,
    backend_name,
    timeout,
    build_dir,
):
    circuit_dir = circuit_dir.resolve()
    build_dir = build_dir.resolve()
    build_dir.mkdir(parents=True, exist_ok=True)
    stimulus = stimulus.resolve()
    if not stimulus.is_file():
        raise ValueError(f"stimulus JSON is missing: {stimulus}")
    source_dir = lower(circuit_dir, build_dir, timeout)
    schema = circuit_dir / "interface.json"
    if not schema.is_file():
        raise ValueError("exported DPI JSON is missing")
    frontend = prepare_frontend(frontend_name, schema, driver, stimulus, build_dir)
    return run_backend(
        backend_name, source_dir, top, frontend, build_dir, circuit_dir, timeout
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--circuit-dir", required=True, type=Path)
    parser.add_argument("--top", required=True)
    parser.add_argument("--driver", required=True, type=Path)
    parser.add_argument("--stimulus", required=True, type=Path)
    parser.add_argument("--frontend", choices=("cpp", "cpython"), default="cpp")
    parser.add_argument("--backend", choices=("verilator", "vcs"), default="verilator")
    parser.add_argument("--build-dir", type=Path)
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    try:
        result = run(
            args.circuit_dir,
            args.top,
            args.driver,
            args.stimulus,
            args.frontend,
            args.backend,
            args.timeout,
            args.build_dir or args.circuit_dir / "build",
        )
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(error, file=sys.stderr)
        return 1
    print(result.stdout, end="")
    print(result.stderr, end="", file=sys.stderr)
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
