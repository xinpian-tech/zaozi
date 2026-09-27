"""Shared validation and C ABI types for CIRCT-exported DPI schemas."""

import json
import re


IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z_0-9]*\Z")
SCALARS = {8: "char", 16: "short", 32: "int", 64: "long long"}


def checked(name):
    if not isinstance(name, str) or not IDENTIFIER.fullmatch(name):
        raise ValueError(f"invalid DPI identifier: {name}")
    return name


def kind_and_type(width):
    if not isinstance(width, int) or not 1 <= width <= 64:
        raise ValueError(f"unsupported DPI width: {width}")
    if width == 1:
        return "BIT", "svBit"
    if width in SCALARS:
        return "SCALAR", SCALARS[width]
    return "PACKED", "svBitVecVal"


def load_functions(schema_path):
    schema = json.loads(schema_path.read_text())
    functions = schema.get("dpi_functions")
    if not isinstance(functions, list):
        raise ValueError("DPI schema has no dpi_functions array")
    return functions


def split_function(function):
    symbol = checked(function.get("name", function.get("function")))
    c_name = checked(function.get("c_name", function.get("function", symbol)))
    arguments = function.get("arguments")
    if not isinstance(arguments, list):
        raise ValueError(f"DPI function {symbol} has no arguments array")
    returns = [argument for argument in arguments if argument.get("direction") == "return"]
    if len(returns) != 1 or not arguments or arguments[-1] is not returns[0]:
        raise ValueError(f"DPI function {symbol} needs one final return argument")
    return symbol, c_name, arguments[:-1], returns[0]


def split_argument(argument):
    if "type" in argument:
        raise ValueError(f"unsupported DPI argument: {argument}")
    name = checked(argument.get("name"))
    direction = argument.get("direction")
    if direction not in ("in", "out", "inout"):
        raise ValueError(f"unsupported DPI direction: {direction}")
    width = argument.get("width")
    kind, c_type = kind_and_type(width)
    return name, direction, width, kind, c_type
