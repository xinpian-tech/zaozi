# SPDX-License-Identifier: Apache-2.0
"""CSV stimulus for the 8-bit, depth-2 RAM with synchronous memory reset.

Called once per falling clock edge. Each row drives one cycle.
This driver supplies inputs; it does not check DUT outputs.
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
    data = int(row["writeData"], 0)
    return {
        "resetN": int(row["resetN"], 0),
        "chipSelectN": int(row["chipSelectN"], 0),
        "writeN": int(row["writeN"], 0),
        "readAddress": int(row["readAddress"], 0),
        "writeAddress": int(row["writeAddress"], 0),
        # The exported DPI ABI represents the 8-bit data port as a signed byte.
        "writeData": data if data < 0x80 else data - 0x100,
        "done": int(row["done"], 0),
        "status": 0,
    }
