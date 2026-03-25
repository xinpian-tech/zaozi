#!/usr/bin/env python3
"""
Batch-regenerate rvprobe output artifacts.

Phases:
  1) Regenerate output assembly (.S) from all @main case generators.
  2) Rebuild ELF/BIN/OBJDUMP from output assembly (in parallel).
  3) Optionally run spike on selected ELF test cases.

Examples:
  python3 rvprobe/scripts/run.py
  python3 rvprobe/scripts/run.py --include 'PrivilegeSv39'
  python3 rvprobe/scripts/run.py --skip-asm --elf-workers 16
  python3 rvprobe/scripts/run.py --asm-workers 4 --elf-workers 16
  python3 rvprobe/scripts/run.py --include 'PMP' --skip-asm --skip-elf \\
      --run-spike --spike-command "nix shell nixpkgs#spike -c spike"
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

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
REPO_ROOT = Path(__file__).resolve().parents[2]
CASES_ROOT = REPO_ROOT / "rvprobe" / "src" / "cases"
ASM_ROOT = CASES_ROOT / "output" / "asm"
ELF_ROOT = CASES_ROOT / "output" / "elf"

# ---------------------------------------------------------------------------
# Case discovery
# ---------------------------------------------------------------------------
_PACKAGE_RE = re.compile(r"^\s*package\s+([\w\.]+)", re.M)
_MAIN_RE = re.compile(r"@main\s+def\s+([A-Za-z0-9_]+)\s*\(")


@dataclass(frozen=True)
class CaseMain:
    fqcn: str
    asm_path: Path


def discover_cases(cases_root: Path, asm_root: Path) -> list[CaseMain]:
    """Scan Scala sources for @main defs and derive output .S paths."""
    mains: list[CaseMain] = []
    for scala in sorted(cases_root.rglob("*.scala")):
        if "/output/" in str(scala):
            continue
        text = scala.read_text(encoding="utf-8")
        pkg_m = _PACKAGE_RE.search(text)
        if pkg_m is None:
            continue
        pkg = pkg_m.group(1)
        subdir = scala.parent.name
        for m in _MAIN_RE.finditer(text):
            name = m.group(1)
            mains.append(CaseMain(
                fqcn=f"{pkg}.{name}",
                asm_path=asm_root / subdir / f"{name}.S",
            ))
    return mains


# ---------------------------------------------------------------------------
# Parallel runner helper
# ---------------------------------------------------------------------------
def _run_parallel(
    label: str,
    items: list,
    fn,
    workers: int,
    on_ok,
    on_fail,
    *,
    stop_on_first_fail: bool = False,
) -> list[tuple]:
    """Run *fn(item)* over *items* with up to *workers* threads.

    Returns a list of (item, error_str) for failures.
    """
    total = len(items)
    failures: list[tuple] = []

    if workers <= 1:
        for idx, item in enumerate(items, 1):
            try:
                result = fn(item)
                on_ok(idx, total, item, result)
            except Exception as exc:
                on_fail(idx, total, item, exc)
                failures.append((item, str(exc)))
                if stop_on_first_fail:
                    break
    else:
        future_map = {}
        with ThreadPoolExecutor(max_workers=workers) as ex:
            for item in items:
                future_map[ex.submit(fn, item)] = item
            done = 0
            for fut in as_completed(future_map):
                item = future_map[fut]
                done += 1
                try:
                    result = fut.result()
                    on_ok(done, total, item, result)
                except Exception as exc:
                    on_fail(done, total, item, exc)
                    failures.append((item, str(exc)))
    return failures


# ---------------------------------------------------------------------------
# Phase 1: ASM generation
# ---------------------------------------------------------------------------
def _mill_run_main(case: CaseMain, workdir: Path) -> None:
    case.asm_path.parent.mkdir(parents=True, exist_ok=True)
    proc = subprocess.run(
        ["mill", "-i", "rvprobe.runMain", case.fqcn, str(case.asm_path)],
        cwd=workdir,
        text=True,
        capture_output=True,
    )
    if proc.returncode != 0:
        raise RuntimeError((proc.stdout or "") + (proc.stderr or ""))


def phase_asm(cases: list[CaseMain], workdir: Path, asm_root: Path, workers: int) -> None:
    if asm_root.exists():
        shutil.rmtree(asm_root)
        print(f"[asm] cleaned {asm_root}")

    print(f"[asm] generating {len(cases)} cases (workers={workers})")
    failures = _run_parallel(
        "asm", cases,
        fn=lambda c: _mill_run_main(c, workdir),
        workers=workers,
        on_ok=lambda i, t, c, _: print(f"[asm][ok] {i}/{t} {c.fqcn}"),
        on_fail=lambda i, t, c, e: print(f"[asm][fail] {i}/{t} {c.fqcn}"),
        stop_on_first_fail=(workers <= 1),
    )
    if failures:
        case, reason = failures[0]
        print(f"\n[asm] first failure: {case.fqcn}", file=sys.stderr)
        print(reason[-8000:], file=sys.stderr)
        raise SystemExit(1)


# ---------------------------------------------------------------------------
# Phase 2: ELF compilation
# ---------------------------------------------------------------------------
def phase_elf(
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
    if elf_root.exists():
        shutil.rmtree(elf_root)
        print(f"[elf] cleaned {elf_root}")

    def compile_one(case: CaseMain):
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

    def on_ok(i, t, case, result):
        elf, binf, obj = result
        rel_asm = case.asm_path.relative_to(asm_root)
        print(
            f"[elf][ok] {i}/{t} {rel_asm} -> "
            f"{elf.relative_to(elf_root)}, {binf.relative_to(elf_root)}, {obj.relative_to(elf_root)}"
        )

    print(f"[elf] compiling {len(cases)} cases (workers={workers})")
    failures = _run_parallel(
        "elf", cases,
        fn=compile_one,
        workers=workers,
        on_ok=on_ok,
        on_fail=lambda i, t, c, e: print(f"[elf][fail] {i}/{t} {c.asm_path.relative_to(asm_root)}"),
    )
    if failures:
        case, reason = failures[0]
        print(f"\n[elf] first failure: {case.fqcn}\n  asm: {case.asm_path}", file=sys.stderr)
        print(reason, file=sys.stderr)
        raise SystemExit(1)


# ---------------------------------------------------------------------------
# Phase 3: Spike simulation
# ---------------------------------------------------------------------------
def _case_elf_path(case: CaseMain, asm_root: Path, elf_root: Path) -> Path:
    return (elf_root / case.asm_path.relative_to(asm_root)).with_suffix(".elf")


def phase_spike(
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
    if log_dir is not None:
        log_dir.mkdir(parents=True, exist_ok=True)

    rows: list[tuple[str, Path, CaseMain, str]] = []

    def run_one(case: CaseMain) -> tuple[str, str, Path]:
        elf_path = _case_elf_path(case, asm_root, elf_root)
        if not elf_path.exists():
            return "MISSING", f"ELF not found: {elf_path}", elf_path
        try:
            proc = subprocess.run(
                [*spike_cmd, str(elf_path)],
                cwd=workdir, text=True, capture_output=True,
                timeout=timeout_sec,
            )
            output = (proc.stdout or "") + (proc.stderr or "")
            status = "PASS" if proc.returncode == 0 else f"FAIL({proc.returncode})"
        except subprocess.TimeoutExpired as exc:
            output = (exc.stdout or "") + (exc.stderr or "")
            status = "TIMEOUT"
        if log_dir is not None:
            log_path = log_dir / case.asm_path.relative_to(asm_root).with_suffix(".log")
            log_path.parent.mkdir(parents=True, exist_ok=True)
            log_path.write_text(output, encoding="utf-8")
        return status, output, elf_path

    print(f"[spike] running {len(cases)} cases (workers={workers}, timeout={timeout_sec}s)")

    future_map = {}
    with ThreadPoolExecutor(max_workers=workers) as ex:
        for case in cases:
            future_map[ex.submit(run_one, case)] = case
        done = 0
        for fut in as_completed(future_map):
            case = future_map[fut]
            done += 1
            try:
                status, out, elf_path = fut.result()
            except Exception as exc:
                status, out = "ERROR", str(exc)
                elf_path = _case_elf_path(case, asm_root, elf_root)
            rows.append((status, elf_path, case, out))
            print(f"[spike][{status.lower()}] {done}/{len(cases)} {elf_path.relative_to(elf_root)}")

    # Write results file
    if results_file is not None:
        results_file.parent.mkdir(parents=True, exist_ok=True)
        lines = [f"{s}\t{e}\t{c.fqcn}" for s, e, c, _ in sorted(rows, key=lambda r: str(r[1]))]
        results_file.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
        print(f"[spike] wrote results: {results_file}")

    # Summary
    counts: dict[str, int] = {}
    for s, _, _, _ in rows:
        counts[s] = counts.get(s, 0) + 1
    print(f"[spike] summary: {', '.join(f'{k}={v}' for k, v in sorted(counts.items())) or 'no results'}")

    failed = [(s, e, c, o) for s, e, c, o in rows if s != "PASS"]
    if failed:
        s, e, c, o = failed[0]
        print(f"\n[spike] first non-pass: {s} {c.fqcn}\n  elf: {e}", file=sys.stderr)
        print((o or "")[-8000:], file=sys.stderr)
        raise SystemExit(1)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def main() -> int:
    ap = argparse.ArgumentParser(description="Batch regenerate rvprobe output artifacts")

    # Paths
    ap.add_argument("--workdir", default=str(REPO_ROOT))
    ap.add_argument("--cases-root", default=str(CASES_ROOT))
    ap.add_argument("--asm-root", default=str(ASM_ROOT))
    ap.add_argument("--elf-root", default=str(ELF_ROOT))

    # Filtering
    ap.add_argument("--include", default=".*", help="Regex filter on case FQCN")

    # Phase toggles
    ap.add_argument("--skip-asm", action="store_true")
    ap.add_argument("--skip-elf", action="store_true")
    ap.add_argument("--run-spike", action="store_true")

    # Parallelism
    cpu = max(1, os.cpu_count() or 1)
    ap.add_argument("--asm-workers", type=int, default=1)
    ap.add_argument("--elf-workers", type=int, default=cpu)
    ap.add_argument("--spike-workers", type=int, default=cpu)

    # Toolchain
    ap.add_argument("--compiler", default="riscv64-unknown-linux-gnu-gcc")
    ap.add_argument("--objcopy", default="riscv64-unknown-linux-gnu-objcopy")
    ap.add_argument("--objdump", default="riscv64-unknown-linux-gnu-objdump")
    ap.add_argument("--march", default="rv64gc_zfa")
    ap.add_argument("--mabi", default="lp64d")
    ap.add_argument("--linker-script", default=str(DEFAULT_LINKER))

    # Spike
    ap.add_argument("--spike-command", default="nix run nixpkgs#spike --")
    ap.add_argument("--spike-timeout", type=float, default=15.0)
    ap.add_argument("--spike-log-dir", default="")
    ap.add_argument("--spike-results", default="")

    args = ap.parse_args()

    workdir = Path(args.workdir).resolve()
    cases_root = Path(args.cases_root).resolve()
    asm_root = Path(args.asm_root).resolve()
    elf_root = Path(args.elf_root).resolve()
    linker_script = Path(args.linker_script).resolve()

    if not cases_root.exists():
        print(f"cases root not found: {cases_root}", file=sys.stderr)
        return 1
    if not args.skip_elf and not linker_script.exists():
        print(f"linker script not found: {linker_script}", file=sys.stderr)
        return 1

    # Discover and filter cases
    include_re = re.compile(args.include)
    cases = [c for c in discover_cases(cases_root, asm_root) if include_re.search(c.fqcn)]
    if not cases:
        print(f"no matching cases for --include={args.include}", file=sys.stderr)
        return 1
    print(f"matched cases: {len(cases)}")

    # Phase 1: ASM
    if not args.skip_asm:
        phase_asm(cases, workdir, asm_root, max(1, args.asm_workers))
    else:
        print("[asm] skipped")

    # Phase 2: ELF
    if not args.skip_elf:
        phase_elf(
            cases, asm_root, elf_root,
            workers=max(1, args.elf_workers),
            compiler=args.compiler, objcopy=args.objcopy, objdump=args.objdump,
            march=args.march, mabi=args.mabi, linker_script=linker_script,
        )
    else:
        print("[elf] skipped")

    # Phase 3: Spike
    if args.run_spike:
        spike_cmd = shlex.split(args.spike_command)
        if not spike_cmd:
            print("spike command is empty", file=sys.stderr)
            return 1
        if shutil.which(spike_cmd[0]) is None and "/" not in spike_cmd[0]:
            print(f"spike not found: {spike_cmd[0]}", file=sys.stderr)
            return 1
        phase_spike(
            cases, asm_root, elf_root, workdir,
            workers=max(1, args.spike_workers),
            spike_cmd=spike_cmd,
            timeout_sec=max(0.1, args.spike_timeout),
            log_dir=Path(args.spike_log_dir).resolve() if args.spike_log_dir else None,
            results_file=Path(args.spike_results).resolve() if args.spike_results else None,
        )
    else:
        print("[spike] skipped")

    print("done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
