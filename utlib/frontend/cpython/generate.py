"""Generate the DPI descriptors consumed by the CPython bridge."""

from frontend.schema import (
    checked,
    kind_and_type,
    load_functions,
    split_argument,
    split_function,
)


def generate(schema_path, build_dir):
    lines = ["/* Generated from CIRCT exportDPIInterface JSON. */"]
    for function in load_functions(schema_path):
        symbol, c_name, arguments, result = split_function(function)
        result_name = checked(result.get("name"))
        result_kind, result_type = kind_and_type(result.get("width"))
        if result_kind == "PACKED" and result["width"] > 32:
            raise ValueError(
                f"packed DPI return wider than 32 bits is unsupported: {symbol}"
            )
        parameters = []
        inputs = []
        outputs = []
        for argument in arguments:
            name, direction, width, kind, c_type = split_argument(argument)
            pointer = direction != "in" or kind == "PACKED"
            const = "const " if direction == "in" and kind == "PACKED" else ""
            parameters.append(f"{const}{c_type}{' *' if pointer else ' '}zaozi_arg_{name}")
            if direction in ("in", "inout"):
                macro = "INOUT" if direction == "inout" else "INPUT"
                inputs.append(f"ZAOZI_DPI_{macro}_{kind}({name}, {width})")
            if direction in ("out", "inout"):
                outputs.append(f"ZAOZI_DPI_OUTPUT_{kind}({name}, {width})")
        lines.append(f"ZAOZI_DPI_BEGIN({result_type}, {c_name}, ({', '.join(parameters)}))")
        lines.extend(inputs)
        lines.append(f'ZAOZI_DPI_CALL("{c_name}")')
        lines.extend(outputs)
        lines.append(f"ZAOZI_DPI_RETURN({result_name}, {result['width']})")
        lines.append("ZAOZI_DPI_END()")
    (build_dir / "zaozi_dpi.def").write_text("\n".join(lines) + "\n")
