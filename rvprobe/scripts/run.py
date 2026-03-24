#!/usr/bin/env python3
"""
Batch-regenerate rvprobe output artifacts.

This script does three phases:
1) Regenerate output assembly (.S) from all @main case generators.
2) Rebuild ELF/BIN/OBJDUMP from output assembly (in parallel).
3) Optionally run spike on selected ELF test cases.

Examples:
  python3 rvprobe/scripts/run.py
  python3 rvprobe/scripts/run.py --include 'PrivilegeSv39'
  python3 rvprobe/scripts/run.py --skip-asm --elf-workers 16
  python3 rvprobe/scripts/run.py --asm-workers 4 --elf-workers 16
  python3 rvprobe/scripts/run.py --include 'PMP' --skip-asm --skip-elf --run-spike --spike-command "nix shell --extra-experimental-features 'nix-command flakes' nixpkgs#spike -c spike"
"""

from __future__ import annotations

import argparse
import os
import re
import shlex
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path

from asm2elf import DEFAULT_LINKER, compile_asm


REPO_ROOT = Path(__file__).resolve().parents[2]
CASES_ROOT = REPO_ROOT / "rvprobe" / "src" / "cases"
ASM_ROOT = REPO_ROOT / "rvprobe" / "src" / "cases" / "output" / "asm"
ELF_ROOT = REPO_ROOT / "rvprobe" / "src" / "cases" / "output" / "elf"

PACKAGE_RE = re.compile(r"^\s*package\s+([\w\.]+)", re.M)
MAIN_RE = re.compile(r"@main\s+def\s+([A-Za-z0-9_]+)\s*\(")


@dataclass(frozen=True)
class CaseMain:
    fqcn: str
    asm_path: Path


def discover_case_mains(cases_root: Path, asm_root: Path) -> list[CaseMain]:
    mains: list[CaseMain] = []
    for scala in sorted(cases_root.rglob("*.scala")):
        # ignore generated output trees
        if "/output/" in str(scala):
            continue
        text = scala.read_text(encoding="utf-8")
        pkg_m = PACKAGE_RE.search(text)
        if pkg_m is None:
            continue
        pkg = pkg_m.group(1)
        subdir = scala.parent.name
        for m in MAIN_RE.finditer(text):
            main_name = m.group(1)
            fqcn = f"{pkg}.{main_name}"
            asm_path = asm_root / subdir / f"{main_name}.S"
            mains.append(CaseMain(fqcn=fqcn, asm_path=asm_path))
    return mains


def run_main(case: CaseMain, workdir: Path) -> tuple[int, str]:
    case.asm_path.parent.mkdir(parents=True, exist_ok=True)
    cmd = ["mill", "-i", "rvprobe.runMain", case.fqcn, str(case.asm_path)]
    proc = subprocess.run(
        cmd,
        cwd=workdir,
        text=True,
        capture_output=True,
    )
    output = (proc.stdout or "") + (proc.stderr or "")
    return proc.returncode, output


def regenerate_asm(cases: list[CaseMain], workdir: Path, workers: int) -> None:
    total = len(cases)
    print(f"[asm] total cases: {total}, workers: {workers}")
    failures: list[tuple[CaseMain, str]] = []

    if workers <= 1:
        for idx, case in enumerate(cases, 1):
            rc, out = run_main(case, workdir)
            if rc == 0:
                print(f"[asm][ok] {idx}/{total} {case.fqcn}")
            else:
                print(f"[asm][fail] {idx}/{total} {case.fqcn}")
                failures.append((case, out))
                break
    else:
        future_map = {}
        with ThreadPoolExecutor(max_workers=workers) as ex:
            for case in cases:
                future_map[ex.submit(run_main, case, workdir)] = case
            done_count = 0
            for fut in as_completed(future_map):
                case = future_map[fut]
                done_count += 1
                try:
                    rc, out = fut.result()
                except Exception as exc:  # pragma: no cover
                    failures.append((case, f"unexpected exception: {exc}"))
                    print(f"[asm][fail] {done_count}/{total} {case.fqcn}")
                    continue
                if rc == 0:
                    print(f"[asm][ok] {done_count}/{total} {case.fqcn}")
                else:
                    print(f"[asm][fail] {done_count}/{total} {case.fqcn}")
                    failures.append((case, out))

    if failures:
        case, out = failures[0]
        print("\n[asm] first failure details:", file=sys.stderr)
        print(f"case: {case.fqcn}", file=sys.stderr)
        print(f"asm:  {case.asm_path}", file=sys.stderr)
        print(out[-8000:], file=sys.stderr)
        raise SystemExit(1)


def compile_one_elf(
    case: CaseMain,
    asm_root: Path,
    elf_root: Path,
    compiler: str,
    objcopy: str,
    objdump: str,
    march: str,
    mabi: str,
    linker_script: Path,
) -> tuple[Path, Path, Path]:
    return compile_asm(
        asm_path=case.asm_path,
        asm_root=asm_root,
        elf_root=elf_root,
        compiler=compiler,
        objcopy=objcopy,
        objdump=objdump,
        march=march,
        mabi=mabi,
        linker_script=linker_script,
    )


def case_elf_path(case: CaseMain, asm_root: Path, elf_root: Path) -> Path:
    relative = case.asm_path.relative_to(asm_root)
    return (elf_root / relative).with_suffix(".elf")


def run_spike_case(
    case: CaseMain,
    asm_root: Path,
    elf_root: Path,
    workdir: Path,
    spike_cmd: list[str],
    timeout_sec: float,
    log_dir: Path | None,
) -> tuple[str, str, Path]:
    elf_path = case_elf_path(case, asm_root, elf_root)
    if not elf_path.exists():
        return "MISSING", f"ELF not found: {elf_path}", elf_path

    cmd = [*spike_cmd, str(elf_path)]
    try:
        proc = subprocess.run(
            cmd,
            cwd=workdir,
            text=True,
            capture_output=True,
            timeout=timeout_sec,
        )
        output = (proc.stdout or "") + (proc.stderr or "")
        status = "PASS" if proc.returncode == 0 else f"FAIL({proc.returncode})"
    except subprocess.TimeoutExpired as exc:
        output = (exc.stdout or "") + (exc.stderr or "")
        status = "TIMEOUT"

    if log_dir is not None:
        rel_log = case.asm_path.relative_to(asm_root).with_suffix(".log")
        log_path = log_dir / rel_log
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text(output, encoding="utf-8")

    return status, output, elf_path


def regenerate_spike(
    cases: list[CaseMain],
    asm_root: Path,
    elf_root: Path,
    workdir: Path,
    workers: int,
    spike_cmd: list[str],
    timeout_sec: float,
    log_dir: Path | None = None,
    results_file: Path | None = None,
) -> None:
    total = len(cases)
    print(f"[spike] total cases: {total}, workers: {workers}, timeout: {timeout_sec}s")
    if log_dir is not None:
        log_dir.mkdir(parents=True, exist_ok=True)

    rows: list[tuple[str, Path, CaseMain, str]] = []

    future_map = {}
    with ThreadPoolExecutor(max_workers=workers) as ex:
        for case in cases:
            future_map[
                ex.submit(
                    run_spike_case,
                    case,
                    asm_root,
                    elf_root,
                    workdir,
                    spike_cmd,
                    timeout_sec,
                    log_dir,
                )
            ] = case

        done_count = 0
        for fut in as_completed(future_map):
            case = future_map[fut]
            done_count += 1
            try:
                status, out, elf_path = fut.result()
            except Exception as exc:  # pragma: no cover
                status = "ERROR"
                out = f"unexpected exception: {exc}"
                elf_path = case_elf_path(case, asm_root, elf_root)
            rows.append((status, elf_path, case, out))
            print(f"[spike][{status.lower()}] {done_count}/{total} {elf_path.relative_to(elf_root)}")

    if results_file is not None:
        results_file.parent.mkdir(parents=True, exist_ok=True)
        lines = [f"{status}\t{elf}\t{case.fqcn}" for status, elf, case, _ in sorted(rows, key=lambda r: str(r[1]))]
        results_file.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
        print(f"[spike] wrote results: {results_file}")

    counts: dict[str, int] = {}
    for status, _, _, _ in rows:
        counts[status] = counts.get(status, 0) + 1

    summary = ", ".join(f"{k}={v}" for k, v in sorted(counts.items()))
    print(f"[spike] summary: {summary if summary else 'no results'}")

    failed = [(status, elf, case, out) for status, elf, case, out in rows if status != "PASS"]
    if failed:
        status, elf, case, out = failed[0]
        print("\n[spike] first non-pass details:", file=sys.stderr)
        print(f"status: {status}", file=sys.stderr)
        print(f"case:   {case.fqcn}", file=sys.stderr)
        print(f"elf:    {elf}", file=sys.stderr)
        print((out or "")[-8000:], file=sys.stderr)
        raise SystemExit(1)


def regenerate_elf(
    cases: list[CaseMain],
    asm_root: Path,
    elf_root: Path,
    workers: int,
    compiler: str,
    objcopy: str,
    objdump: str,
    march: str,
    mabi: str,
    linker_script: Path,
) -> None:
    total = len(cases)
    print(f"[elf] total cases: {total}, workers: {workers}")
    failures: list[tuple[CaseMain, str]] = []

    future_map = {}
    with ThreadPoolExecutor(max_workers=workers) as ex:
        for case in cases:
            future_map[
                ex.submit(
                    compile_one_elf,
                    case,
                    asm_root,
                    elf_root,
                    compiler,
                    objcopy,
                    objdump,
                    march,
                    mabi,
                    linker_script,
                )
            ] = case

        done_count = 0
        for fut in as_completed(future_map):
            case = future_map[fut]
            done_count += 1
            try:
                elf, binf, obj = fut.result()
                print(
                    f"[elf][ok] {done_count}/{total} {case.asm_path.relative_to(asm_root)} -> "
                    f"{elf.relative_to(elf_root)}, {binf.relative_to(elf_root)}, {obj.relative_to(elf_root)}"
                )
            except Exception as exc:
                print(f"[elf][fail] {done_count}/{total} {case.asm_path.relative_to(asm_root)}")
                failures.append((case, str(exc)))

    if failures:
        case, reason = failures[0]
        print("\n[elf] first failure details:", file=sys.stderr)
        print(f"case: {case.fqcn}", file=sys.stderr)
        print(f"asm:  {case.asm_path}", file=sys.stderr)
        print(reason, file=sys.stderr)
        raise SystemExit(1)


def main() -> int:
    parser = argparse.ArgumentParser(description="Batch regenerate rvprobe output artifacts")
    parser.add_argument("--workdir", default=str(REPO_ROOT), help="Repository root used to run mill")
    parser.add_argument("--cases-root", default=str(CASES_ROOT), help="Root directory for case Scala files")
    parser.add_argument("--asm-root", default=str(ASM_ROOT), help="Root directory for output .S files")
    parser.add_argument("--elf-root", default=str(ELF_ROOT), help="Root directory for ELF/BIN/OBJDUMP")
    parser.add_argument("--include", default=".*", help="Regex to filter case FQCN (e.g. 'PrivilegeSv39|RVLoadStore')")
    parser.add_argument("--skip-asm", action="store_true", help="Skip regenerating .S files")
    parser.add_argument("--skip-elf", action="store_true", help="Skip rebuilding ELF/BIN/OBJDUMP")
    parser.add_argument(
        "--asm-workers",
        type=int,
        default=1,
        help="Parallel workers for mill runMain (default 1; mill may serialize on shared output lock)",
    )
    parser.add_argument(
        "--elf-workers",
        type=int,
        default=max(1, (os.cpu_count() or 1)),
        help="Parallel workers for ELF rebuild",
    )
    parser.add_argument("--compiler", default="riscv64-unknown-linux-gnu-gcc", help="RISC-V GCC executable")
    parser.add_argument("--objcopy", default="riscv64-unknown-linux-gnu-objcopy", help="RISC-V objcopy executable")
    parser.add_argument("--objdump", default="riscv64-unknown-linux-gnu-objdump", help="RISC-V objdump executable")
    parser.add_argument("--march", default="rv64gc_zfa", help="Target ISA string passed to GCC")
    parser.add_argument("--mabi", default="lp64d", help="Target ABI string passed to GCC")
    parser.add_argument("--linker-script", default=str(DEFAULT_LINKER), help="Linker script used to build ELF")
    parser.add_argument("--run-spike", action="store_true", help="Run spike on generated ELF files")
    parser.add_argument("--spike-command", default="spike", help="Spike command, e.g. 'spike' or 'nix shell ... -c spike'")
    parser.add_argument("--spike-timeout", type=float, default=15.0, help="Timeout (seconds) per spike run")
    parser.add_argument(
        "--spike-workers",
        type=int,
        default=max(1, (os.cpu_count() or 1)),
        help="Parallel workers for spike runs",
    )
    parser.add_argument("--spike-log-dir", default="", help="Optional directory to write per-case spike logs")
    parser.add_argument("--spike-results", default="", help="Optional TSV file for spike results")
    args = parser.parse_args()

    workdir = Path(args.workdir).resolve()
    cases_root = Path(args.cases_root).resolve()
    asm_root = Path(args.asm_root).resolve()
    elf_root = Path(args.elf_root).resolve()
    linker_script = Path(args.linker_script).resolve()

    if not cases_root.exists():
        print(f"cases root does not exist: {cases_root}", file=sys.stderr)
        return 1
    if not linker_script.exists():
        print(f"linker script does not exist: {linker_script}", file=sys.stderr)
        return 1

    include_re = re.compile(args.include)
    cases = [c for c in discover_case_mains(cases_root, asm_root) if include_re.search(c.fqcn)]
    if not cases:
        print(f"no matching @main cases for include={args.include}", file=sys.stderr)
        return 1

    print(f"matched cases: {len(cases)}")

    if not args.skip_asm:
        regenerate_asm(cases, workdir, max(1, args.asm_workers))
    else:
        print("[asm] skipped")

    if not args.skip_elf:
        regenerate_elf(
            cases=cases,
            asm_root=asm_root,
            elf_root=elf_root,
            workers=max(1, args.elf_workers),
            compiler=args.compiler,
            objcopy=args.objcopy,
            objdump=args.objdump,
            march=args.march,
            mabi=args.mabi,
            linker_script=linker_script,
        )
    else:
        print("[elf] skipped")

    if args.run_spike:
        spike_cmd = shlex.split(args.spike_command)
        if not spike_cmd:
            print("spike command is empty", file=sys.stderr)
            return 1
        if shutil.which(spike_cmd[0]) is None and "/" not in spike_cmd[0]:
            print(f"spike executable not found in PATH: {spike_cmd[0]}", file=sys.stderr)
            return 1

        spike_log_dir = Path(args.spike_log_dir).resolve() if args.spike_log_dir else None
        spike_results = Path(args.spike_results).resolve() if args.spike_results else None
        regenerate_spike(
            cases=cases,
            asm_root=asm_root,
            elf_root=elf_root,
            workdir=workdir,
            workers=max(1, args.spike_workers),
            spike_cmd=spike_cmd,
            timeout_sec=max(0.1, args.spike_timeout),
            log_dir=spike_log_dir,
            results_file=spike_results,
        )
    else:
        print("[spike] skipped")

    print("done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
