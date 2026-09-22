"""Trusted runtime inputs, checked before spending any model tokens."""
import hashlib
import shutil
import subprocess
from pathlib import Path
import backend_imports
from rvprobe.backend import validation


def runtime_files(root):
    root = Path(root).resolve()
    files = [root / name for name in (
        'experiments/ut_harness.py', 'experiments/isolation.py',
        'experiments/framework_runtime.py', 'experiments/backend_imports.py',
        'experiments/rtl_initial_state.py', 'experiments/repair_policy.py',
        'experiments/src/TrustedSolver.scala', 'utlib/src/JasperGold.scala')]
    # Ownership follows the imported backend, not an obsolete experiment path.
    backend = Path(validation.__file__).resolve().parent
    if not backend.is_relative_to(root):
        raise RuntimeError('trusted backend was imported from a different checkout')
    files += sorted(backend.glob('*.py'))
    for path in files:
        if not path.is_file():
            raise FileNotFoundError(f'trusted runtime input missing: {path}')
    return files


def runtime_hashes(root):
    return {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in runtime_files(root)}


def runtime_commands():
    """Validate the production entry command before a paid model request.

    Direct compiler/API tests do not exercise the nested Nix harness entry.
    Never depend on an interactive shell having supplied this executable.
    """
    executable = shutil.which('nix')
    if executable is None:
        raise RuntimeError("RVProbe runtime command 'nix' is missing from PATH; use the flake devShell including nix before starting generation. No model repair is applicable.")
    try:
        result = subprocess.run([executable, '--version'], capture_output=True,
                                text=True, check=True, timeout=15)
    except (OSError, subprocess.SubprocessError) as error:
        raise RuntimeError("RVProbe runtime command 'nix --version' failed; repair the execution environment before generation, not the LTL.") from error
    if not result.stdout.strip():
        raise RuntimeError("RVProbe runtime command 'nix --version' returned no version")
    return {'nix': {'executable': str(Path(executable).resolve()),
                    'version': result.stdout.strip().splitlines()[0][:512]}}
