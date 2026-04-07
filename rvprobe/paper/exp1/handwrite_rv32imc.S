# Hand-written coverage hole closure for RV32IMC
#
# Covers all fillable holes from VCS SV coverage baseline (137 holes).
# Inherits all RV32I holes from handwrite.S plus M/C extension holes.

    .text
    .globl _start
_start:

    # Set up trap handler (skip faulting instructions)
    la   t0, _trap_handler
    csrw mtvec, t0
    j    _after_trap

    .balign 4
_trap_handler:
    csrr t0, mepc
    addi t0, t0, 4
    csrw mepc, t0
    mret
_after_trap:

    # Scratch buffer address
    la   x3, _scratch_buf

# ============================================================
# Include all RV32I holes (from handwrite.S)
# ============================================================

# --- R-type hazard coverage (WAR/WAW/NoHazard) ---
# add
    add x1, x5, x6
    add x5, x7, x8          # WAR
    add x10, x11, x12
    add x10, x13, x14       # WAW
    add x15, x16, x17
    add x18, x19, x20       # NoHazard
# sub
    sub x1, x5, x6
    sub x5, x7, x8
    sub x10, x11, x12
    sub x10, x13, x14
    sub x15, x16, x17
    sub x18, x19, x20
# and
    and x1, x5, x6
    and x5, x7, x8
    and x10, x11, x12
    and x10, x13, x14
    and x15, x16, x17
    and x18, x19, x20
# or
    or x1, x5, x6
    or x5, x7, x8
    or x10, x11, x12
    or x10, x13, x14
    or x15, x16, x17
    or x18, x19, x20
# xor
    xor x1, x5, x6
    xor x5, x7, x8
    xor x10, x11, x12
    xor x10, x13, x14
    xor x15, x16, x17
    xor x18, x19, x20
# sll/srl/sra/slt/sltu
    sll x2, x5, x6
    srl x2, x5, x6
    sra x2, x5, x6
    slt x2, x5, x6
    sltu x2, x5, x6

# --- I-type with rd=SP ---
    addi  x2, x5, 10
    andi  x2, x5, 10
    ori   x2, x5, 10
    xori  x2, x5, 10
    slti  x2, x5, 10
    sltiu x2, x5, 10
    slli  x2, x5, 1
    srli  x2, x5, 1
    srai  x2, x5, 1

# --- U-type with rd=SP ---
    lui   x2, 0x12345
    auipc x2, 0

# --- R-type with rd=SP ---
    sub  x2, x5, x6
    sra  x2, x5, x6
    srl  x2, x5, x6
    sll  x2, x5, x6
    slt  x2, x5, x6
    sltu x2, x5, x6
    and  x2, x5, x6
    xor  x2, x5, x6
    or   x2, x5, x6

# ============================================================
# M extension: mul/div/rem with rd=SP
# ============================================================

    mul    x2, x5, x6
    mulh   x2, x5, x6
    mulhu  x2, x5, x6
    mulhsu x2, x5, x6
    div    x2, x5, x6
    divu   x2, x5, x6
    rem    x2, x5, x6
    remu   x2, x5, x6

# rem DIV_OVERFLOW: dividend = INT_MIN, divisor = -1
    li     x5, 0x80000000    # INT_MIN
    li     x6, -1
    rem    x7, x5, x6        # DIV_OVERFLOW

# ============================================================
# CSR with rd=SP + rd=ZERO
# ============================================================

    csrrw  x2, mscratch, x5
    csrrs  x2, mscratch, x5
    csrrc  x2, mscratch, x5
    csrrwi x2, mscratch, 1
    csrrsi x2, mscratch, 1
    csrrci x2, mscratch, 1
    csrrs  x0, mscratch, x5  # rd=ZERO

# ============================================================
# Load with rd=SP and rs1=SP
# ============================================================

    mv   x4, x2              # save SP
    mv   x2, x3              # SP = scratch_buf
    lb   x2, 0(x3)
    lbu  x2, 1(x3)
    lh   x2, 2(x3)
    lhu  x2, 4(x3)
    lw   x2, 8(x3)
    lb   x6, 0(x2)
    lbu  x6, 1(x2)
    lh   x6, 2(x2)
    lhu  x6, 4(x2)
    lw   x6, 8(x2)
    mv   x2, x4              # restore SP

# ============================================================
# Store with rs2=ZERO and rs1=SP
# ============================================================

    sb   x0, 0(x3)
    sh   x0, 2(x3)
    sw   x0, 4(x3)
    mv   x4, x2
    mv   x2, x3
    sb   x6, 8(x2)
    sh   x6, 10(x2)
    sw   x6, 12(x2)
    mv   x2, x4

# ============================================================
# JAL/JALR with rd=SP, rd=ZERO
# ============================================================

    j    1f                   # jal rd=ZERO
1:
    jal  x2, 2f               # jal rd=SP
2:
    la   x5, 3f
    jalr x2, x5, 0            # jalr rd=SP
3:

# ============================================================
# Logical similarity (IDENTICAL/OPPOSITE for I-type)
# ============================================================

    li   x21, 42
    andi x22, x21, 42         # IDENTICAL
    li   x21, 0x555
    andi x22, x21, -1366      # OPPOSITE
    li   x21, 42
    ori  x22, x21, 42
    li   x21, 0x555
    ori  x22, x21, -1366      # OPPOSITE
    li   x21, 42
    xori x22, x21, 42         # IDENTICAL
    li   x21, 0x555
    xori x22, x21, -1366      # OPPOSITE

# ============================================================
# C extension: compressed instructions
# ============================================================

# c_add rd=SP:  c.add x2, x5
    c.add x2, x5

# c_mv rd=SP:   c.mv x2, x5
    c.mv x2, x5

# c.jr with all missing rs1 registers
.macro cjr_reg reg, label
    la   x5, \label
    mv   \reg, x5
    c.jr \reg
\label:
.endm
    cjr_reg x1,  cjr_ra
    cjr_reg x3,  cjr_gp
    cjr_reg x2,  cjr_sp
    cjr_reg x4,  cjr_tp
    cjr_reg x5,  cjr_t0
    cjr_reg x7,  cjr_t2
    cjr_reg x8,  cjr_s0
    cjr_reg x9,  cjr_s1
    cjr_reg x14, cjr_a4
    cjr_reg x16, cjr_a6
    cjr_reg x19, cjr_s3
    cjr_reg x21, cjr_s5
    cjr_reg x23, cjr_s7
    cjr_reg x24, cjr_s8
    cjr_reg x27, cjr_s11
    cjr_reg x28, cjr_t3
    cjr_reg x30, cjr_t5
    cjr_reg x31, cjr_t6

# c.jalr with all missing rs1 registers
.macro cjalr_reg reg, label
    la   x5, \label
    mv   \reg, x5
    c.jalr \reg
\label:
.endm
    cjalr_reg x2,  cjalr_sp
    cjalr_reg x3,  cjalr_gp
    cjalr_reg x12, cjalr_a2
    cjalr_reg x13, cjalr_a3
    cjalr_reg x16, cjalr_a6
    cjalr_reg x18, cjalr_s2
    cjalr_reg x20, cjalr_s4
    cjalr_reg x21, cjalr_s5
    cjalr_reg x23, cjalr_s7
    cjalr_reg x26, cjalr_s10
    cjalr_reg x30, cjalr_t5

# jalr with missing rd registers
.macro jalr_rd rd_reg, label
    la   x5, \label
    jalr \rd_reg, x5, 0
\label:
.endm
    jalr_rd x3,  jalr_gp
    jalr_rd x5,  jalr_t0
    jalr_rd x7,  jalr_t2
    jalr_rd x8,  jalr_s0
    jalr_rd x12, jalr_a2
    jalr_rd x13, jalr_a3
    jalr_rd x14, jalr_a4
    jalr_rd x15, jalr_a5
    jalr_rd x19, jalr_s3
    jalr_rd x26, jalr_s10
    jalr_rd x27, jalr_s11
    jalr_rd x31, jalr_t6

# jalr cross-coverage: specific rd×rs1 pairs
    la   x6, jalr_cross1
    jalr x1, x6, 0      # rd=ra, rs1=t1 → [ra][t1] cross
jalr_cross1:
    la   x1, jalr_cross2
    jalr x6, x1, 0      # rd=t1, rs1=ra → [t1][ra] cross
jalr_cross2:

# c_slli hazard: RAW
    c.slli x5, 1
    add    x6, x5, x7  # RAW: rs1=x5 == prev c.slli rd=x5

# c_lw hazard: RAW + WAR
    mv     x8, x3       # x8 = scratch_buf (valid base)
    c.lw   x9, 0(x8)    # RAW setup: rd=x9
    add    x10, x9, x7  # RAW: rs1=x9 == prev c.lw rd=x9
    # c_lw WAR: next instr writes to c.lw's base register
    c.lw   x10, 0(x8)
    add    x8, x10, x0  # WAR: rd=x8 == prev c.lw rs1=x8

# c_sw hazard: WAR + WAW
    mv     x8, x3
    c.sw   x9, 0(x8)
    add    x8, x9, x0   # WAR: rd=x8 overwrites c.sw's rs1=x8
    mv     x8, x3
    c.sw   x9, 0(x8)
    c.sw   x10, 0(x8)   # WAW: same offset, same base

# c_addi4spn hazard: WAR
    c.addi4spn x8, x2, 4
    mv     x2, x8        # WAR: rd=x2(sp) == c.addi4spn implicit src

# c_addi16sp hazard: WAR
    c.addi16sp x2, 16
    mv     x5, x2        # WAR: uses x2 after c.addi16sp wrote it
    c.addi16sp x2, -16   # restore SP

# Additional rd=SP for sw and lw
    mv     x4, x2
    mv     x2, x3
    sw     x6, 0(x2)     # sw rs1=SP
    lw     x2, 0(x3)     # lw rd=SP
    mv     x2, x4

# Additional rd/rs2=ZERO
    j      jal_zero       # jal rd=ZERO
jal_zero:
    csrrs  x0, mscratch, x5  # csrrs rd=ZERO
    sb     x0, 20(x3)    # sb rs2=ZERO
    sh     x0, 22(x3)    # sh rs2=ZERO
    sw     x0, 24(x3)    # sw rs2=ZERO

# ECALL
    ecall

# ============================================================
# Hint instructions
# ============================================================

    # hint slli: c.slli x0, 1 (hint: rd=x0 with non-zero shamt)
    .half 0x0006             # c.slli x0, 1 encoding
    # hint srli64: c.srli64 is encoded as c.srli x8, 0
    # (not directly expressible in gas, use .half)

# ============================================================

    .data
    .balign 16
    .globl _scratch_buf
_scratch_buf:
    .fill 256, 1, 0
