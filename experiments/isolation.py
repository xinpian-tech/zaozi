"""Fail-closed Linux isolation for compilation and model elaboration, never EDA/license access."""
import json
import os
from pathlib import Path
import shutil
import subprocess

from process_runner import run


def command(argv, *, readonly, writable, env):
    bwrap = shutil.which("bwrap")
    if not bwrap:
        raise RuntimeError("bubblewrap is required for model isolation; no unsandboxed fallback")
    args = [bwrap, "--unshare-all", "--die-with-parent", "--new-session", "--clearenv",
            "--ro-bind", "/nix/store", "/nix/store", "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp"]
    for path in sorted({Path(p).resolve() for p in readonly if Path(p).exists()}, key=lambda p: len(p.parts)):
        if not path.is_relative_to("/nix/store"):
            args += ["--ro-bind", str(path), str(path)]
    writable = Path(writable).resolve()
    if writable == Path("/") or writable in [Path(p).resolve() for p in readonly]:
        raise ValueError("sandbox output must be a distinct, dedicated directory")
    args += ["--bind", str(writable), str(writable), "--chdir", str(writable)]
    for name, value in env.items():
        args += ["--setenv", name, value]
    return args + ["--remount-ro", "/", "--", *map(str, argv)]


def toolchain(root):
    env = {k: v for k, v in os.environ.items() if k not in (
        "RVPROBE_EXPERIMENT_SOURCES", "RVPROBE_LLM_API_KEY", "RVPROBE_LLM_BASE_URL",
        "OPENAI_API_KEY", "OPENAI_BASE_URL")}
    result = run(["mill", "--no-server", "show", "experiments.sandboxToolchain"], cwd=root, env=env,
                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=600)
    if result.returncode:
        raise RuntimeError("trusted toolchain preparation failed: " + result.stdout[-2500:])
    decoder = json.JSONDecoder()
    for index, ch in enumerate(result.stdout):
        if ch == "{":
            try:
                value, _ = decoder.raw_decode(result.stdout[index:])
                if isinstance(value, dict) and set(value) == {"compiler", "classpath", "options", "jvm"}:
                    return value
            except ValueError:
                pass
    raise RuntimeError("trusted toolchain did not return its classpath configuration")


def execute(argv, config, sources, work, *, timeout=600):
    readonly = [sources, *config["compiler"], *config["classpath"]]
    readonly += [arg.removeprefix("-Xplugin:") for arg in config["options"] if arg.startswith("-Xplugin:")]
    paths = [str(Path(p).resolve()) for p in os.environ.get("PATH", "").split(os.pathsep)
             if p and Path(p).exists() and str(Path(p).resolve()).startswith("/nix/store/")]
    env = {"PATH": os.pathsep.join(paths), "TMPDIR": "/tmp", "LANG": "C.UTF-8"}
    for name in ("CIRCT_INSTALL_PATH", "MLIR_INSTALL_PATH"):
        if name in os.environ:
            path = str(Path(os.environ[name]).resolve())
            env[name] = path
            readonly.append(path)
    cmd = command(argv, readonly=readonly, writable=work, env=env)
    return run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
