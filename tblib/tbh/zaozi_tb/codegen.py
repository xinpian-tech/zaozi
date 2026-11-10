# SPDX-License-Identifier: Apache-2.0
"""Generate backend-independent C API declarations from the DPI JSON."""

import re

from .data import DpiArgument, DpiFunction, array_items, object_fields

SCALARS = {
    (1, False): "unsigned char",
    (8, True): "char",
    (16, True): "short",
    (32, True): "int",
    (64, True): "long long",
}
DIRECTIONS = {"in", "out", "inout", "return"}
KEYWORDS = {
    "auto",
    "break",
    "case",
    "char",
    "const",
    "continue",
    "default",
    "do",
    "double",
    "else",
    "enum",
    "extern",
    "float",
    "for",
    "goto",
    "if",
    "inline",
    "int",
    "long",
    "register",
    "restrict",
    "return",
    "short",
    "signed",
    "sizeof",
    "static",
    "struct",
    "switch",
    "typedef",
    "union",
    "unsigned",
    "void",
    "volatile",
    "while",
    "_Bool",
    "_Complex",
    "_Imaginary",
}


def identifier(name: object) -> str:
    if (
        not isinstance(name, str)
        or not re.fullmatch(r"[A-Za-z][A-Za-z_0-9]*", name)
        or name in KEYWORDS
    ):
        raise ValueError(f"Invalid C identifier: {name!r}")
    return name


def c_type(arg: DpiArgument) -> str:
    width, signed = arg["width"], arg["signed"]
    if (
        type(width) is not int
        or type(signed) is not bool
        or (width, signed) not in SCALARS
    ):
        raise ValueError(
            f"Unsupported DPI type: {arg}; supported: bit, byte, shortint, int, longint scalars"
        )
    return SCALARS[width, signed]


def functions(interface: object) -> list[DpiFunction]:
    result: list[DpiFunction] = []
    for raw_function in array_items(object_fields(interface)["dpi_functions"]):
        function = object_fields(raw_function)
        name = identifier(function["function"])
        args: list[DpiArgument] = []
        for raw_arg in array_items(function["arguments"]):
            arg = object_fields(raw_arg)
            arg_name = identifier(arg["name"])
            width, signed, direction = arg["width"], arg["signed"], arg["direction"]
            if type(width) is not int or type(signed) is not bool:
                raise ValueError(f"Invalid DPI width or signedness: {arg}")
            if not isinstance(direction, str) or direction not in DIRECTIONS:
                raise ValueError(f"Invalid DPI direction: {arg}")
            argument: DpiArgument = {
                "name": arg_name,
                "width": width,
                "signed": signed,
                "direction": direction,
            }
            _ = c_type(argument)
            args.append(argument)
        if len({a["name"] for a in args}) != len(args):
            raise ValueError(f"Duplicate argument names in {name}")
        if sum(a["direction"] == "return" for a in args) > 1:
            raise ValueError(f"Multiple return values in {name}")
        result.append({"function": name, "arguments": args})
    names = [function["function"] for function in result]
    if len(set(names)) != len(names):
        raise ValueError("Duplicate DPI function names")
    return result


def signature(function: DpiFunction) -> str:
    params: list[str] = []
    result = "void"
    for arg in function["arguments"]:
        kind = c_type(arg)
        if arg["direction"] == "return":
            result = kind
        else:
            name = arg["name"]
            pointer = "*" if arg["direction"] in ("out", "inout") else ""
            params.append(f"{kind} {pointer}{name}")
    return f"{result} {function['function']}({', '.join(params) or 'void'})"


def generate_header(interface: object) -> str:
    return "\n".join(signature(f) + ";" for f in functions(interface)) + "\n"
