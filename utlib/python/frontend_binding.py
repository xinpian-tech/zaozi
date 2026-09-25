# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
"""Dispatch DPI calls to the selected Python driver using the exported schema."""

import importlib
import json
import os
from pathlib import Path


_schema = json.loads(Path(os.environ["ZAOZI_DPI_SCHEMA"]).read_text())
_spec = {function["name"]: function["arguments"] for function in _schema["dpi_functions"]}
_driver = None


def _decode(value, width, signed):
    value &= (1 << width) - 1
    if signed and value & (1 << (width - 1)):
        value -= 1 << width
    return value


def _encode(value, width, signed):
    if not isinstance(value, int):
        raise TypeError(f"DPI result must be an integer, got {type(value).__name__}")
    low = -(1 << (width - 1)) if signed else 0
    high = (1 << (width - 1)) - 1 if signed else (1 << width) - 1
    if not low <= value <= high:
        raise ValueError(f"DPI value {value} outside {width}-bit range")
    return value & ((1 << width) - 1)


def dispatch(name, *raw_inputs):
    global _driver
    if _driver is None:
        _driver = importlib.import_module(os.environ["ZAOZI_FRONTEND_MODULE"])
    arguments = _spec[name]
    inputs = [arg for arg in arguments if arg["direction"] in ("in", "inout")]
    if len(raw_inputs) != len(inputs):
        raise ValueError(f"{name} expected {len(inputs)} inputs, got {len(raw_inputs)}")
    kwargs = {arg["name"]: _decode(raw, arg["width"], arg["signed"])
              for arg, raw in zip(inputs, raw_inputs)}
    result = getattr(_driver, name)(**kwargs)
    if not isinstance(result, dict):
        raise TypeError(f"{name} must return a dict")
    outputs = [arg for arg in arguments if arg["direction"] in ("out", "inout", "return")]
    expected = {arg["name"] for arg in outputs}
    if result.keys() != expected:
        raise ValueError(f"{name} must return keys {sorted(expected)}")
    return tuple(_encode(result[arg["name"]], arg["width"], arg["signed"])
                 for arg in outputs)
