#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 xinpian-tech
"""DPI frontend: a [[backend.Backend]] realized over the export-"DPI-C" `.so`.

Usage:
  ut_frontend.py [drive] <lib.so> <port.json> <stimulus.json>
  ut_frontend.py generate <lib.so> <port.json> <cycles> <seed> <out-stimulus.json>

The `.so` (built with `verilator --lib-create` from the lib model plus its DPI-export
wrapper) exposes the DPI ABI (see `doc/dpi-abi.md`): `sim_new`/`sim_eval`/`sim_delete`
and handle-first `dpi_poke_<input>(h, v)` / `dpi_peek_probe_<name>(h)`. `DpiBackend`
maps the operation ABI (poke/peek/step) onto those symbols; the shared drivers
(`backend.replay`, `crv.generate`) do the rest.
"""
import asyncio
import ctypes
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "frontend"))
import backend as be  # noqa: E402
import crv  # noqa: E402

VERSION = "1.0"


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
    return be.as_signed(v, width, signed)


class DpiBackend(be.Backend):
    def __init__(self, lib_path, ports):
        super().__init__(ports)
        self.lib = ctypes.CDLL(lib_path)
        self.lib.sim_new.restype = ctypes.c_void_p
        self.lib.sim_eval.argtypes = [ctypes.c_void_p]
        self.lib.sim_delete.argtypes = [ctypes.c_void_p]
        u32p = ctypes.POINTER(ctypes.c_uint32)

        def mk_poke(port):
            f = getattr(self.lib, "dpi_poke_" + be.lib_input(port))
            w = port["width"]
            if w <= 64:
                f.argtypes = [ctypes.c_void_p, ctype_for(w, True)]
                return lambda h, v: f(h, v)
            f.argtypes = [ctypes.c_void_p, u32p]
            n = chunks_of(w)
            return lambda h, v: f(h, (ctypes.c_uint32 * n)(*to_chunks(v, w)))

        def mk_peek(port):
            f = getattr(self.lib, "dpi_peek_" + be.lib_probe(port))
            w, s = port["width"], port["signed"]
            if w <= 64:
                f.argtypes = [ctypes.c_void_p]
                f.restype = ctype_for(w, s)
                return lambda h: f(h)
            f.argtypes = [ctypes.c_void_p, u32p]
            f.restype = None
            n = chunks_of(w)

            def do(h):
                buf = (ctypes.c_uint32 * n)()
                f(h, buf)
                return from_chunks(buf, w, s)

            return do

        inputs = ([self.clock] if self.clock else []) + [p for p in ports if p["role"] == "Reset"] + self.drives
        self._poke = {p["name"]: mk_poke(p) for p in inputs}
        self._peek = {p["name"]: mk_peek(p) for p in self.probes}
        self.handle = self.lib.sim_new()

    def poke(self, port, value):
        self._poke[port["name"]](self.handle, value)

    def peek(self, port):
        return self._peek[port["name"]](self.handle)

    async def _eval(self):
        self.lib.sim_eval(self.handle)

    def close(self):
        self.lib.sim_delete(self.handle)


def load(path):
    spec = json.load(open(path))
    if spec.get("version", "?") != VERSION:
        sys.exit(f"port contract version mismatch: {spec.get('version')} vs frontend {VERSION}")
    return spec


def main():
    args = sys.argv[1:]
    if args and args[0] == "generate":
        _, lib_path, ports_path, cycles, seed, out_path = args
        b = DpiBackend(lib_path, load(ports_path)["ports"])
        stim = asyncio.run(crv.generate(b, int(cycles), int(seed)))
        b.close()
        json.dump(stim, open(out_path, "w"), indent=2)
        print(f"GENERATED {cycles} cycles -> {out_path}")
    else:
        rest = args[1:] if (args and args[0] == "drive") else args
        lib_path, ports_path, stim_path = rest[0], rest[1], rest[2]
        b = DpiBackend(lib_path, load(ports_path)["ports"])
        asyncio.run(be.replay(b, json.load(open(stim_path))))
        b.close()


if __name__ == "__main__":
    main()
