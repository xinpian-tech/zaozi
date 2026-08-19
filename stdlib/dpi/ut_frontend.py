#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 xinpian-tech
"""Generic DPI-export frontend: drive a Verilated lib model from Python.

Usage: ut_frontend.py <lib.so> <dpi.json> <stimulus.json>

The lib `.so` (built with `verilator --lib-create` from the lib model plus its
DPI-export wrapper) exposes, as plain C symbols, the DPI ABI (see `doc/dpi-abi.md`):
`sim_new` / `sim_eval` / `sim_delete`, and handle-first `dpi_poke_<input>(h, v)` /
`dpi_peek_probe_<name>(h)` per contract port. This frontend reads the DPI contract
for the port names, roles and widths, then owns the loop: each cycle it pokes the
drive ports from the stimulus, steps the model with `sim_eval` (time is advanced by
the model, not by DPI), and peeks the probes.

It is the single generic consumer — nothing here is DUT-specific. `stimulus.json`
is `{ "<drive-port>": [v0, v1, ...], ... }`; where those values come from (a
solver, SVA-assertion sampling, a fixed vector) is out of scope.
"""
import ctypes
import json
import sys

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


def main():
    lib_path, dpi_path, stim_path = sys.argv[1], sys.argv[2], sys.argv[3]
    lib = ctypes.CDLL(lib_path)
    spec = json.load(open(dpi_path))
    stim = json.load(open(stim_path))

    got = spec.get("abiVersion", "?")
    if got != ABI_VERSION:
        sys.exit(f"DPI ABI mismatch: contract is {got}, frontend speaks {ABI_VERSION}")

    ports = spec["ports"]
    drives = [p for p in ports if p["role"] == "Drive"]
    probes = [p for p in ports if p["role"] == "Probe"]
    clock = next((p for p in ports if p["role"] == "Clock"), None)

    lib.sim_new.restype = ctypes.c_void_p
    lib.sim_eval.argtypes = [ctypes.c_void_p]
    lib.sim_delete.argtypes = [ctypes.c_void_p]

    u32p = ctypes.POINTER(ctypes.c_uint32)

    def poke(lib_name, width):
        # dpi_poke_<lib_name>(void* handle, value); SV truncates to the port width.
        f = getattr(lib, "dpi_poke_" + lib_name)
        if width <= 64:
            f.argtypes = [ctypes.c_void_p, ctype_for(width, True)]
            return lambda h, v: f(h, v)
        # A wide port crosses as a packed vector: uint32 chunks, little-endian.
        f.argtypes = [ctypes.c_void_p, u32p]
        n = chunks_of(width)
        return lambda h, v: f(h, (ctypes.c_uint32 * n)(*to_chunks(v, width)))

    def peek(port):
        width, signed = port["width"], port["signed"]
        f = getattr(lib, "dpi_peek_probe_" + port["name"])
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

    drive_poke = {p["name"]: poke("drive_" + p["name"], p["width"]) for p in drives}
    probe_peek = {p["name"]: peek(p) for p in probes}
    clock_poke = poke(clock["name"], clock["width"]) if clock else None

    model = lib.sim_new()
    cycles = len(stim[drives[0]["name"]]) if drives else 0
    for cyc in range(cycles):
        for p in drives:
            drive_poke[p["name"]](model, stim[p["name"]][cyc])
        if clock_poke:  # sequential DUT: a full clock edge per cycle
            clock_poke(model, 0)
            lib.sim_eval(model)
            clock_poke(model, 1)
            lib.sim_eval(model)
        else:  # combinational DUT: one settle
            lib.sim_eval(model)
        observed = " ".join(f"{p['name']}={probe_peek[p['name']](model)}" for p in probes)
        print(f"PY cyc={cyc + 1} {observed}")
    lib.sim_delete(model)


if __name__ == "__main__":
    main()
