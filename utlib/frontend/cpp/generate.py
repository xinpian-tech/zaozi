"""Generate C++ declarations for CIRCT-exported DPI functions."""

from frontend.schema import (
    checked,
    kind_and_type,
    load_functions,
    split_argument,
    split_function,
)


def generate(schema_path, build_dir):
    lines = [
        "// Generated from CIRCT exportDPIInterface JSON.",
        "#pragma once",
        "",
        "#include <svdpi.h>",
        "",
        'extern "C" {',
    ]
    for function in load_functions(schema_path):
        symbol, c_name, arguments, result = split_function(function)
        checked(result.get("name"))
        result_kind, result_type = kind_and_type(result.get("width"))
        if result_kind == "PACKED" and result["width"] > 32:
            raise ValueError(
                f"packed DPI return wider than 32 bits is unsupported: {symbol}"
            )
        parameters = []
        for argument in arguments:
            name, direction, _, kind, c_type = split_argument(argument)
            pointer = direction != "in" or kind == "PACKED"
            const = "const " if direction == "in" and kind == "PACKED" else ""
            parameters.append(f"{const}{c_type}{' *' if pointer else ' '}zaozi_arg_{name}")
        lines.append(f"{result_type} {c_name}({', '.join(parameters) or 'void'});")
    lines.extend(["", '}  // extern "C"'])
    (build_dir / "zaozi_dpi.hpp").write_text("\n".join(lines) + "\n")
