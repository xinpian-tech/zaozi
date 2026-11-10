# SPDX-License-Identifier: Apache-2.0
"""Register Python implementations of a JSON-described C API with CFFI."""

import importlib.util
import inspect
import os
import sys
import traceback
from collections.abc import Callable
from pathlib import Path
from types import TracebackType
from typing import NoReturn, Protocol, TypeVar, cast

from .codegen import functions
from .data import DpiArgument, DpiFunction, json_value, object_fields, string_fields

Scalar = int | bytes
Implementation = Callable[..., object]
Callback = Callable[..., Scalar | None]
ErrorHandler = Callable[[type[BaseException], BaseException, TracebackType], object]
_Callback = TypeVar("_Callback", bound=Callback)


class Pointer(Protocol):
    def __getitem__(self, index: int, /) -> object: ...
    def __setitem__(self, index: int, value: Scalar, /) -> None: ...


class CallbackRegistry(Protocol):
    def def_extern(
        self, *, name: str, onerror: ErrorHandler
    ) -> Callable[[_Callback], _Callback]: ...


def fail(
    exc_type: type[BaseException], exc: BaseException, tb: TracebackType
) -> NoReturn:
    traceback.print_exception(exc_type, exc, tb)
    _ = sys.stdout.flush()
    _ = sys.stderr.flush()
    os._exit(1)


def from_c(argument: DpiArgument, value: object) -> int:
    # CFFI represents plain C char as bytes; CIRCT uses char for signed SV byte.
    if argument["width"] == 8:
        if not isinstance(value, bytes):
            raise TypeError("Expected a CFFI char as bytes")
        return int.from_bytes(value, signed=True)
    if not isinstance(value, int):
        raise TypeError("Expected an integer DPI scalar")
    return value


def to_c(argument: DpiArgument, value: int) -> Scalar:
    if argument["width"] == 8:
        return bytes([value & 0xFF])
    return value


def make_callback(function: DpiFunction, implementation: Implementation) -> Callback:
    arguments = function["arguments"]
    parameters = [arg for arg in arguments if arg["direction"] != "return"]
    outputs = [arg for arg in arguments if arg["direction"] != "in"]
    expected = {arg["name"] for arg in outputs}

    def callback(*values: object) -> Scalar | None:
        inputs: dict[str, int] = {}
        for arg, value in zip(parameters, values, strict=True):
            if arg["direction"] == "in":
                inputs[arg["name"]] = from_c(arg, value)
            elif arg["direction"] == "inout":
                inputs[arg["name"]] = from_c(arg, cast(Pointer, value)[0])
        result = implementation(**inputs)
        fields = object_fields(result)
        if set(fields) != expected:
            raise ValueError(
                f"{function['function']} must return exactly these fields: {sorted(expected)}"
            )
        checked: dict[str, int] = {}
        for arg in outputs:
            value = fields[arg["name"]]
            width, signed = arg["width"], arg["signed"]
            low = -(1 << (width - 1)) if signed else 0
            high = (1 << (width - int(signed))) - 1
            if not isinstance(value, int) or not low <= value <= high:
                raise ValueError(f"{arg['name']}={value!r} is outside {low}..{high}")
            checked[arg["name"]] = value
        for arg, value in zip(parameters, values, strict=True):
            if arg["direction"] in ("out", "inout"):
                cast(Pointer, value)[0] = to_c(arg, checked[arg["name"]])
        for arg in outputs:
            if arg["direction"] == "return":
                return to_c(arg, checked[arg["name"]])
        return None

    return callback


def register(ffi: CallbackRegistry) -> None:
    path = Path(os.environ["ZAOZI_TB_DRIVER"])
    sys.path.insert(0, str(path.parent))
    spec = importlib.util.spec_from_file_location("_zaozi_user_driver", path)
    if spec is None or spec.loader is None:
        raise ImportError(f"Cannot load Python driver: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    interface = json_value(Path(os.environ["ZAOZI_TB_INTERFACE"]).read_text())
    bindings = string_fields(json_value(os.environ["ZAOZI_TB_BINDINGS"]))
    for function in functions(interface):
        name = function["function"]
        target = bindings[name]
        implementation = cast(object, getattr(module, target))
        if not callable(implementation):
            raise TypeError(f"{target} in {path} is not callable")
        inputs = {
            arg["name"]: 0
            for arg in function["arguments"]
            if arg["direction"] in ("in", "inout")
        }
        _ = inspect.signature(implementation).bind(**inputs)
        _ = ffi.def_extern(name=name, onerror=fail)(
            make_callback(function, implementation)
        )
