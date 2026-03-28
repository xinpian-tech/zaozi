# Hand-written hazard coverage for I-type ALU instructions
#
# Goal: fill WAR, WAW, NoHazard holes for: addi, andi, ori, xori, slti, sltiu
#
# I-type ALU format: rd, rs1, imm12 (no rs2)
# Hazard classification uses rd and rs1 only:
#   RAW:      prev.rd == curr.rs1
#   WAR:      curr.rd == prev.rs1              ∧ ¬RAW
#   WAW:      prev.rd == curr.rd               ∧ ¬RAW ∧ ¬WAR
#   NoHazard: none of the above
#
# Compared to R-type, I-type has only rs1 (no rs2), so:
#   - WAR condition is simpler: curr.rd == prev.rs1
#   - But the engineer must still exclude RAW: prev.rd ≠ curr.rs1

    .text

# ============================================================
# ADDI (representative I-type ALU: rd, rs1, imm12)
# ============================================================

# Pair 1: WAR — curr.rd == prev.rs1 ∧ prev.rd ≠ curr.rs1
#   prev: addi x1, x5, 10    (rd=x1, rs1=x5)
#   curr: addi x5, x7, 20    (rd=x5, rs1=x7)
#   WAR:  curr.rd=x5 == prev.rs1=x5 ✓
#   ¬RAW: prev.rd=x1 ≠ curr.rs1=x7 ✓
    addi x1, x5, 10
    addi x5, x7, 20

# Pair 2: WAW — prev.rd == curr.rd ∧ ¬RAW ∧ ¬WAR
#   prev: addi x10, x11, 30   (rd=x10, rs1=x11)
#   curr: addi x10, x13, 40   (rd=x10, rs1=x13)
#   WAW:  prev.rd=x10 == curr.rd=x10 ✓
#   ¬RAW: prev.rd=x10 ≠ curr.rs1=x13 ✓
#   ¬WAR: curr.rd=x10 ≠ prev.rs1=x11 ✓
    addi x10, x11, 30
    addi x10, x13, 40

# Pair 3: NoHazard
#   prev: addi x15, x16, 50
#   curr: addi x18, x19, 60
#   ¬RAW: x15 ≠ x19 ✓  ¬WAR: x18 ≠ x16 ✓  ¬WAW: x15 ≠ x18 ✓
    addi x15, x16, 50
    addi x18, x19, 60

# ============================================================
# ANDI
# ============================================================
    andi x1, x5, 10
    andi x5, x7, 20         # WAR
    andi x10, x11, 30
    andi x10, x13, 40       # WAW
    andi x15, x16, 50
    andi x18, x19, 60       # NoHazard

# ============================================================
# ORI
# ============================================================
    ori x1, x5, 10
    ori x5, x7, 20          # WAR
    ori x10, x11, 30
    ori x10, x13, 40        # WAW
    ori x15, x16, 50
    ori x18, x19, 60        # NoHazard

# ============================================================
# XORI
# ============================================================
    xori x1, x5, 10
    xori x5, x7, 20         # WAR
    xori x10, x11, 30
    xori x10, x13, 40       # WAW
    xori x15, x16, 50
    xori x18, x19, 60       # NoHazard

# ============================================================
# SLTI
# ============================================================
    slti x1, x5, 10
    slti x5, x7, 20         # WAR
    slti x10, x11, 30
    slti x10, x13, 40       # WAW
    slti x15, x16, 50
    slti x18, x19, 60       # NoHazard

# ============================================================
# SLTIU
# ============================================================
    sltiu x1, x5, 10
    sltiu x5, x7, 20        # WAR
    sltiu x10, x11, 30
    sltiu x10, x13, 40      # WAW
    sltiu x15, x16, 50
    sltiu x18, x19, 60      # NoHazard

# ============================================================
# Summary: 6 instructions × 6 asm lines = 36 instructions
# Same mechanical pattern as R-type, but with one fewer register
# field to track. The hazard exclusion logic is slightly simpler
# (only rs1, no rs2) but the engineer must still verify each pair.
# ============================================================
