"""Dispatch CIRCT-exported DPI calls to the selected Python driver."""

import importlib.util
import json
import os
from pathlib import Path


_schema = json.loads(Path(os.environ["ZAOZI_DPI_SCHEMA"]).read_text())
_functions = {
    function.get("c_name", function.get("function", function.get("name"))): function
    for function in _schema["dpi_functions"]
}
_driver = None


def _load_driver():
    global _driver
    if _driver is None:
        path = Path(os.environ["ZAOZI_FRONTEND_DRIVER"])
        spec = importlib.util.spec_from_file_location("zaozi_frontend_driver", path)
        if spec is None or spec.loader is None:
            raise ImportError(f"cannot load Python frontend driver: {path}")
        _driver = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(_driver)
    return _driver


def _check(values, arguments, label, encode=False):
    expected = {argument["name"]: argument for argument in arguments}
    if not isinstance(values, dict) or set(values) != set(expected):
        raise ValueError(f"{label} must contain {sorted(expected)}")
    checked = {}
    for name, argument in expected.items():
        width = argument["width"]
        value = values[name]
        if encode and argument.get("signed") and isinstance(value, int) and value < 0:
            if value < -(1 << (width - 1)):
                raise ValueError(f"{name} is outside signed {width}-bit range")
            value += 1 << width
        if not isinstance(value, int) or not 0 <= value < 1 << width:
            raise ValueError(f"{name} must fit in {width} bits")
        if not encode and argument.get("signed") and value >= 1 << (width - 1):
            value -= 1 << width
        checked[name] = value
    return checked


def invoke(name, inputs):
    function = _functions[name]
    arguments = function["arguments"]
    decoded = _check(
        inputs,
        [argument for argument in arguments if argument["direction"] in ("in", "inout")],
        "DPI inputs",
    )
    outputs = getattr(_load_driver(), name)(**decoded)
    return _check(
        outputs,
        [argument for argument in arguments if argument["direction"] in ("out", "inout", "return")],
        f"DPI {name} result",
        encode=True,
    )
