# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 xinpian-tech
"""The operation ABI: poke / peek / step.

`port.json` is the *contract* (which ports exist, their roles/widths/signedness).
The *ABI* is this operation interface, which each simulator backend realizes:

  - `DpiBackend` (ctypes over the export-"DPI-C" `.so`) — see `dpi/ut_frontend.py`
  - `CocotbBackend` (VPI signal access)               — see `vpi/ut_cocotb.py`

Drivers (`replay`, `crv.generate`) depend only on `Backend`, so they are
backend-agnostic. `step()` is async so the same driver serves the synchronous DPI
model (a trivial await) and cocotb's genuinely async time control.
"""


def lib_input(port):
    """The lib model's port name for a contract input: drives are prefixed, clock/reset are not."""
    return ("drive_" + port["name"]) if port["role"] == "Drive" else port["name"]


def lib_probe(port):
    return "probe_" + port["name"]


def as_signed(value, width, signed):
    v = value & ((1 << width) - 1)
    if signed and (v >> (width - 1)) & 1:
        v -= 1 << width
    return v


class Backend:
    """Realize the operation ABI for one simulator.

    Subclasses implement `poke(port, value)`, `peek(port) -> int`, and `_eval()`
    (advance one settle). `step()` is shared: it toggles the clock when the contract
    has one, else a single settle.
    """

    def __init__(self, ports):
        self.ports = ports
        self.drives = [p for p in ports if p["role"] == "Drive"]
        self.probes = [p for p in ports if p["role"] == "Probe"]
        self.clock = next((p for p in ports if p["role"] == "Clock"), None)

    def probe(self, name):
        return next((p for p in self.probes if p["name"] == name), None)

    def poke(self, port, value):
        raise NotImplementedError

    def peek(self, port):
        raise NotImplementedError

    async def _eval(self):
        raise NotImplementedError

    def close(self):
        pass

    async def step(self):
        if self.clock is not None:  # sequential DUT: a full clock edge
            self.poke(self.clock, 0)
            await self._eval()
            self.poke(self.clock, 1)
            await self._eval()
        else:  # combinational DUT: one settle
            await self._eval()


async def replay(backend, stimulus):
    """Drive a stored per-cycle stimulus, printing the observed probes each cycle."""
    drives = backend.drives
    cycles = len(stimulus[drives[0]["name"]]) if drives else 0
    for cyc in range(cycles):
        for p in drives:
            backend.poke(p, stimulus[p["name"]][cyc])
        await backend.step()
        observed = " ".join(f"{p['name']}={backend.peek(p)}" for p in backend.probes)
        print(f"PY cyc={cyc + 1} {observed}")
