# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 xinpian-tech
"""Generic constrained-random stimulus generation from an SVA assumption.

A UT module writes its input constraint as SVA `Assume` and mirrors the same
predicate to a probe named `assumeOk`. This sampler generates per-cycle drive
values by rejection sampling: draw random inputs, drive them, and keep a cycle
only if `assumeOk` holds. It is binding-agnostic — the caller supplies the
poke/step/peek operations, so the same generator serves the DPI (ctypes) and VPI
(cocotb) frontends alike.
"""
import random


def generate(ports, poke, step, peek, cycles, seed=0, max_tries=100000):
    """Return ``{drive_port: [value, ...]}`` of length ``cycles``, each cycle's
    values satisfying the ``assumeOk`` probe.

    ``poke(drive_name, value)`` drives an input, ``step()`` advances the model one
    settle, ``peek(probe_name)`` reads a probe as an int. If the contract has no
    ``assumeOk`` probe, every draw is accepted (unconstrained random).
    """
    rng = random.Random(seed)
    drives = [p for p in ports if p["role"] == "Drive"]
    has_assume = any(p["role"] == "Probe" and p["name"] == "assumeOk" for p in ports)
    out = {d["name"]: [] for d in drives}
    for cyc in range(cycles):
        for _ in range(max_tries):
            vals = {d["name"]: rng.getrandbits(d["width"]) for d in drives}
            for d in drives:
                poke(d["name"], vals[d["name"]])
            step()
            if not has_assume or peek("assumeOk"):
                for d in drives:
                    out[d["name"]].append(vals[d["name"]])
                break
        else:
            raise RuntimeError(f"CRV: no sample satisfying assumeOk for cycle {cyc} in {max_tries} tries")
    return out
