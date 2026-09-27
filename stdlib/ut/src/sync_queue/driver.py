"""CPython frontend for the SyncQueue unit test."""

import json
import os
from pathlib import Path


_stimulus = json.loads(Path(os.environ["ZAOZI_STIMULUS_JSON"]).read_text())
_vectors = _stimulus["vectors"]
_cycle = 0


def zaozi_step():
    global _cycle
    cycle = _cycle
    _cycle += 1
    command = _vectors[cycle] if cycle < len(_vectors) else {
        "reset_n": 1,
        "push_n": 1,
        "pop_n": 1,
        "diagnostic_n": 1,
        "data_in": 0,
    }
    return {
        "resetN": command["reset_n"],
        "pushRequestN": command["push_n"],
        "popRequestN": command["pop_n"],
        "diagnosticN": command["diagnostic_n"],
        "dataIn": command["data_in"],
        "done": int(cycle >= len(_vectors) + 3),
        "status": 0,
    }
