#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Place beside the generated SyncQueueTestBench inputs and dpi_impl.py."""

import subprocess
import sys
from pathlib import Path


def main():
    here = Path(__file__).resolve().parent
    repository = next(
        parent for parent in here.parents if (parent / "tblib/python/run.py").is_file()
    )
    result = subprocess.run(
        [
            sys.executable,
            "-B",
            str(repository / "tblib/python/run.py"),
            str(here / "dpi_impl.py"),
            "--mlir",
            str(here / "SyncQueueTestBench.mlirbc"),
            "--interface",
            str(here / "SyncQueueTestBench.json"),
            "--top",
            "SyncQueueTestBench",
            "--out",
            str(here),
            *sys.argv[1:],
        ],
        check=False,
    )
    return result.returncode if result.returncode >= 0 else 128 - result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
