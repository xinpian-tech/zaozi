# SPDX-License-Identifier: Apache-2.0
"""Provision the pinned toolchain and launch the framework without shell glue."""

import subprocess
import sys
from pathlib import Path


def launch(arguments):
    framework = Path(__file__).resolve().parents[1]
    repository = framework.parents[1]
    caller = Path.cwd()

    def package(name):
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
