# SPDX-License-Identifier: Apache-2.0
"""Prepare generated design artifacts and identify their top directly from IR."""

import os
import re
import shutil
import subprocess
import tempfile
from collections.abc import Mapping, Sequence
from pathlib import Path

from .data import array_items, json_value


def ir_top(path: Path) -> str:
    """Read the unique public HW hierarchy root from CIRCT's canonical IR printer."""
    result = subprocess.run(
        ["circt-opt", str(path)], check=True, capture_output=True, text=True
    )
    text = result.stdout
    if re.search(r"^\s*firrtl\.circuit\b", text, re.MULTILINE):
        raise ValueError(
            "Expected a complete HW testbench; supply DUT and clock IR through tbh"
        )
    # Symbols can be bare or quoted (MLIR uses hexadecimal escapes in quoted strings).
    symbol = r'@("(?:[^"\\]|\\.)*"|[A-Za-z_$][\w$.\-]*)'

    def decode(value: str) -> str:
        if not value.startswith('"'):
            return value
        value = value[1:-1]
        return re.sub(
            r'\\([0-9A-Fa-f]{2}|["\\])',
            lambda match: chr(int(match[1], 16)) if len(match[1]) == 2 else match[1],
            value,
        )

    modules = {
        decode(match[1])
        for match in re.finditer(
            r"^  hw\.module (?:public )?" + symbol + r"\(", text, re.MULTILINE
        )
    }
    instantiated = {
        decode(match[1])
        for match in re.finditer(
            r"\bhw\.instance\b[^\n]*?" + symbol + r"(?:<[^\n]*?>)?\(", text
        )
    }
    roots = sorted(modules - instantiated)
    if len(roots) != 1:
        raise ValueError(
            "Cannot infer a unique testbench top from IR; "
            + f"ambiguous design (roots: {', '.join(roots)})"
        )
    return roots[0]


def elaborate(command: Sequence[str], output: Path, env: Mapping[str, str]) -> Path:
    """Publish only this invocation's artifacts and return its complete testbench IR."""
    print("Elaborating testbench...", flush=True)
    generation_env = dict(env)
    native_paths = ":".join(
        str(Path(env[name]) / "lib")
        for name in ("MLIR_INSTALL_PATH", "CIRCT_INSTALL_PATH")
    )
    generation_env["JAVA_TOOL_OPTIONS"] = (
        env.get("JAVA_TOOL_OPTIONS", "")
        + " --enable-native-access=ALL-UNNAMED -Djava.library.path="
        + native_paths
    )
    # Older parameterizations in the output directory cannot become this run's input.
    staging = output / ".build"
    staging.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="elaborate-", dir=staging) as temporary:
        generated = Path(temporary)
        generation_env["ZAOZI_OUTDIR"] = str(generated)
        _ = subprocess.run(command, cwd=Path.cwd(), env=generation_env, check=True)
        bytecodes = list(generated.glob("*.mlirbc"))
        if len(bytecodes) != 1:
            raise ValueError("Generation must produce exactly one complete testbench bytecode file")
        mlir = output / bytecodes[0].name
        for artifact in generated.iterdir():
            if artifact.is_file():
                _ = shutil.copy2(artifact, output / artifact.name)
    return mlir


def scala_command(source: Path, arguments: Sequence[str]) -> list[str]:
    """Compile the selected source against tblib, without guessing a Mill module."""
    result = subprocess.run(
        ["mill", "--no-daemon", "show", "tblib.runClasspath"],
        check=True, stdout=subprocess.PIPE, text=True,
    )
    classpath: list[str] = []
    for entry in array_items(json_value(result.stdout)):
        if not isinstance(entry, str):
            raise ValueError("Invalid Mill classpath")
        # Mill PathRefs include their content hash before the absolute path.
        classpath.append(entry[entry.index("/"):])
    version = json_value(subprocess.check_output(
        ["mill", "--no-daemon", "show", "tblib.scalaVersion"], text=True,
    ))
    if not isinstance(version, str):
        raise ValueError("Invalid Scala version")
    package = re.search(r"^\s*package\s+([A-Za-z_][\w.]*)", source.read_text(), re.MULTILINE)
    main = (package[1] + "." if package else "") + source.stem
    return [
        "scala-cli", "--server=false", "--java-home=" + os.environ["JAVA_HOME"],
        "--extra-jars=" + os.pathsep.join(classpath), "--scala-version=" + version,
        "-O=-experimental", "--main-class", main, str(source),
        "--", *arguments,
    ]
