#!/usr/bin/env python3
"""Verify hand-written ADD (R-type) coverage correctness."""

instrs = [
    # Phase 1: i=0..30, rd=x(i+1), rs1/rs2 rotated
    (1,8,14), (2,15,27), (3,22,9), (4,29,22), (5,5,4),
    (6,12,17), (7,19,30), (8,26,12), (9,2,25), (10,9,7),
    (11,16,20), (12,23,2), (13,30,15), (14,6,28), (15,13,10),
    (16,20,23), (17,27,5), (18,3,18), (19,10,31), (20,17,13),
    (21,24,26), (22,31,8), (23,7,21), (24,14,3), (25,21,16),
    (26,28,29), (27,4,11), (28,11,24), (29,18,6), (30,25,19),
    (31,1,1),
    # Phase 2: hazard patches
    (31,2,3), (1,31,4), (4,5,6), (10,20,30),
]

target = set(range(1, 32))
rd_set = set(t[0] for t in instrs)
rs1_set = set(t[1] for t in instrs)
rs2_set = set(t[2] for t in instrs)

print(f"rd  coverage: {len(rd_set & target)}/31  missing: {target - rd_set}")
print(f"rs1 coverage: {len(rs1_set & target)}/31  missing: {target - rs1_set}")
print(f"rs2 coverage: {len(rs2_set & target)}/31  missing: {target - rs2_set}")

has_raw = has_war = has_waw = has_nohaz = False
for i in range(len(instrs) - 1):
    rd_e, rs1_e, rs2_e = instrs[i]
    rd_l, rs1_l, rs2_l = instrs[i + 1]
    raw = (rd_e == rs1_l) or (rd_e == rs2_l)
    war = ((rs1_e == rd_l) or (rs2_e == rd_l)) and not raw
    waw = (rd_e == rd_l)
    nohaz = (rd_e != rs1_l and rd_e != rs2_l and
             rs1_e != rd_l and rs2_e != rd_l and
             rd_e != rd_l)
    if raw: has_raw = True
    if war: has_war = True
    if waw: has_waw = True
    if nohaz: has_nohaz = True

print(f"\nHazard coverage:")
print(f"  RAW:      {'PASS' if has_raw else 'FAIL'}")
print(f"  WAR:      {'PASS' if has_war else 'FAIL'}")
print(f"  WAW:      {'PASS' if has_waw else 'FAIL'}")
print(f"  NoHazard: {'PASS' if has_nohaz else 'FAIL'}")

ok = (rd_set >= target and rs1_set >= target and rs2_set >= target
      and has_raw and has_war and has_waw and has_nohaz)
print(f"\nOverall: {'PASS' if ok else 'FAIL'}")
