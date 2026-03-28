# Hand-written coverage test for ADD instruction (R-type)
#
# Coverage goals (same as RVProbe rType(35, isAdd())):
#   - rd  covers x1..x31  (31 bins)
#   - rs1 covers x1..x31  (31 bins)
#   - rs2 covers x1..x31  (31 bins)
#   - At least one adjacent pair with RAW hazard
#   - At least one adjacent pair with WAR hazard (pure, no RAW)
#   - At least one adjacent pair with WAW hazard
#   - At least one adjacent pair with no hazard
#
# Strategy:
#   Instructions 1-31:  rd=x1..x31, rs1 and rs2 chosen to cover all 31 bins
#                       while embedding the required hazard patterns.
#   Instructions 32-35: extra instructions to fill remaining rs1/rs2 bins.
#
# This requires careful manual tracking of which register bins are covered
# and which hazard pairs exist. Any mistake (duplicate bin, missing hazard,
# register conflict) is silent — the assembler will not warn you.

    .section ".text"
    .globl _start
_start:

# --- Instructions 1-31: cover rd x1..x31 ---
# We also need rs1 to cover x1..x31 and rs2 to cover x1..x31.
# Hazard plan:
#   Pair (3,4):  RAW — inst3.rd == inst4.rs1
#   Pair (7,8):  WAR — inst7.rs1 == inst8.rd, but inst7.rd != inst8.rs1/rs2
#   Pair (11,12): WAW — inst11.rd == inst12.rd  (NOT actually: we reuse rd bin)
#                 ... but wait, rd must cover all x1-x31, so we can't repeat rd.
#                 WAW requires rd_i == rd_{i+1}, conflicting with full rd coverage.
#                 Solution: use instructions 32-35 for the WAW pair.
#   Pair (1,2):  NoHazard — no register overlap between adjacent instructions.

# Inst  1: rd=x1,  rs1=x2,  rs2=x3   — NoHazard with next (x1 not in {x4,x5})
    add x1,  x2,  x3
# Inst  2: rd=x2,  rs1=x4,  rs2=x5   — NoHazard with prev
    add x2,  x4,  x5
# Inst  3: rd=x3,  rs1=x6,  rs2=x7   — RAW setup: rd=x3
    add x3,  x6,  x7
# Inst  4: rd=x4,  rs1=x3,  rs2=x8   — RAW: rs1=x3 == prev.rd=x3 ✓
    add x4,  x3,  x8
# Inst  5: rd=x5,  rs1=x9,  rs2=x10
    add x5,  x9,  x10
# Inst  6: rd=x6,  rs1=x11, rs2=x12
    add x6,  x11, x12
# Inst  7: rd=x7,  rs1=x14, rs2=x13  — WAR setup: rs1=x14
    add x7,  x14, x13
# Inst  8: rd=x14, rs1=x15, rs2=x16  — WAR: rd=x14 == prev.rs1=x14 ✓
#                                        NOT RAW: rs1=x15 != prev.rd=x7 ✓
#                                        ... but wait, rd=x14 breaks our rd sequence.
#                                        We need rd=x8 here. Rethink...

# --- RESTART with systematic approach ---
# The difficulty illustrates why hand-writing is error-prone:
# covering all bins while satisfying hazard constraints simultaneously
# requires solving a constraint satisfaction problem — exactly what RVProbe
# automates.
#
# Let's use a simpler (suboptimal) approach: first cover rd bins with
# arbitrary rs1/rs2, then patch hazards at the end.

# Phase 1: Cover rd=x1..x31, rs1 bins, rs2 bins (31 instructions)
# Use a rotating scheme: rs1 = x((i*7) mod 31 + 1), rs2 = x((i*13) mod 31 + 1)
# to try to cover many bins. Manual verification needed afterwards.

# i=0:  rd=x1,  rs1=x(7%31+1)=x8,    rs2=x(13%31+1)=x14
    add x1,  x8,  x14
# i=1:  rd=x2,  rs1=x(14%31+1)=x15,   rs2=x(26%31+1)=x27
    add x2,  x15, x27
# i=2:  rd=x3,  rs1=x(21%31+1)=x22,   rs2=x(39%31+1)=x(8+1)=x9
    add x3,  x22, x9
# i=3:  rd=x4,  rs1=x(28%31+1)=x29,   rs2=x(52%31+1)=x(21+1)=x22
    add x4,  x29, x22
# i=4:  rd=x5,  rs1=x(35%31+1)=x(4+1)=x5,  rs2=x(65%31+1)=x(3+1)=x4
    add x5,  x5,  x4
# i=5:  rd=x6,  rs1=x(42%31+1)=x(11+1)=x12, rs2=x(78%31+1)=x(16+1)=x17
    add x6,  x12, x17
# i=6:  rd=x7,  rs1=x(49%31+1)=x(18+1)=x19, rs2=x(91%31+1)=x(29+1)=x30
    add x7,  x19, x30
# i=7:  rd=x8,  rs1=x(56%31+1)=x(25+1)=x26, rs2=x(104%31+1)=x(11+1)=x12
    add x8,  x26, x12
# i=8:  rd=x9,  rs1=x(63%31+1)=x(1+1)=x2,   rs2=x(117%31+1)=x(24+1)=x25
    add x9,  x2,  x25
# i=9:  rd=x10, rs1=x(70%31+1)=x(8+1)=x9,   rs2=x(130%31+1)=x(6+1)=x7
    add x10, x9,  x7
# i=10: rd=x11, rs1=x(77%31+1)=x(15+1)=x16,  rs2=x(143%31+1)=x(19+1)=x20
    add x11, x16, x20
# i=11: rd=x12, rs1=x(84%31+1)=x(22+1)=x23,  rs2=x(156%31+1)=x(1+1)=x2
    add x12, x23, x2
# i=12: rd=x13, rs1=x(91%31+1)=x(29+1)=x30,  rs2=x(169%31+1)=x(14+1)=x15
    add x13, x30, x15
# i=13: rd=x14, rs1=x(98%31+1)=x(5+1)=x6,    rs2=x(182%31+1)=x(27+1)=x28
    add x14, x6,  x28
# i=14: rd=x15, rs1=x(105%31+1)=x(12+1)=x13,  rs2=x(195%31+1)=x(9+1)=x10
    add x15, x13, x10
# i=15: rd=x16, rs1=x(112%31+1)=x(19+1)=x20,  rs2=x(208%31+1)=x(22+1)=x23
    add x16, x20, x23
# i=16: rd=x17, rs1=x(119%31+1)=x(26+1)=x27,  rs2=x(221%31+1)=x(4+1)=x5
    add x17, x27, x5
# i=17: rd=x18, rs1=x(126%31+1)=x(2+1)=x3,    rs2=x(234%31+1)=x(17+1)=x18
    add x18, x3,  x18
# i=18: rd=x19, rs1=x(133%31+1)=x(9+1)=x10,   rs2=x(247%31+1)=x(30+1)=x31
    add x19, x10, x31
# i=19: rd=x20, rs1=x(140%31+1)=x(16+1)=x17,  rs2=x(260%31+1)=x(12+1)=x13
    add x20, x17, x13
# i=20: rd=x21, rs1=x(147%31+1)=x(23+1)=x24,  rs2=x(273%31+1)=x(25+1)=x26
    add x21, x24, x26
# i=21: rd=x22, rs1=x(154%31+1)=x(30+1)=x31,  rs2=x(286%31+1)=x(7+1)=x8
    add x22, x31, x8
# i=22: rd=x23, rs1=x(161%31+1)=x(6+1)=x7,    rs2=x(299%31+1)=x(20+1)=x21
    add x23, x7,  x21
# i=23: rd=x24, rs1=x(168%31+1)=x(13+1)=x14,  rs2=x(312%31+1)=x(2+1)=x3
    add x24, x14, x3
# i=24: rd=x25, rs1=x(175%31+1)=x(20+1)=x21,  rs2=x(325%31+1)=x(15+1)=x16
    add x25, x21, x16
# i=25: rd=x26, rs1=x(182%31+1)=x(27+1)=x28,  rs2=x(338%31+1)=x(28+1)=x29
    add x26, x28, x29
# i=26: rd=x27, rs1=x(189%31+1)=x(3+1)=x4,    rs2=x(351%31+1)=x(10+1)=x11
    add x27, x4,  x11
# i=27: rd=x28, rs1=x(196%31+1)=x(10+1)=x11,  rs2=x(364%31+1)=x(23+1)=x24
    add x28, x11, x24
# i=28: rd=x29, rs1=x(203%31+1)=x(17+1)=x18,  rs2=x(377%31+1)=x(5+1)=x6
    add x29, x18, x6
# i=29: rd=x30, rs1=x(210%31+1)=x(24+1)=x25,  rs2=x(390%31+1)=x(18+1)=x19
    add x30, x25, x19
# i=30: rd=x31, rs1=x(217%31+1)=x(31%31+1)=x1, rs2=x(403%31+1)=x(31%31+1)=x1
    add x31, x1,  x1

# Phase 2: Extra instructions for hazard coverage + missing bins
# Inst 32: WAW with inst 31 (rd=x31 == rd=x31)
    add x31, x2,  x3
# Inst 33: RAW with inst 32 (rs1=x31 == prev.rd=x31)
    add x1,  x31, x4
# Inst 34: WAR with inst 33 — prev.rs2=x4, so rd=x4; ensure no RAW (rs1,rs2 != prev.rd=x1)
    add x4,  x5,  x6
# Inst 35: NoHazard with inst 34 (no overlap: rd=x10, rs1=x20, rs2=x30 vs prev rd=x4,rs1=x5,rs2=x6)
    add x10, x20, x30

# --- Verification checklist (must be done manually): ---
# TODO: verify rd  bins x1..x31 all covered  (x31 appears twice, but all 31 covered)
# TODO: verify rs1 bins x1..x31 all covered  (need to check: is x1 only in inst30?)
#       Missing rs1 bins: must manually enumerate and check...
#       rs1 values used: x8,x15,x22,x29,x5,x12,x19,x26,x2,x9,x16,x23,x30,x6,
#                        x13,x20,x27,x3,x10,x17,x24,x31,x7,x14,x21,x28,x4,x11,
#                        x18,x25,x1, x2,x31,x5,x20
#       Looks like all 31 covered. But is this actually correct? Hard to verify...
# TODO: verify rs2 bins x1..x31 all covered
#       rs2 values used: x14,x27,x9,x22,x4,x17,x30,x12,x25,x7,x20,x2,x15,x28,
#                        x10,x23,x5,x18,x31,x13,x26,x8,x21,x3,x16,x29,x11,x24,
#                        x6,x19,x1, x3,x4,x6,x30
#       Looks like all 31 covered. But again, manual verification is tedious.
# TODO: verify RAW pair exists   — inst32→33: rd=x31, rs1=x31 ✓
# TODO: verify WAR pair exists   — inst33→34: rs2=x4, rd=x4; rs1/rs2=x5,x6 != x1 ✓
# TODO: verify WAW pair exists   — inst31→32: rd=x31, rd=x31 ✓
# TODO: verify NoHazard pair     — inst34→35: no overlap ✓
#
# Total effort: ~2 hours of careful manual work for ONE instruction type.
# RV32I has 27 instruction types. Estimated total: ~54 hours.
# RVProbe: ~5 minutes to write, 0 manual verification needed.
