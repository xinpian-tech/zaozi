"""Supply the SyncQueue unit test's input stimulus."""

import json
import os
from pathlib import Path


_stimulus = json.loads(Path(os.environ["ZAOZI_STIMULUS_JSON"]).read_text())
_vectors = _stimulus["vectors"]
_cycle = 0


def step():
    global _cycle
    cycle = _cycle
    _cycle += 1
    command = _vectors[cycle] if cycle < len(_vectors) else {
        "reset_n": 1, "push_n": 1, "pop_n": 1, "diagnostic_n": 1, "data_in": 0
    }
    return dict(command, done=int(cycle >= len(_vectors) + 3), status=0)
