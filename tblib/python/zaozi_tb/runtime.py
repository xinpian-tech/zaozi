# SPDX-License-Identifier: Apache-2.0
"""Register Python implementations of a JSON-described C API with CFFI."""

import importlib.util
import json
import os
import sys
import traceback
from pathlib import Path

from .codegen import functions


def fail(exc_type, exc, tb):
    traceback.print_exception(exc_type, exc, tb)
    sys.stdout.flush()
    sys.stderr.flush()
    os._exit(1)


def from_c(argument, value):
    # CFFI represents plain C char as bytes; CIRCT uses char for signed SV byte.
    if argument["width"] == 8:
        return int.from_bytes(value, signed=True)
    return value


def to_c(argument, value):
    if argument["width"] == 8:
        return bytes([value & 0xFF])
    return value


def make_callback(function, implementation):
    arguments = function["arguments"]
    parameters = [arg for arg in arguments if arg["direction"] != "return"]
    outputs = [arg for arg in arguments if arg["direction"] != "in"]
    expected = {arg["name"] for arg in outputs}

    def callback(*values):
        inputs = {}
        for arg, value in zip(parameters, values, strict=True):
            if arg["direction"] == "in":
                inputs[arg["name"]] = from_c(arg, value)
            elif arg["direction"] == "inout":
                inputs[arg["name"]] = from_c(arg, value[0])
        result = implementation(**inputs)
        if not isinstance(result, dict) or set(result) != expected:
            raise ValueError(
                f"{function['function']} must return exactly these fields: {sorted(expected)}"
            )
        for arg in outputs:
            value = result[arg["name"]]
            width, signed = arg["width"], arg["signed"]
            low = -(1 << (width - 1)) if signed else 0
            high = (1 << (width - int(signed))) - 1
            if not isinstance(value, int) or not low <= value <= high:
                raise ValueError(f"{arg['name']}={value!r} is outside {low}..{high}")
        for arg, value in zip(parameters, values, strict=True):
            if arg["direction"] in ("out", "inout"):
                value[0] = to_c(arg, result[arg["name"]])
        for arg in outputs:
            if arg["direction"] == "return":
                return to_c(arg, result[arg["name"]])
        return None

    return callback


def register(ffi):
    path = Path(os.environ["ZAOZI_TB_DRIVER"])
    sys.path.insert(0, str(path.parent))
    spec = importlib.util.spec_from_file_location("_zaozi_user_driver", path)
    if spec is None or spec.loader is None:
        raise ImportError(f"Cannot load Python driver: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    interface = json.loads(Path(os.environ["ZAOZI_TB_INTERFACE"]).read_text())
    bindings = json.loads(os.environ["ZAOZI_TB_BINDINGS"])
    for function in functions(interface):
        name = function["function"]
        target = bindings[name]
        implementation = getattr(module, target)
        if not callable(implementation):
            raise TypeError(f"{target} in {path} is not callable")
        ffi.def_extern(name=name, onerror=fail)(make_callback(function, implementation))
