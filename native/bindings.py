"""Generate the typed raw API and the C by-value adapters packaged with a Native library."""
import argparse
from pathlib import Path
import re
import subprocess


def generate(group, header, output, binary, clang, include_paths, library_dir):
    package = f"org.llvm.{group}.raw"
    sources = output / "src"
    resources = output / "resources" / "scala-native"
    sources.mkdir(parents=True, exist_ok=True)
    resources.mkdir(parents=True, exist_ok=True)
    args = [binary, "--package", package, "--header", str(header),
            "--flavour", "scala-native05", "--clang-path", clang,
            "--render.no-comments", "--render.no-location",
            "--render.no-constructor", "MlirTransformOpInterfaceCallbacks"]
    for directory in include_paths:
        args.extend(["--clang-include", directory])
        # Nix's clang wrapper also advertises library prefixes as system
        # includes. Generate the requested APIs even in a clean Nix build.
        for api in ("mlir-c", "circt-c", "mlir", "circt"):
            api_headers = Path(directory) / api
            if api_headers.is_dir():
                args.extend(["--exclude-system-path", str(api_headers)])
    integers = ["size_t", "intptr_t", "uintptr_t"]
    integers.extend(f"{prefix}int{bits}_t" for prefix in ("", "u") for bits in (8, 16, 32, 64))
    for name in integers:
        args.extend(["--render.external-name", name + "=org.llvm.ffi.StdInt"])
    if group == "circt":
        # MSFT.h declares this symbol but the pinned CIRCT has no implementation.
        args.extend(["--render.external-name", "circtMSFTGetInstance=unavailable"])
        for pattern in ("*/mlir-c/*", "*/mlir/*"):
            args.extend(["--render.external-path", pattern + "=org.llvm.mlir.raw.all"])

    scala = sources / "Bindings.scala"
    subprocess.run([*args, "--scala", "--out", scala], check=True)
    # A raw binding can reach any dialect declared by the umbrella header.
    # Record link dependencies in NIR for consumers of the published artifact.
    prefix = group.upper() + "CAPI"
    links = sorted({f.name[3:].split(".")[0] for f in library_dir.glob("lib" + prefix + "*")
                    if f.name.endswith((".so", ".dylib", ".a"))})
    if not links:
        raise RuntimeError(f"No {prefix} libraries in {library_dir}")
    # Scala Native compiles C resources even when a downstream program uses
    # none of this group's bindings. Enable the adapters with their link flags.
    macro = f"ZAOZI_{group.upper()}_RAW"
    annotated = "\n".join([f'@define("{macro}")', *(f'@link("{name}")' for name in links), "@extern"])
    source = scala.read_text().replace("\n@extern\n", "\n" + annotated + "\n")
    # MLIR waits for pool workers that can enter Scala and allocate.
    # Let the GC suspend the caller while it is waiting inside the C++ driver.
    source = re.sub(r"(?m)^(  )(private\[raw\] def __sn_wrap_\w+_mlirPassManagerRunOnOp\()",
                    r"  @blocking\n\1\2", source)
    scala.write_text(source)

    glue = resources / f"{group}.c"
    subprocess.run([*args, "--c", "--out", glue], check=True)
    # sn-bindgen emits names after abstract function-pointer and array
    # declarators. C requires them inside (*) or before the array extent.
    c = glue.read_text()
    c = re.sub(r"\(\*\)\(([^()]*)\) (\w+)", r"(*\2)(\1)", c)
    c = re.sub(r"\b(\w+)((?:\[\d+\])+) (\w+)", r"\1 \3\2", c)
    glue.write_text(f"#ifdef {macro}\n" + header.read_text() + "\n" + c + "\n#endif\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("group", choices=("mlir", "circt"))
    parser.add_argument("header", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--binary", default="bindgen")
    parser.add_argument("--clang", default="clang")
    parser.add_argument("--include", action="append", required=True)
    parser.add_argument("--library-dir", type=Path, required=True)
    args = parser.parse_args()
    generate(args.group, args.header, args.output, args.binary, args.clang, args.include, args.library_dir)
