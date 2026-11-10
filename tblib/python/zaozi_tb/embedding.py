# SPDX-License-Identifier: Apache-2.0
"""Fixed initialization source embedded by CFFI, executed on the first DPI call."""

import os
import sys
import traceback

try:
    from _zaozi_dpi import ffi

    from zaozi_tb.runtime import register

    register(ffi)
except BaseException:
    traceback.print_exc()
    sys.stdout.flush()
    sys.stderr.flush()
    os._exit(1)
