# SPDX-License-Identifier: Apache-2.0
"""Launch the harness with the packaged toolchain or this repository's Nix shell."""

import os
import subprocess
import sys
from collections.abc import Sequence
from pathlib import Path

from .data import json_value, string_fields


def launch(arguments: Sequence[str]) -> int:
    framework = Path(__file__).resolve().parents[1]
    caller = Path.cwd()
    packaged = framework / "toolchain.json"
    if packaged.is_file():
        toolchain = string_fields(json_value(packaged.read_text()))
        environment = os.environ.copy()
        environment.update(
            PATH=toolchain["path"] + os.pathsep + environment.get("PATH", ""),
            PYTHONPATH=str(framework),
            PYTHONDONTWRITEBYTECODE="1",
            ZAOZI_TB_VERILATOR=toolchain["verilator"],
            ZAOZI_TB_CWD=str(caller),
            CIRCT_INSTALL_PATH=toolchain["circt"],
            MLIR_INSTALL_PATH=toolchain["mlir"],
            JAVA_HOME=toolchain["java"],
            JEXTRACT_INSTALL_PATH=toolchain["jextract"],
            LIBC_INCLUDE_PATH=toolchain["libc_include"],
            LIT_INSTALL_PATH=toolchain["lit"],
            Z3_LIB=toolchain["z3_lib"],
            JAVA_TOOL_OPTIONS=(environment.get("JAVA_TOOL_OPTIONS", "") + " -Xss32m"),
            COURSIER_REPOSITORIES=environment.get(
                "COURSIER_REPOSITORIES", toolchain["maven_repository"]
            ),
        )
        result = subprocess.run(
            [toolchain["python"], "-m", "zaozi_tb", *arguments],
            cwd=caller,
            env=environment,
            check=False,
        )
        return result.returncode if result.returncode >= 0 else 128 - result.returncode

    repository = framework.parents[1]

    def package(name: str) -> Path:
        result = subprocess.run(
            [
                "nix",
                "build",
                "--no-link",
                "--print-out-paths",
                f".#{name}",
            ],
            cwd=repository,
            check=True,
            text=True,
            stdout=subprocess.PIPE,
        )
        return Path(result.stdout.strip())

    try:
        python = package("testbench-python")
        verilator = package("legacyPackages.x86_64-linux.verilator")
        environment = {
            "PYTHONPATH": str(framework),
            "PYTHONDONTWRITEBYTECODE": "1",
            "ZAOZI_TB_VERILATOR": str(verilator / "bin/verilator"),
            "ZAOZI_TB_CWD": str(caller),
        }
        # Nix's shell hook runs at the repository root, never in the simulation
        # directory. The framework restores the caller's cwd before parsing paths.
        result = subprocess.run(
            [
                "nix",
                "develop",
                "-c",
                "env",
                *[f"{key}={value}" for key, value in environment.items()],
                str(python / "bin/python3"),
                "-m",
                "zaozi_tb",
                *arguments,
            ],
            cwd=repository,
            check=False,
        )
        return result.returncode if result.returncode >= 0 else 128 - result.returncode
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
