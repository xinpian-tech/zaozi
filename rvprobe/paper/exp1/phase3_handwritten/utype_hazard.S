# Hand-written hazard coverage for U-type instructions
#
# Goal: fill WAR, WAW, NoHazard holes for: lui, auipc
#
# U-type format: rd, imm20 (NO rs1, NO rs2)
#
# This format presents a unique challenge for hazard coverage:
#   - Between two U-type instructions, only rd exists
#   - RAW (prev.rd ∈ {curr.rs1, curr.rs2}): IMPOSSIBLE — no rs fields
#   - WAR (curr.rd ∈ {prev.rs1, prev.rs2}): IMPOSSIBLE — no rs fields
#   - WAW (prev.rd == curr.rd): possible
#   - NoHazard (prev.rd ≠ curr.rd): possible
#
# Therefore, to cover WAR for a U-type instruction, the PREVIOUS
# instruction must be a DIFFERENT type that HAS rs fields.
# This is a cross-format dependency that the engineer must recognize.
#
# riscv-dv's coverpoint is per-instruction: when it sees a lui,
# it checks the hazard with whatever instruction came before it.

    .text

# ============================================================
# LUI
# ============================================================

# Pair 1: WAR — need a predecessor WITH rs fields
#   Use addi as predecessor: addi has rs1
#   prev: addi x1, x5, 0     (rd=x1, rs1=x5)
#   curr: lui  x5, 0x12345   (rd=x5)
#   WAR:  curr.rd=x5 == prev.rs1=x5 ✓
#   ¬RAW: prev.rd=x1, but lui has no rs → RAW impossible ✓
    addi x1, x5, 0
    lui x5, 0x12345

# Pair 2: WAW — prev.rd == curr.rd
#   prev: lui x10, 0xAAAAA
#   curr: lui x10, 0xBBBBB
#   WAW:  prev.rd=x10 == curr.rd=x10 ✓
#   ¬RAW: lui has no rs → impossible ✓
#   ¬WAR: lui has no rs → impossible ✓
    lui x10, 0xAAAAA
    lui x10, 0xBBBBB

# Pair 3: NoHazard
#   prev: lui x15, 0x11111
#   curr: lui x18, 0x22222
#   ¬WAW: x15 ≠ x18 ✓  (RAW/WAR impossible between two lui's)
    lui x15, 0x11111
    lui x18, 0x22222

# ============================================================
# AUIPC
# ============================================================

# WAR: need predecessor with rs fields
    addi x2, x6, 0
    auipc x6, 0x12345       # WAR: rd=x6 == prev.rs1=x6

# WAW
    auipc x20, 0xAAAAA
    auipc x20, 0xBBBBB      # WAW: same rd

# NoHazard
    auipc x25, 0x11111
    auipc x28, 0x22222      # NoHazard: different rd

# ============================================================
# Key observation: U-type WAR coverage REQUIRES mixing instruction
# formats. The engineer must:
#   1. Recognize that two lui's can never produce WAR
#   2. Choose an appropriate predecessor instruction type
#   3. Ensure the predecessor's rs1 or rs2 matches the lui's rd
#   4. Ensure no higher-priority hazard (RAW) is triggered
#
# RVProbe handles this automatically: coverWAR() considers the
# instruction format (hasRd/hasRs1/hasRs2) and generates
# appropriate cross-instruction constraints. The engineer never
# needs to reason about which formats can produce which hazards.
#
# Summary: 2 instructions × 6 asm lines = 12 instructions
#   (plus 2 helper addi instructions for WAR coverage)
# ============================================================
