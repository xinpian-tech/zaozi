"""Trusted runtime inputs, checked before spending any model tokens."""
import hashlib
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
