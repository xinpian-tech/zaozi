# SPDX-License-Identifier: Apache-2.0
"""Generate backend-independent C API declarations from the DPI JSON."""

import re

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


def identifier(name):
    if (
        not isinstance(name, str)
        or not re.fullmatch(r"[A-Za-z][A-Za-z_0-9]*", name)
        or name in KEYWORDS
    ):
        raise ValueError(f"Invalid C identifier: {name!r}")


def c_type(arg):
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


def functions(interface):
    result = interface["dpi_functions"]
    names = []
    for function in result:
        name = function["function"]
        identifier(name)
        names.append(name)
        args = function["arguments"]
        for arg in args:
            identifier(arg["name"])
            c_type(arg)
            if arg["direction"] not in DIRECTIONS:
                raise ValueError(f"Invalid DPI direction: {arg}")
        if len({a["name"] for a in args}) != len(args):
            raise ValueError(f"Duplicate argument names in {name}")
        if sum(a["direction"] == "return" for a in args) > 1:
            raise ValueError(f"Multiple return values in {name}")
    if len(set(names)) != len(names):
        raise ValueError("Duplicate DPI function names")
    return result


def signature(function):
    params = []
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


def generate_header(interface):
    return "\n".join(signature(f) + ";" for f in functions(interface)) + "\n"
