# Hand-written hazard coverage for R-type instructions
#
# Goal: fill the 3 hazard holes left by riscv-dv for each R-type instruction:
#   - WAR_HAZARD
#   - WAW_HAZARD
#   - NO_HAZARD
#
# riscv-dv hazard classification (priority order, mutually exclusive):
#   RAW:      prev.rd ∈ {curr.rs1, curr.rs2}
#   WAR:      curr.rd ∈ {prev.rs1, prev.rs2}  ∧ ¬RAW
#   WAW:      prev.rd == curr.rd               ∧ ¬RAW ∧ ¬WAR
#   NoHazard: none of the above
#
# Each instruction needs its own hazard coverage (the coverpoint is per-instruction).
# Below: minimal sequence for each R-type instruction, 3 pairs = 4 instructions each.
#
# Constraint difficulty: for each pair, must satisfy the target hazard AND
# exclude higher-priority hazards. E.g., WAW requires prev.rd == curr.rd
# but also prev.rd ∉ {curr.rs1, curr.rs2} (¬RAW) and curr.rd ∉ {prev.rs1, prev.rs2} (¬WAR).
# The engineer must mentally track all 3 conditions simultaneously.

    .text

# ============================================================
# ADD (representative R-type: rd, rs1, rs2)
# ============================================================

# Pair 1: WAR — curr.rd ∈ {prev.rs1, prev.rs2} ∧ ¬RAW
#   prev: add x1, x5, x6    (rd=x1, rs1=x5, rs2=x6)
#   curr: add x5, x7, x8    (rd=x5, rs1=x7, rs2=x8)
#   WAR:  curr.rd=x5 == prev.rs1=x5 ✓
#   ¬RAW: prev.rd=x1 ∉ {x7, x8} ✓
    add x1, x5, x6
    add x5, x7, x8

# Pair 2: WAW — prev.rd == curr.rd ∧ ¬RAW ∧ ¬WAR
#   prev: add x10, x11, x12  (rd=x10, rs1=x11, rs2=x12)
#   curr: add x10, x13, x14  (rd=x10, rs1=x13, rs2=x14)
#   WAW:  prev.rd=x10 == curr.rd=x10 ✓
#   ¬RAW: prev.rd=x10 ∉ {x13, x14} ✓
#   ¬WAR: curr.rd=x10 ∉ {x11, x12} ✓
    add x10, x11, x12
    add x10, x13, x14

# Pair 3: NoHazard — no register dependency
#   prev: add x15, x16, x17
#   curr: add x18, x19, x20
#   ¬RAW: x15 ∉ {x19, x20} ✓
#   ¬WAR: x18 ∉ {x16, x17} ✓
#   ¬WAW: x15 ≠ x18 ✓
    add x15, x16, x17
    add x18, x19, x20

# ============================================================
# SUB
# ============================================================
    sub x1, x5, x6
    sub x5, x7, x8          # WAR
    sub x10, x11, x12
    sub x10, x13, x14       # WAW
    sub x15, x16, x17
    sub x18, x19, x20       # NoHazard

# ============================================================
# AND
# ============================================================
    and x1, x5, x6
    and x5, x7, x8          # WAR
    and x10, x11, x12
    and x10, x13, x14       # WAW
    and x15, x16, x17
    and x18, x19, x20       # NoHazard

# ============================================================
# OR
# ============================================================
    or x1, x5, x6
    or x5, x7, x8           # WAR
    or x10, x11, x12
    or x10, x13, x14        # WAW
    or x15, x16, x17
    or x18, x19, x20        # NoHazard

# ============================================================
# XOR
# ============================================================
    xor x1, x5, x6
    xor x5, x7, x8          # WAR
    xor x10, x11, x12
    xor x10, x13, x14       # WAW
    xor x15, x16, x17
    xor x18, x19, x20       # NoHazard

# ============================================================
# SLL
# ============================================================
    sll x1, x5, x6
    sll x5, x7, x8          # WAR
    sll x10, x11, x12
    sll x10, x13, x14       # WAW
    sll x15, x16, x17
    sll x18, x19, x20       # NoHazard

# ============================================================
# SRL
# ============================================================
    srl x1, x5, x6
    srl x5, x7, x8          # WAR
    srl x10, x11, x12
    srl x10, x13, x14       # WAW
    srl x15, x16, x17
    srl x18, x19, x20       # NoHazard

# ============================================================
# SRA
# ============================================================
    sra x1, x5, x6
    sra x5, x7, x8          # WAR
    sra x10, x11, x12
    sra x10, x13, x14       # WAW
    sra x15, x16, x17
    sra x18, x19, x20       # NoHazard

# ============================================================
# SLT
# ============================================================
    slt x1, x5, x6
    slt x5, x7, x8          # WAR
    slt x10, x11, x12
    slt x10, x13, x14       # WAW
    slt x15, x16, x17
    slt x18, x19, x20       # NoHazard

# ============================================================
# SLTU
# ============================================================
    sltu x1, x5, x6
    sltu x5, x7, x8         # WAR
    sltu x10, x11, x12
    sltu x10, x13, x14      # WAW
    sltu x15, x16, x17
    sltu x18, x19, x20      # NoHazard

# ============================================================
# Summary: 10 instructions × 6 asm lines = 60 instructions
# Each instruction requires the engineer to verify ¬RAW, ¬WAR
# conditions manually for WAR and WAW pairs.
#
# The pattern is mechanically repetitive for same-format instructions,
# but the engineer must still verify each pair's hazard classification
# is correct — a mistake (e.g., accidentally creating RAW instead of
# WAR) is silent and would leave the hole unfilled.
# ============================================================
