#!/usr/bin/env python3
"""
Compile rvprobe output assembly into RISC-V ELF/BIN/OBJDUMP artifacts.

By default, scans `rvprobe/src/cases/output/asm/**/*.S` and writes
matching `.elf`, `.bin`, and `.objdump` files under `rvprobe/src/cases/output/elf/`.

Examples:
    python3 rvprobe/scripts/asm2elf.py
    python3 rvprobe/scripts/asm2elf.py rvprobe/src/cases/output/asm/cache/DCacheHitMiss.S
    python3 rvprobe/scripts/asm2elf.py --asm-root rvprobe/src/cases/output/asm --elf-root rvprobe/src/cases/output/elf
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ASM_ROOT = REPO_ROOT / "rvprobe" / "src" / "cases" / "output" / "asm"
DEFAULT_ELF_ROOT = REPO_ROOT / "rvprobe" / "src" / "cases" / "output" / "elf"
DEFAULT_LINKER = Path(__file__).resolve().parent / "roundtrip" / "baremetal.ld"


def compile_asm(
    asm_path: Path,
    asm_root: Path,
    elf_root: Path,
    compiler: str,
    objcopy: str,
    objdump: str,
    march: str,
    mabi: str,
    linker_script: Path,
) -> tuple[Path, Path, Path]:
    try:
        relative = asm_path.relative_to(asm_root)
    except ValueError as exc:
        raise ValueError(f"{asm_path} is not under asm root {asm_root}") from exc

    artifact_base = elf_root / relative
    artifact_base.parent.mkdir(parents=True, exist_ok=True)

    elf_path = artifact_base.with_suffix(".elf")
    bin_path = artifact_base.with_suffix(".bin")
    objdump_path = artifact_base.with_suffix(".objdump")

    gcc_cmd = [
        compiler,
        "-nostdlib",
        "-nostartfiles",
        f"-march={march}",
        f"-mabi={mabi}",
        "-T",
        str(linker_script),
        "-o",
        str(elf_path),
        str(asm_path),
    ]
    subprocess.run(gcc_cmd, check=True)

    objcopy_cmd = [objcopy, "-O", "binary", str(elf_path), str(bin_path)]
    subprocess.run(objcopy_cmd, check=True)

    with objdump_path.open("w", encoding="utf-8") as f:
        objdump_cmd = [objdump, "-d", str(elf_path)]
        subprocess.run(objdump_cmd, check=True, stdout=f)

    return elf_path, bin_path, objdump_path


def collect_inputs(paths: list[str], asm_root: Path) -> list[Path]:
    if not paths:
        return sorted(asm_root.rglob("*.S"))

    collected: list[Path] = []
    for raw in paths:
        path = Path(raw).resolve()
        if path.is_dir():
            collected.extend(sorted(path.rglob("*.S")))
        elif path.suffix == ".S":
            collected.append(path)
        else:
            raise ValueError(f"unsupported input path: {raw}")
    return collected


def main() -> int:
    parser = argparse.ArgumentParser(description="Compile rvprobe assembly outputs into ELF/BIN/OBJDUMP artifacts")
    parser.add_argument("inputs", nargs="*", help="Assembly file(s) or directories. Defaults to all files under --asm-root.")
    parser.add_argument("--asm-root", default=str(DEFAULT_ASM_ROOT), help="Root directory containing input .S files")
    parser.add_argument("--elf-root", default=str(DEFAULT_ELF_ROOT), help="Root directory for output artifacts")
    parser.add_argument("--compiler", default="riscv64-unknown-linux-gnu-gcc", help="RISC-V GCC executable")
    parser.add_argument("--objcopy", default="riscv64-unknown-linux-gnu-objcopy", help="RISC-V objcopy executable")
    parser.add_argument("--objdump", default="riscv64-unknown-linux-gnu-objdump", help="RISC-V objdump executable")
    parser.add_argument("--march", default="rv64gc_zfa", help="Target ISA string passed to GCC")
    parser.add_argument("--mabi", default="lp64d", help="Target ABI string passed to GCC")
    parser.add_argument("--linker-script", default=str(DEFAULT_LINKER), help="Linker script used to build ELF before objcopy")
    args = parser.parse_args()

    asm_root = Path(args.asm_root).resolve()
    elf_root = Path(args.elf_root).resolve()
    linker_script = Path(args.linker_script).resolve()

    if not asm_root.exists():
        print(f"asm root does not exist: {asm_root}", file=sys.stderr)
        return 1
    if not linker_script.exists():
        print(f"linker script does not exist: {linker_script}", file=sys.stderr)
        return 1

    try:
        asm_files = collect_inputs(args.inputs, asm_root)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    if not asm_files:
        print(f"no .S files found under {asm_root}", file=sys.stderr)
        return 1

    failures = 0
    for asm_file in asm_files:
        try:
            output = compile_asm(
                asm_path=asm_file,
                asm_root=asm_root,
                elf_root=elf_root,
                compiler=args.compiler,
                objcopy=args.objcopy,
                objdump=args.objdump,
                march=args.march,
                mabi=args.mabi,
                linker_script=linker_script,
            )
            elf_path, bin_path, objdump_path = output
            print(
                f"[ok] {asm_file.relative_to(asm_root)} -> "
                f"{elf_path.relative_to(elf_root)}, {bin_path.relative_to(elf_root)}, {objdump_path.relative_to(elf_root)}"
            )
        except ValueError as exc:
            failures += 1
            print(f"[fail] {exc}", file=sys.stderr)
        except subprocess.CalledProcessError as exc:
            failures += 1
            print(f"[fail] {asm_file}: command exited with {exc.returncode}", file=sys.stderr)

    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
