#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 xinpian-tech
"""Generic VPI frontend (cocotb binding): drive a lib model from Python via VPI.

This is the second binding of the ABI contract (`abi.json`), alongside the DPI
one (`ut_frontend.py`). Where the DPI binding calls generated `export "DPI-C"`
symbols, cocotb drives the lib model's ports directly through VPI —
`dut.drive_<name>.value = ...` / `int(dut.probe_<name>.value)` — so it needs no
DPI wrapper, only the lib model (`generated.sv` + layer/ref split files).

Run as a script it acts as the cocotb *runner*: it builds the lib model with
Verilator and launches the cocotb test below. The test reads the same contract
and per-cycle stimulus the DPI frontend does, and owns the loop.

    ut_cocotb.py <workdir> <toplevel> <abi.json> <stimulus.json> <sv sources...>
"""
import json
import os
import sys

import cocotb
from cocotb.triggers import Timer


@cocotb.test()
async def drive(dut):
    spec = json.load(open(os.environ["ABI_JSON"]))
    stim = json.load(open(os.environ["STIMULUS_JSON"]))
    ports = spec["ports"]
    drives = [p for p in ports if p["role"] == "Drive"]
    probes = [p for p in ports if p["role"] == "Probe"]
    clock = next((p for p in ports if p["role"] == "Clock"), None)

    cycles = len(stim[drives[0]["name"]]) if drives else 0
    for cyc in range(cycles):
        for p in drives:
            getattr(dut, "drive_" + p["name"]).value = stim[p["name"]][cyc] & ((1 << p["width"]) - 1)
        if clock is not None:  # sequential DUT: a full clock edge per cycle
            c = getattr(dut, clock["name"])
            c.value = 0
            await Timer(1, unit="ns")
            c.value = 1
            await Timer(1, unit="ns")
        else:  # combinational DUT: let it settle
            await Timer(1, unit="ns")
        observed = []
        for p in probes:
            raw = int(getattr(dut, "probe_" + p["name"]).value)
            if p["signed"] and (raw >> (p["width"] - 1)) & 1:
                raw -= 1 << p["width"]
            observed.append(f"{p['name']}={raw}")
        print(f"PY cyc={cyc + 1} " + " ".join(observed))


def main():
    workdir, toplevel, abi_json, stim_json = sys.argv[1:5]
    sources = sys.argv[5:]
    os.environ["ABI_JSON"] = abi_json
    os.environ["STIMULUS_JSON"] = stim_json
    # The cocotb subprocess imports this file as the test module.
    here = os.path.dirname(os.path.abspath(__file__))
    os.environ["PYTHONPATH"] = here + os.pathsep + os.environ.get("PYTHONPATH", "")

    from cocotb_tools.runner import get_runner

    runner = get_runner("verilator")
    runner.build(
        verilog_sources=sources,
        hdl_toplevel=toplevel,
        build_dir=os.path.join(workdir, "cocotb_build"),
        # The SVA `assume` is a formal constraint; disable it in simulation (it would trip at
        # startup on X/0). The `assumeOk` probe stays available for constrained-random generation.
        build_args=["-I" + workdir, "-Wno-fatal", "--no-assert"],
    )
    runner.test(hdl_toplevel=toplevel, test_module="ut_cocotb")


if __name__ == "__main__":
    main()
