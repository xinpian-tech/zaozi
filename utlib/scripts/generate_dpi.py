#!/usr/bin/env python3
"""Generate DPI macro definitions from CIRCT JSON."""

import argparse
import json
import re
from pathlib import Path


IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z_0-9]*\Z")
DIRECTIONS = {"in", "out", "inout", "return"}


def checked_name(value):
    if not isinstance(value, str) or not IDENTIFIER.fullmatch(value):
        raise ValueError(f"invalid C/Python identifier: {value!r}")
    return value


def validate(schema):
    if schema.get("version") != 1:
        raise ValueError("unsupported DPI schema version")
    dut = schema["dut"]
    checked_name(dut["module"])
    for port in dut["ports"]:
        checked_name(port["name"])
        if port["direction"] not in {"input", "output"}:
            raise ValueError("unsupported DUT port direction")
        if not isinstance(port["width"], int) or not 1 <= port["width"] <= 64:
            raise ValueError("unsupported DUT port width")
    functions = schema["dpi_functions"]
    if not functions:
        raise ValueError("DPI schema has no functions")
    for func in functions:
        checked_name(func["name"])
        checked_name(func["c_name"])
        names = set()
        returns = []
        for arg in func["arguments"]:
            name = checked_name(arg["name"])
            if name in names:
                raise ValueError(f"duplicate DPI argument: {name}")
            names.add(name)
            if arg["direction"] not in DIRECTIONS:
                raise ValueError("unsupported DPI direction")
            if not isinstance(arg["width"], int) or not 1 <= arg["width"] <= 64:
                raise ValueError("unsupported DPI width")
            abi = arg.get("abi", "bit" if arg["width"] == 1 else "packed")
            if abi not in {"bit", "scalar", "packed"}:
                raise ValueError(f"unsupported DPI ABI: {abi}")
            if (abi == "bit") != (arg["width"] == 1):
                raise ValueError("bit DPI ABI requires width 1")
            if abi == "scalar" and arg["width"] not in {8, 16, 32, 64}:
                raise ValueError("scalar DPI ABI requires width 8, 16, 32, or 64")
            if arg["direction"] == "return":
                returns.append(arg)
        if len(returns) != 1 or returns[0]["width"] != 32 or func["arguments"][-1] is not returns[0]:
            raise ValueError("MVP requires a final 32-bit status return")


def argument_macro(arg):
    abi = arg.get("abi", "bit" if arg["width"] == 1 else "packed")
    return f"ZAOZI_DPI_ARG_{arg['direction'].upper()}_{abi.upper()}({arg['name']}, {arg['width']})"


def definitions(schema):
    lines = ["/* Generated DPI metadata. Included by dpi_bridge.cpp. */"]
    for func in schema["dpi_functions"]:
        arguments = func["arguments"]
        parameters = ", ".join(argument_macro(arg) for arg in arguments if arg["direction"] != "return")
        inputs = [arg for arg in arguments if arg["direction"] in {"in", "inout"}]
        outputs = [arg for arg in arguments if arg["direction"] in {"out", "inout", "return"}]
        lines.append(f"ZAOZI_DPI_BEGIN({func['name']}, {func['c_name']}, ({parameters}), {len(inputs)})")
        for arg in inputs:
            abi = arg.get("abi", "bit" if arg["width"] == 1 else "packed")
            direction = "INOUT" if arg["direction"] == "inout" else "IN"
            lines.append(f"ZAOZI_DPI_INPUT_{direction}_{abi.upper()}({arg['name']}, {arg['width']})")
        lines.append(f"ZAOZI_DPI_CALL({len(outputs)})")
        for arg in outputs:
            abi = arg.get("abi", "bit" if arg["width"] == 1 else "packed")
            if arg["direction"] == "return":
                lines.append(f"ZAOZI_DPI_RETURN({arg['name']}, {arg['width']})")
            else:
                lines.append(f"ZAOZI_DPI_OUTPUT_{abi.upper()}({arg['name']}, {arg['width']})")
        lines.append("ZAOZI_DPI_END()")
    return "\n".join(lines) + "\n"


def generate(schema_path, output_dir):
    schema = json.loads(Path(schema_path).read_text())
    validate(schema)
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    for old_name in ("zaozi_dpi.h", "zaozi_dpi.cpp", "frontend_binding.py"):
        (output / old_name).unlink(missing_ok=True)
    (output / "zaozi_dpi.def").write_text(definitions(schema))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("schema", type=Path)
    parser.add_argument("output_dir", type=Path)
    args = parser.parse_args()
    generate(args.schema, args.output_dir)
