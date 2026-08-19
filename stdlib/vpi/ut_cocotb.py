#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 xinpian-tech
"""VPI frontend: a [[backend.Backend]] realized over cocotb's VPI signal access.

The same operation ABI (poke/peek/step) as the DPI frontend, but poke/peek go
straight to the lib model's ports through VPI (`dut.drive_<name>.value` /
`int(dut.probe_<name>.value)`) — no DPI wrapper, only the lib model. The shared
drivers (`backend.replay`, `crv.generate`) run unchanged.

Run as a script it is the cocotb *runner*: build the lib model with Verilator and
launch the cocotb test below, whose mode/paths come from environment variables.

    ut_cocotb.py <workdir> <toplevel> <port.json> <stimulus.json> <sv sources...>
"""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "frontend"))
import backend as be  # noqa: E402
import crv  # noqa: E402

import cocotb
from cocotb.triggers import Timer


class CocotbBackend(be.Backend):
    def __init__(self, dut, ports):
        super().__init__(ports)
        self.dut = dut

    def poke(self, port, value):
        getattr(self.dut, be.lib_input(port)).value = value & ((1 << port["width"]) - 1)

    def peek(self, port):
        raw = int(getattr(self.dut, be.lib_probe(port)).value)
        return be.as_signed(raw, port["width"], port["signed"])

    async def _eval(self):
        await Timer(1, unit="ns")


@cocotb.test()
async def run(dut):
    spec = json.load(open(os.environ["PORT_JSON"]))
    b = CocotbBackend(dut, spec["ports"])
    if os.environ.get("UT_MODE") == "generate":
        stim = await crv.generate(b, int(os.environ["CYCLES"]), int(os.environ["SEED"]))
        json.dump(stim, open(os.environ["OUT_JSON"], "w"), indent=2)
    else:
        await be.replay(b, json.load(open(os.environ["STIMULUS_JSON"])))


def _run(workdir, toplevel, sources):
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


def main():
    args = sys.argv[1:]
    if args and args[0] == "generate":
        # generate <workdir> <toplevel> <port.json> <cycles> <seed> <out.json> <sources...>
        _, workdir, toplevel, port_json, cycles, seed, out_json, *sources = args
        os.environ.update(UT_MODE="generate", PORT_JSON=port_json, CYCLES=cycles, SEED=seed, OUT_JSON=out_json)
        _run(workdir, toplevel, sources)
    else:
        rest = args[1:] if (args and args[0] == "drive") else args
        workdir, toplevel, port_json, stim_json, *sources = rest
        os.environ.update(PORT_JSON=port_json, STIMULUS_JSON=stim_json)
        _run(workdir, toplevel, sources)


if __name__ == "__main__":
    main()
