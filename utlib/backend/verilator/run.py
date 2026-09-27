"""Build and run the CIRCT simulation wrapper with Verilator."""

import json
import os
import re
import shlex
import shutil
import subprocess
from pathlib import Path


COVERAGE = re.compile(r"^C\s+'(.*)'\s+(\d+)\s*$")


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
    return {
        "coverpoints": dict(sorted(hits.items())),
        "total": len(hits),
        "covered": sum(count > 0 for count in hits.values()),
    }


def run(source_dir, top, frontend, build_dir, circuit_dir, timeout):
    if shutil.which("verilator") is None:
        raise RuntimeError("verilator is not on PATH")
    simulation_top = top + "Wrapper"
    nix_cflags = tuple(shlex.split(os.environ.get("NIX_CFLAGS_COMPILE", "")))
    cflags = frontend.cflags + nix_cflags + (
        f"-include V{simulation_top}.h",
        f"-include V{simulation_top}__Dpi.h",
        f"-DZAOZI_TOP=V{simulation_top}",
    )
    binary_dir = build_dir / "obj_dir"
    command = [
        "verilator",
        "--cc",
        "--exe",
        "--build",
        "--timing",
        "--assert",
        "--coverage-user",
        "--trace",
        "--trace-structs",
        "--top-module",
        simulation_top,
        "-Wno-fatal",
        "-Mdir",
        str(binary_dir),
        "-I" + str(source_dir),
        "-F",
        str(source_dir / "filelist.f"),
        str(Path(__file__).resolve().parent / "main.cpp"),
        *(str(source) for source in frontend.sources),
        "-CFLAGS",
        " ".join(cflags),
    ]
    if frontend.ldflags:
        command.extend(("-LDFLAGS", " ".join(frontend.ldflags)))
    build = subprocess.run(
        command, cwd=build_dir, text=True, capture_output=True, timeout=timeout
    )
    (circuit_dir / "build.log").write_text(build.stdout + build.stderr)
    if build.returncode:
        raise RuntimeError(f"Verilator build failed; see {circuit_dir / 'build.log'}")

    coverage = circuit_dir / "coverage.dat"
    for stale in (coverage, circuit_dir / "coverage_report.json", circuit_dir / "trace.vcd"):
        stale.unlink(missing_ok=True)
    environment = os.environ.copy()
    environment.update(frontend.environment)
    executable = binary_dir / ("V" + simulation_top)
    result = subprocess.run(
        [str(executable)],
        cwd=circuit_dir,
        env=environment,
        text=True,
        capture_output=True,
        timeout=timeout,
    )
    (circuit_dir / "simulation.log").write_text(result.stdout + result.stderr)
    if coverage.is_file():
        (circuit_dir / "coverage_report.json").write_text(
            json.dumps(coverage_report(coverage), indent=2) + "\n"
        )
    elif result.returncode == 0:
        raise RuntimeError("simulation completed without coverage.dat")
    if result.returncode == 0 and not (circuit_dir / "trace.vcd").is_file():
        raise RuntimeError("simulation completed without trace.vcd")
    return result
