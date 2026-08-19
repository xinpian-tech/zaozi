#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 xinpian-tech
"""Generic DPI-export frontend: drive a Verilated lib model from Python.

Usage:
  ut_frontend.py [drive] <lib.so> <abi.json> <stimulus.json>
  ut_frontend.py generate <lib.so> <abi.json> <cycles> <seed> <out-stimulus.json>

The lib `.so` (built with `verilator --lib-create` from the lib model plus its
DPI-export wrapper) exposes, as plain C symbols, the DPI ABI (see `doc/dpi-abi.md`):
`sim_new` / `sim_eval` / `sim_delete`, and handle-first `dpi_poke_<input>(h, v)` /
`dpi_peek_probe_<name>(h)` per contract port.

`drive` replays a stimulus; `generate` produces one by constrained-random sampling
against the module's `assumeOk` probe (the generic CRV core in `frontend/crv.py`).
Both own the loop: poke drive ports, `sim_eval` to step (time is the model's, not
DPI's), peek probes. Nothing here is DUT-specific.
"""
import ctypes
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "frontend"))
import crv  # noqa: E402

ABI_VERSION = "1.0"


def ctype_for(width, signed):
    if width <= 8:
        return ctypes.c_byte if signed else ctypes.c_ubyte
    if width <= 16:
        return ctypes.c_short if signed else ctypes.c_ushort
    if width <= 32:
        return ctypes.c_int if signed else ctypes.c_uint
    return ctypes.c_longlong if signed else ctypes.c_ulonglong


def chunks_of(width):
    return (width + 31) // 32


def to_chunks(value, width):
    v = value & ((1 << width) - 1)
    return [(v >> (32 * i)) & 0xFFFFFFFF for i in range(chunks_of(width))]


def from_chunks(buf, width, signed):
    v = 0
    for i in range(chunks_of(width)):
        v |= (buf[i] & 0xFFFFFFFF) << (32 * i)
    v &= (1 << width) - 1
    if signed and (v >> (width - 1)) & 1:
        v -= 1 << width
    return v


class Model:
    """The lib `.so` plus poke/step/peek bound to a fresh model handle."""

    def __init__(self, lib_path, spec):
        self.lib = ctypes.CDLL(lib_path)
        self.ports = spec["ports"]
        self.drives = [p for p in self.ports if p["role"] == "Drive"]
        self.probes = [p for p in self.ports if p["role"] == "Probe"]
        clock = next((p for p in self.ports if p["role"] == "Clock"), None)

        self.lib.sim_new.restype = ctypes.c_void_p
        self.lib.sim_eval.argtypes = [ctypes.c_void_p]
        self.lib.sim_delete.argtypes = [ctypes.c_void_p]
        u32p = ctypes.POINTER(ctypes.c_uint32)

        def poke_fn(lib_name, width):
            f = getattr(self.lib, "dpi_poke_" + lib_name)
            if width <= 64:
                f.argtypes = [ctypes.c_void_p, ctype_for(width, True)]
                return lambda h, v: f(h, v)
            f.argtypes = [ctypes.c_void_p, u32p]
            n = chunks_of(width)
            return lambda h, v: f(h, (ctypes.c_uint32 * n)(*to_chunks(v, width)))

        def peek_fn(port):
            width, signed = port["width"], port["signed"]
            f = getattr(self.lib, "dpi_peek_probe_" + port["name"])
            if width <= 64:
                f.argtypes = [ctypes.c_void_p]
                f.restype = ctype_for(width, signed)
                return lambda h: f(h)
            f.argtypes = [ctypes.c_void_p, u32p]
            f.restype = None
            n = chunks_of(width)

            def do(h):
                buf = (ctypes.c_uint32 * n)()
                f(h, buf)
                return from_chunks(buf, width, signed)

            return do

        self._drive_poke = {p["name"]: poke_fn("drive_" + p["name"], p["width"]) for p in self.drives}
        self._probe_peek = {p["name"]: peek_fn(p) for p in self.probes}
        self._clock_poke = poke_fn(clock["name"], clock["width"]) if clock else None
        self._clocked = clock is not None
        self.handle = self.lib.sim_new()

    def poke(self, name, value):
        self._drive_poke[name](self.handle, value)

    def step(self):
        if self._clocked:  # sequential DUT: a full clock edge
            self._clock_poke(self.handle, 0)
            self.lib.sim_eval(self.handle)
            self._clock_poke(self.handle, 1)
            self.lib.sim_eval(self.handle)
        else:  # combinational DUT: one settle
            self.lib.sim_eval(self.handle)

    def peek(self, name):
        return self._probe_peek[name](self.handle)

    def close(self):
        self.lib.sim_delete(self.handle)


def load(abi_path):
    spec = json.load(open(abi_path))
    got = spec.get("abiVersion", "?")
    if got != ABI_VERSION:
        sys.exit(f"DPI ABI mismatch: contract is {got}, frontend speaks {ABI_VERSION}")
    return spec


def drive_main(lib_path, abi_path, stim_path):
    spec = load(abi_path)
    stim = json.load(open(stim_path))
    m = Model(lib_path, spec)
    cycles = len(stim[m.drives[0]["name"]]) if m.drives else 0
    for cyc in range(cycles):
        for p in m.drives:
            m.poke(p["name"], stim[p["name"]][cyc])
        m.step()
        observed = " ".join(f"{p['name']}={m.peek(p['name'])}" for p in m.probes)
        print(f"PY cyc={cyc + 1} {observed}")
    m.close()


def generate_main(lib_path, abi_path, cycles, seed, out_path):
    spec = load(abi_path)
    m = Model(lib_path, spec)
    stim = crv.generate(spec["ports"], m.poke, m.step, m.peek, cycles, seed=seed)
    m.close()
    json.dump(stim, open(out_path, "w"), indent=2)
    print(f"GENERATED {cycles} cycles -> {out_path}")


def main():
    args = sys.argv[1:]
    if args and args[0] == "generate":
        _, lib_path, abi_path, cycles, seed, out_path = args
        generate_main(lib_path, abi_path, int(cycles), int(seed), out_path)
    else:
        rest = args[1:] if (args and args[0] == "drive") else args
        drive_main(rest[0], rest[1], rest[2])


if __name__ == "__main__":
    main()
