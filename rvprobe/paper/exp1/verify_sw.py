#!/usr/bin/env python3
"""Verify hand-written SW (Store) coverage correctness."""

# (rs1, rs2) — stores have no rd
instrs = [
    # Phase 1: i=0..30
    (1,8), (2,15), (3,22), (4,29), (5,5),
    (6,12), (7,19), (8,26), (9,2), (10,9),
    (11,16), (12,23), (13,30), (14,6), (15,13),
    (16,20), (17,27), (18,3), (19,10), (20,17),
    (21,24), (22,31), (23,7), (24,14), (25,21),
    (26,28), (27,4), (28,11), (29,18), (30,25),
    (31,1),
    # Phase 2: padding
    (2,1), (4,3), (6,5), (8,7),
]

target = set(range(1, 32))
rs1_set = set(t[0] for t in instrs)
rs2_set = set(t[1] for t in instrs)

print(f"rs1 coverage: {len(rs1_set & target)}/31  missing: {target - rs1_set}")
print(f"rs2 coverage: {len(rs2_set & target)}/31  missing: {target - rs2_set}")

# Stores have no rd, so no RAW/WAR/WAW — only NoHazard is applicable.
# Between any two stores, there is trivially no hazard (no destination register).
print(f"\nNoHazard: PASS (trivially satisfied — stores have no rd)")

ok = rs1_set >= target and rs2_set >= target
print(f"\nOverall: {'PASS' if ok else 'FAIL'}")
