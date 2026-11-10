# SPDX-License-Identifier: Apache-2.0
"""Build Python's implementation of the C API using CFFI embedding."""

from pathlib import Path

from cffi import FFI


def emit_bridge(directory):
    framework = Path(__file__).parent
    native = framework / "native"
    builder = FFI()
    builder.embedding_api((directory / "dpi_api.h").read_text())
    builder.set_source("_zaozi_dpi", (native / "python_backend.c").read_text())
    builder.embedding_init_code((framework / "embedding.py").read_text())
    source = directory / "cffi_bridge.c"
    builder.emit_c_code(str(source))
    return source
