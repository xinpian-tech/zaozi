# SPDX-License-Identifier: Apache-2.0
"""Python stimulus for the 8-bit, depth-3 SyncQueue testbench.

Called once per falling clock edge. Return DPI outputs and the status result.
The labels describe requests; this driver does not check DUT outputs.
"""

_steps = iter(
    [
        (0, 1, 1, 0, 0, "reset"),
        (0, 1, 1, 0, 0, "reset"),
        (1, 1, 1, 0, 0, "release reset"),
        (1, 0, 1, 0x11, 0, "push 11"),
        (1, 0, 1, 0x22, 0, "push 22"),
        (1, 0, 1, 0x33, 0, "push 33"),
        (1, 0, 1, 0x55, 0, "overflow request"),
        (1, 1, 1, 0, 0, "idle"),
        (1, 1, 0, 0, 0, "pop"),
        (1, 0, 0, 0x44, 0, "pop + push 44"),
        (1, 1, 0, 0, 0, "pop"),
        (1, 1, 0, 0, 0, "pop"),
        (1, 1, 0, 0, 0, "underflow request"),
        (1, 1, 1, 0, 0, "idle"),
        (1, 1, 1, 0, 1, "done"),
    ]
)
_index = 0


def step():
    global _index
    reset, push, pop, data, done, label = next(_steps)
    print(f"Python step={_index}: {label}", flush=True)
    _index += 1
    return {
        "resetN": reset,
        "pushRequestN": push,
        "popRequestN": pop,
        "diagnosticN": 1,
        "dataIn": data,
        "done": done,
        "status": 0,
    }
