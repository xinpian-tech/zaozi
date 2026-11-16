# SPDX-License-Identifier: Apache-2.0
"""Python stimulus for the 8-bit, depth-3 SyncQueue testbench.

Called once per falling clock edge. Return DPI outputs and the status result.
The labels describe requests; this driver does not check DUT outputs.
"""

import csv
from pathlib import Path

with Path(__file__).resolve().with_name("stimulus.csv").open(newline="") as stream:
    _steps = iter(list(csv.DictReader(stream)))
_index = 0


def step():
    global _index
    row = next(_steps)
    print(f"Python step={_index}: {row['label']}", flush=True)
    _index += 1
    return {
        "resetN": int(row["resetN"], 0),
        "pushRequestN": int(row["pushRequestN"], 0),
        "popRequestN": int(row["popRequestN"], 0),
        "diagnosticN": 1,
        "dataIn": int(row["dataIn"], 0),
        "done": int(row["done"], 0),
        "status": 0,
    }
