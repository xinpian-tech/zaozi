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

# c.jr with various rs1 (covering missing regs)
    la   x5, 4f
    mv   x1, x5     # ra
    mv   x3, x5     # gp
    mv   x6, x5     # t1 (=x6? no, t0=x5, t1=x6)

    # c.jr needs rs1 to be a valid code address
    c.jr x1          # ra
4:
    la   x5, 5f
    mv   x3, x5
    c.jr x3          # gp
5:
    la   x5, 6f
    mv   x2, x5
    c.jr x2          # sp
6:
    la   x5, 7f
    mv   x4, x5
    c.jr x4          # tp
7:

# c.jalr with various rs1
    la   x5, 8f
    mv   x3, x5
    c.jalr x3        # gp
8:
    la   x5, 9f
    mv   x2, x5
    c.jalr x2        # sp
9:

# c_slli hazard: RAW
    c.slli x5, 1
    add    x6, x5, x7  # RAW: rs1=x5 == prev c.slli rd=x5

# c_lw hazard: RAW + WAR (c.lw/c.sw use x8-x15 for both rd/rs1/rs2)
    mv     x8, x3       # x8 = scratch_buf (valid base for c.lw/c.sw)
    c.lw   x9, 0(x8)    # RAW setup: rd=x9
    add    x10, x9, x7  # RAW: rs1=x9 == prev c.lw rd=x9
    c.lw   x10, 0(x8)
    c.sw   x11, 0(x10)  # uses x10 as addr after c.lw wrote it

# c_sw hazard: WAR + WAW
    c.sw   x9, 0(x8)
    c.lw   x8, 0(x8)    # WAR: c.lw rd=x8 overwrites c.sw's rs1=x8

# c_addi4spn hazard: WAR
    c.addi4spn x8, x2, 4   # rd ∈ x8-x15
    add    x2, x8, x0      # WAR

# c_addi16sp hazard: WAR
    c.addi16sp x2, 16
    add    x5, x2, x0      # uses x2 after c.addi16sp

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

# ============================================================
# F/D extension: floating point with rd=SP (fp loads/stores)
# ============================================================

    # FP loads (use scratch_buf as valid address)
    flw   f0, 0(x3)
    fld   f0, 0(x3)

    # FP stores
    fsw   f0, 0(x3)
    fsd   f0, 0(x3)

    # FP arithmetic (use f0-f7)
    fadd.s f0, f1, f2
    fsub.s f0, f1, f2
    fmul.s f0, f1, f2
    fdiv.s f0, f1, f2
    fadd.d f0, f1, f2
    fsub.d f0, f1, f2
    fmul.d f0, f1, f2
    fdiv.d f0, f1, f2

    # FP fused multiply-add
    fmadd.s  f0, f1, f2, f3
    fmsub.s  f0, f1, f2, f3
    fnmadd.s f0, f1, f2, f3
    fnmsub.s f0, f1, f2, f3
    fmadd.d  f0, f1, f2, f3
    fmsub.d  f0, f1, f2, f3
    fnmadd.d f0, f1, f2, f3
    fnmsub.d f0, f1, f2, f3

    # FP compare
    flt.s x5, f0, f1
    flt.d x5, f0, f1
    feq.s x5, f0, f1
    feq.d x5, f0, f1
    fle.s x5, f0, f1
    fle.d x5, f0, f1

    # FP convert
    fcvt.w.s  x5, f0
    fcvt.s.w  f0, x5
    fcvt.w.d  x5, f0
    fcvt.d.w  f0, x5

    # FP move/sign
    fmv.x.w  x5, f0
    fmv.w.x  f0, x5
    fsgnj.s  f0, f1, f2
    fsgnjn.s f0, f1, f2
    fsgnjx.s f0, f1, f2
    fsgnj.d  f0, f1, f2
    fsgnjn.d f0, f1, f2
    fsgnjx.d f0, f1, f2

    # FP min/max
    fmin.s f0, f1, f2
    fmax.s f0, f1, f2
    fmin.d f0, f1, f2
    fmax.d f0, f1, f2

    # FP class
    fclass.s x5, f0
    fclass.d x5, f0

# ============================================================
# A extension: atomics
# ============================================================

    # AMO (atomic memory operations)
    lr.w      x5, (x3)
    sc.w      x5, x6, (x3)
    amoswap.w x5, x6, (x3)
    amoadd.w  x5, x6, (x3)
    amoand.w  x5, x6, (x3)
    amoor.w   x5, x6, (x3)
    amoxor.w  x5, x6, (x3)
    amomin.w  x5, x6, (x3)
    amomax.w  x5, x6, (x3)
    amominu.w x5, x6, (x3)
    amomaxu.w x5, x6, (x3)
