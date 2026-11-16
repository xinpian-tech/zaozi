#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Command-line entry point for the Python testbench framework."""

import sys
from pathlib import Path


def main() -> int:
    # Disable bytecode caches before loading the framework from this directory.
    sys.dont_write_bytecode = True
    from zaozi_tb.launcher import launch

    arguments = sys.argv[1:]
    script_directory = Path(sys.argv[0]).absolute().parent.resolve()
    if script_directory != Path(__file__).resolve().parent:
        arguments = [str(script_directory), *arguments]
    return launch(arguments)


if __name__ == "__main__":
    raise SystemExit(main())
