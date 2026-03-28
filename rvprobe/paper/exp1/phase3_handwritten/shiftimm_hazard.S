# Hand-written hazard coverage for Shift-immediate instructions
#
# Goal: fill WAR, WAW, NoHazard holes for: slli, srli, srai
#
# Shift-imm format: rd, rs1, shamt (no rs2)
# Register fields identical to I-type ALU (rd + rs1), so hazard logic is the same:
#   RAW:      prev.rd == curr.rs1
#   WAR:      curr.rd == prev.rs1              ∧ ¬RAW
#   WAW:      prev.rd == curr.rd               ∧ ¬RAW ∧ ¬WAR
#   NoHazard: none of the above

    .text

# ============================================================
# SLLI
# ============================================================

# Pair 1: WAR — curr.rd == prev.rs1 ∧ prev.rd ≠ curr.rs1
    slli x1, x5, 1
    slli x5, x7, 2          # WAR: rd=x5 == prev.rs1=x5; prev.rd=x1 ≠ rs1=x7

# Pair 2: WAW — prev.rd == curr.rd ∧ ¬RAW ∧ ¬WAR
    slli x10, x11, 3
    slli x10, x13, 4        # WAW: rd=x10 == prev.rd=x10; x10 ≠ x13; x10 ≠ x11

# Pair 3: NoHazard
    slli x15, x16, 5
    slli x18, x19, 6        # NoHazard: no overlap

# ============================================================
# SRLI
# ============================================================
    srli x1, x5, 1
    srli x5, x7, 2          # WAR
    srli x10, x11, 3
    srli x10, x13, 4        # WAW
    srli x15, x16, 5
    srli x18, x19, 6        # NoHazard

# ============================================================
# SRAI
# ============================================================
    srai x1, x5, 1
    srai x5, x7, 2          # WAR
    srai x10, x11, 3
    srai x10, x13, 4        # WAW
    srai x15, x16, 5
    srai x18, x19, 6        # NoHazard

# ============================================================
# Summary: 3 instructions × 6 asm lines = 18 instructions
# Mechanically identical to I-type ALU — same register fields,
# same hazard structure. This repetition itself is a data point:
# the engineer writes essentially the same pattern 3 more times,
# changing only the mnemonic. RVProbe's shiftImm() library
# function eliminates this duplication.
# ============================================================
