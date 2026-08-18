#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 xinpian-tech
"""Generic DPI-export frontend: drive a Verilated lib model from Python.

Usage: ut_frontend.py <lib.so> <dpi.json> <stimulus.json>

The lib `.so` (built with `verilator --lib-create` from the lib model plus its
DPI-export wrapper) exposes, as plain C symbols, `sim_new` / `sim_eval` /
`sim_delete` and one `poke_<input>` / `peek_probe_<name>` per contract port. This
frontend reads the DPI contract for the port names, roles and widths, then owns
the loop: each cycle it pokes the drive ports from the stimulus, steps the model
with `sim_eval` (time is advanced by the model, not by DPI), and peeks the probes.

It is the single generic consumer — nothing here is DUT-specific. `stimulus.json`
is `{ "<drive-port>": [v0, v1, ...], ... }`; where those values come from (a
solver, SVA-assertion sampling, a fixed vector) is out of scope.
"""
import ctypes
import json
import sys


def ctype_for(width, signed):
    if width <= 8:
        return ctypes.c_byte if signed else ctypes.c_ubyte
    if width <= 16:
        return ctypes.c_short if signed else ctypes.c_ushort
    if width <= 32:
        return ctypes.c_int if signed else ctypes.c_uint
    return ctypes.c_longlong if signed else ctypes.c_ulonglong


def main():
    lib_path, dpi_path, stim_path = sys.argv[1], sys.argv[2], sys.argv[3]
    lib = ctypes.CDLL(lib_path)
    spec = json.load(open(dpi_path))
    stim = json.load(open(stim_path))
    ports = spec["ports"]

    drives = [p for p in ports if p["role"] == "Drive"]
    probes = [p for p in ports if p["role"] == "Probe"]
    clock = next((p for p in ports if p["role"] == "Clock"), None)

    lib.sim_new.restype = ctypes.c_void_p
    lib.sim_eval.argtypes = [ctypes.c_void_p]
    lib.sim_delete.argtypes = [ctypes.c_void_p]

    def poke(lib_name, width):
        f = getattr(lib, "poke_" + lib_name)
        f.argtypes = [ctype_for(width, True)]  # poke by value; SV truncates to the port width
        return f

    def peek(port):
        f = getattr(lib, "peek_probe_" + port["name"])
        f.restype = ctype_for(port["width"], port["signed"])
        return f

    drive_poke = {p["name"]: poke("drive_" + p["name"], p["width"]) for p in drives}
    probe_peek = {p["name"]: peek(p) for p in probes}
    clock_poke = poke(clock["name"], clock["width"]) if clock else None

    model = lib.sim_new()
    cycles = len(stim[drives[0]["name"]]) if drives else 0
    for cyc in range(cycles):
        for p in drives:
            drive_poke[p["name"]](stim[p["name"]][cyc])
        if clock_poke:  # sequential DUT: a full clock edge per cycle
            clock_poke(0)
            lib.sim_eval(model)
            clock_poke(1)
            lib.sim_eval(model)
        else:  # combinational DUT: one settle
            lib.sim_eval(model)
        observed = " ".join(f"{p['name']}={probe_peek[p['name']]()}" for p in probes)
        print(f"PY cyc={cyc + 1} {observed}")
    lib.sim_delete(model)


if __name__ == "__main__":
    main()
