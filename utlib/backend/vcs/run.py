"""Build and run the CIRCT simulation wrapper with Synopsys VCS."""

import os
import shlex
import shutil
import subprocess


def run(source_dir, top, frontend, build_dir, circuit_dir, timeout):
    if shutil.which("vcs") is None:
        raise RuntimeError("vcs is not on PATH")
    simulation_top = top + "Wrapper"
    executable = build_dir / "simv"
    command = [
        "vcs",
        "-sverilog",
        "-full64",
        "-timescale=1ns/1ps",
        "-top",
        simulation_top,
        "-F",
        str(source_dir / "filelist.f"),
        *(str(source) for source in frontend.sources),
    ]
    cflags = frontend.cflags + tuple(
        shlex.split(os.environ.get("NIX_CFLAGS_COMPILE", ""))
    )
    if cflags:
        command.extend(("-CFLAGS", " ".join(cflags)))
    if frontend.ldflags:
        command.extend(("-LDFLAGS", " ".join(frontend.ldflags)))
    command.extend(("-o", str(executable)))
    build = subprocess.run(
        command, cwd=build_dir, text=True, capture_output=True, timeout=timeout
    )
    (circuit_dir / "build.log").write_text(build.stdout + build.stderr)
    if build.returncode:
        raise RuntimeError(f"VCS build failed; see {circuit_dir / 'build.log'}")

    environment = os.environ.copy()
    environment.update(frontend.environment)
    result = subprocess.run(
        [str(executable)],
        cwd=circuit_dir,
        env=environment,
        text=True,
        capture_output=True,
        timeout=timeout,
    )
    (circuit_dir / "simulation.log").write_text(result.stdout + result.stderr)
    return result
