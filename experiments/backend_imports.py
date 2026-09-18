"""Locate the in-tree RVProbe runtime for directly executed experiment scripts.

This is path setup only. Backend implementation lives in rvprobe/backend and
can also be imported normally with the repository on PYTHONPATH.
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
