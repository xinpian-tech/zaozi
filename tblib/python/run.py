#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Command-line entry point for the Python testbench framework."""

import sys


def main():
    # Disable bytecode caches before loading the framework from this directory.
    sys.dont_write_bytecode = True
    from zaozi_tb.launcher import launch

    return launch(sys.argv[1:])


if __name__ == "__main__":
    raise SystemExit(main())
