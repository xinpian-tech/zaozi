# SPDX-License-Identifier: Apache-2.0
"""Fixed initialization source embedded by CFFI, executed on the first DPI call."""

import os
import sys
import traceback

try:
    # CFFI registers this built-in module in memory; typings supplies its API.
    from _zaozi_dpi import ffi  # pyright: ignore[reportMissingModuleSource]

    from zaozi_tb.runtime import register

    register(ffi)
except BaseException:  # noqa: BLE001 - Any driver failure must fail the simulation.
    traceback.print_exc()
    _ = sys.stdout.flush()
    _ = sys.stderr.flush()
    os._exit(1)
