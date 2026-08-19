# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 xinpian-tech
"""Generic constrained-random stimulus generation from an SVA assumption.

A UT module writes its input constraint as SVA `Assume` and mirrors the same
predicate to a probe named `assumeOk`. This sampler generates per-cycle drive
values by rejection sampling: draw random inputs, drive them, and keep a cycle
only if `assumeOk` holds. It depends only on a [[backend.Backend]], so the same
generator serves the DPI and VPI frontends alike.
"""
import random


async def generate(backend, cycles, seed=0, max_tries=100000):
    """Return ``{drive_port: [value, ...]}`` of length ``cycles``, each cycle's values
    satisfying the ``assumeOk`` probe. With no such probe, every draw is accepted."""
    rng = random.Random(seed)
    drives = backend.drives
    assume = backend.probe("assumeOk")
    out = {d["name"]: [] for d in drives}
    for cyc in range(cycles):
        for _ in range(max_tries):
            vals = {d["name"]: rng.getrandbits(d["width"]) for d in drives}
            for d in drives:
                backend.poke(d, vals[d["name"]])
            await backend.step()
            if assume is None or backend.peek(assume):
                for d in drives:
                    out[d["name"]].append(vals[d["name"]])
                break
        else:
            raise RuntimeError(f"CRV: no sample satisfying assumeOk for cycle {cyc} in {max_tries} tries")
    return out
