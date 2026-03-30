# Hand-written hazard hole closure for 21 RV32I instructions
#
# Fills 63 holes: 21 instructions × {WAR, WAW, NoHazard}
# riscv-dv (334K instructions) only covered RAW_HAZARD.
#
# Hazard priority (riscv-dv): RAW > WAR > WAW > NoHazard
# Each pair must satisfy the target hazard AND exclude higher-priority ones.

    .text
    .globl _start
_start:

# ============================================================
# R-type (10 instructions × 6 lines = 60)
# Format: op rd, rs1, rs2
# ============================================================

# add
    add x1, x5, x6
    add x5, x7, x8          # WAR: rd=x5==prev.rs1; x1∉{x7,x8}→¬RAW
    add x10, x11, x12
    add x10, x13, x14       # WAW: rd==prev.rd; x10∉{x13,x14}→¬RAW; x10∉{x11,x12}→¬WAR
    add x15, x16, x17
    add x18, x19, x20       # NoHazard

# sub
    sub x1, x5, x6
    sub x5, x7, x8          # WAR
    sub x10, x11, x12
    sub x10, x13, x14       # WAW
    sub x15, x16, x17
    sub x18, x19, x20       # NoHazard

# and
    and x1, x5, x6
    and x5, x7, x8          # WAR
    and x10, x11, x12
    and x10, x13, x14       # WAW
    and x15, x16, x17
    and x18, x19, x20       # NoHazard

# or
    or x1, x5, x6
    or x5, x7, x8           # WAR
    or x10, x11, x12
    or x10, x13, x14        # WAW
    or x15, x16, x17
    or x18, x19, x20        # NoHazard

# xor
    xor x1, x5, x6
    xor x5, x7, x8          # WAR
    xor x10, x11, x12
    xor x10, x13, x14       # WAW
    xor x15, x16, x17
    xor x18, x19, x20       # NoHazard

# sll
    sll x1, x5, x6
    sll x5, x7, x8          # WAR
    sll x10, x11, x12
    sll x10, x13, x14       # WAW
    sll x15, x16, x17
    sll x18, x19, x20       # NoHazard

# srl
    srl x1, x5, x6
    srl x5, x7, x8          # WAR
    srl x10, x11, x12
    srl x10, x13, x14       # WAW
    srl x15, x16, x17
    srl x18, x19, x20       # NoHazard

# sra
    sra x1, x5, x6
    sra x5, x7, x8          # WAR
    sra x10, x11, x12
    sra x10, x13, x14       # WAW
    sra x15, x16, x17
    sra x18, x19, x20       # NoHazard

# slt
    slt x1, x5, x6
    slt x5, x7, x8          # WAR
    slt x10, x11, x12
    slt x10, x13, x14       # WAW
    slt x15, x16, x17
    slt x18, x19, x20       # NoHazard

# sltu
    sltu x1, x5, x6
    sltu x5, x7, x8         # WAR
    sltu x10, x11, x12
    sltu x10, x13, x14      # WAW
    sltu x15, x16, x17
    sltu x18, x19, x20      # NoHazard

# ============================================================
# I-type ALU (6 instructions × 6 lines = 36)
# Format: op rd, rs1, imm12
# ============================================================

# addi
    addi x1, x5, 10
    addi x5, x7, 20         # WAR
    addi x10, x11, 30
    addi x10, x13, 40       # WAW
    addi x15, x16, 50
    addi x18, x19, 60       # NoHazard

# andi
    andi x1, x5, 10
    andi x5, x7, 20         # WAR
    andi x10, x11, 30
    andi x10, x13, 40       # WAW
    andi x15, x16, 50
    andi x18, x19, 60       # NoHazard

# ori
    ori x1, x5, 10
    ori x5, x7, 20          # WAR
    ori x10, x11, 30
    ori x10, x13, 40        # WAW
    ori x15, x16, 50
    ori x18, x19, 60        # NoHazard

# xori
    xori x1, x5, 10
    xori x5, x7, 20         # WAR
    xori x10, x11, 30
    xori x10, x13, 40       # WAW
    xori x15, x16, 50
    xori x18, x19, 60       # NoHazard

# slti
    slti x1, x5, 10
    slti x5, x7, 20         # WAR
    slti x10, x11, 30
    slti x10, x13, 40       # WAW
    slti x15, x16, 50
    slti x18, x19, 60       # NoHazard

# sltiu
    sltiu x1, x5, 10
    sltiu x5, x7, 20        # WAR
    sltiu x10, x11, 30
    sltiu x10, x13, 40      # WAW
    sltiu x15, x16, 50
    sltiu x18, x19, 60      # NoHazard

# ============================================================
# Shift-imm (3 instructions × 6 lines = 18)
# Format: op rd, rs1, shamt
# ============================================================

# slli
    slli x1, x5, 1
    slli x5, x7, 2          # WAR
    slli x10, x11, 3
    slli x10, x13, 4        # WAW
    slli x15, x16, 5
    slli x18, x19, 6        # NoHazard

# srli
    srli x1, x5, 1
    srli x5, x7, 2          # WAR
    srli x10, x11, 3
    srli x10, x13, 4        # WAW
    srli x15, x16, 5
    srli x18, x19, 6        # NoHazard

# srai
    srai x1, x5, 1
    srai x5, x7, 2          # WAR
    srai x10, x11, 3
    srai x10, x13, 4        # WAW
    srai x15, x16, 5
    srai x18, x19, 6        # NoHazard

# ============================================================
# U-type (2 instructions × 6 lines + 2 helpers = 14)
# Format: op rd, imm20 (NO rs1, NO rs2)
#
# Two U-type instructions cannot produce WAR (no rs fields).
# WAR requires a non-U-type predecessor with rs fields.
# ============================================================

# lui
    addi x1, x5, 0          # helper for WAR
    lui x5, 0x12345          # WAR: rd=x5==prev.rs1=x5; lui has no rs→¬RAW
    lui x10, 0xAAAAA
    lui x10, 0xBBBBB         # WAW: rd==prev.rd; no rs→¬RAW¬WAR
    lui x15, 0x11111
    lui x18, 0x22222         # NoHazard

# auipc
    addi x2, x6, 0          # helper for WAR
    auipc x6, 0x12345        # WAR
    auipc x20, 0xAAAAA
    auipc x20, 0xBBBBB       # WAW
    auipc x25, 0x11111
    auipc x28, 0x22222       # NoHazard

# ============================================================
# Total: 128 instructions (60 + 36 + 18 + 14)
# Fills: 63 hazard holes (21 × {WAR, WAW, NoHazard})
# ============================================================
