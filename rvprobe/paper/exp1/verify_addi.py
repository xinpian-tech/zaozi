#!/usr/bin/env python3
"""Verify hand-written ADDI (I-type ALU) coverage correctness."""

instrs = [
    # (rd, rs1, imm) — Phase 1: i=0..30
    (1,8,-2048), (2,15,-1), (3,22,0), (4,29,1), (5,5,2047),
    (6,12,-2048), (7,19,-1), (8,26,0), (9,2,1), (10,9,2047),
    (11,16,-2048), (12,23,-1), (13,30,0), (14,6,1), (15,13,2047),
    (16,20,-2048), (17,27,-1), (18,3,0), (19,10,1), (20,17,2047),
    (21,24,-2048), (22,31,-1), (23,7,0), (24,14,1), (25,21,2047),
    (26,28,-2048), (27,4,-1), (28,11,0), (29,18,1), (30,25,2047),
    (31,1,-2048),
    # Phase 2: hazard patches
    (31,2,0), (1,31,1), (31,5,-1), (10,20,2047),
]

target = set(range(1, 32))
imm_boundary = {-2048, -1, 0, 1, 2047}

rd_set = set(t[0] for t in instrs)
rs1_set = set(t[1] for t in instrs)
imm_set = set(t[2] for t in instrs)

print(f"rd  coverage: {len(rd_set & target)}/31  missing: {target - rd_set}")
print(f"rs1 coverage: {len(rs1_set & target)}/31  missing: {target - rs1_set}")
print(f"imm boundary: missing {imm_boundary - imm_set}" if imm_boundary - imm_set else "imm boundary: all covered")

# I-type: only rs1, no rs2
has_raw = has_war = has_waw = has_nohaz = False
for i in range(len(instrs) - 1):
    rd_e, rs1_e, _ = instrs[i]
    rd_l, rs1_l, _ = instrs[i + 1]
    raw = (rd_e == rs1_l)
    war = (rs1_e == rd_l) and not raw
    waw = (rd_e == rd_l)
    nohaz = (rd_e != rs1_l and rs1_e != rd_l and rd_e != rd_l)
    if raw: has_raw = True
    if war: has_war = True
    if waw: has_waw = True
    if nohaz: has_nohaz = True

print(f"\nHazard coverage:")
print(f"  RAW:      {'PASS' if has_raw else 'FAIL'}")
print(f"  WAR:      {'PASS' if has_war else 'FAIL'}")
print(f"  WAW:      {'PASS' if has_waw else 'FAIL'}")
print(f"  NoHazard: {'PASS' if has_nohaz else 'FAIL'}")

ok = (rd_set >= target and rs1_set >= target
      and not (imm_boundary - imm_set)
      and has_raw and has_war and has_waw and has_nohaz)
print(f"\nOverall: {'PASS' if ok else 'FAIL'}")
