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
# Logical similarity (6 logical instructions × {IDENTICAL, OPPOSITE, DIFFERENT})
# Format: li setup + logical op; riscv-dv compares rs1_value vs rs2_value/imm
# ============================================================

# --- R-type logical (and, or, xor): 3 bins × 3 ops = 9 groups ---
# Each needs: li + li + op (3 instrs per bin), IDENTICAL uses same reg (2 instrs)

# and — IDENTICAL: same register → same value
    li x21, 42
    and x22, x21, x21       # rs1_value == rs2_value → IDENTICAL
# and — OPPOSITE: all 32 bits differ
    li x21, 0x55555555
    li x22, -1431655766      # 0xAAAAAAAA = ~0x55555555
    and x23, x21, x22       # OPPOSITE
# and — DIFFERENT: ≥5 bits differ
    li x21, 0
    li x22, 255              # 0xFF, 8 bits differ
    and x23, x21, x22       # DIFFERENT

# or — IDENTICAL
    li x21, 42
    or x22, x21, x21        # IDENTICAL
# or — OPPOSITE
    li x21, 0x55555555
    li x22, -1431655766
    or x23, x21, x22        # OPPOSITE
# or — DIFFERENT
    li x21, 0
    li x22, 255
    or x23, x21, x22        # DIFFERENT

# xor — IDENTICAL
    li x21, 42
    xor x22, x21, x21       # IDENTICAL
# xor — OPPOSITE
    li x21, 0x55555555
    li x22, -1431655766
    xor x23, x21, x22       # OPPOSITE
# xor — DIFFERENT
    li x21, 0
    li x22, 255
    xor x23, x21, x22       # DIFFERENT

# --- I-type logical (andi, ori, xori): rs1_value vs imm ---
# Full register state reset before logical tests.

    # Clear ALL general purpose registers to eliminate any stale state
    li x3, 0
    li x4, 0
    li x5, 0
    li x6, 0
    li x7, 0
    li x8, 0
    li x9, 0
    li x10, 0
    li x11, 0
    li x12, 0
    li x13, 0
    li x14, 0
    li x15, 0
    li x16, 0
    li x17, 0
    li x18, 0
    li x19, 0
    li x20, 0
    li x21, 0
    li x22, 0
    li x23, 0
    li x24, 0
    li x25, 0
    li x26, 0
    li x27, 0
    li x28, 0
    li x29, 0
    li x30, 0
    li x31, 0

# andi — IDENTICAL
    li x25, 42
    andi x26, x25, 42       # rs1_val=42, imm=42 → IDENTICAL
# andi — OPPOSITE
    li x27, 0x555
    andi x28, x27, -1366    # rs1_val=0x555, imm=0xFFFFFAAA → OPPOSITE
# andi — DIFFERENT
    li x29, 0
    andi x30, x29, 255      # DIFFERENT

# ori — IDENTICAL
    li x25, 42
    ori x26, x25, 42        # IDENTICAL
# ori — OPPOSITE
    li x27, 0x555
    ori x28, x27, -1366     # OPPOSITE
# ori — DIFFERENT
    li x29, 0
    ori x30, x29, 255       # DIFFERENT

# xori — IDENTICAL
    li x25, 42
    xori x26, x25, 42       # IDENTICAL
# xori — OPPOSITE
    li x27, 0x555
    xori x28, x27, -1366    # OPPOSITE
# xori — DIFFERENT
    li x29, 0
    xori x30, x29, 255      # DIFFERENT

# ============================================================
# cp_rd=SP (x2) holes — 19 instructions missing rd=SP
# riscv-dv avoids SP as destination by default.
# ============================================================

    # R-type: sub, sra, srl, sll, slt, sltu, and, xor, or
    sub  x2, x5, x6
    sra  x2, x5, x6
    srl  x2, x5, x6
    sll  x2, x5, x6
    slt  x2, x5, x6
    sltu x2, x5, x6
    and  x2, x5, x6
    xor  x2, x5, x6
    or   x2, x5, x6

    # I-type: ori, andi, xori, slti, sltiu
    ori   x2, x5, 10
    andi  x2, x5, 10
    xori  x2, x5, 10
    slti  x2, x5, 10
    sltiu x2, x5, 10

    # Shift-imm: srli, srai, slli
    srli x2, x5, 1
    srai x2, x5, 1
    slli x2, x5, 1

    # U-type: lui
    lui  x2, 0x12345

    # CSR: csrrw/csrrs/csrrc rd=SP
    csrrw x2, mscratch, x5
    csrrs x2, mscratch, x5
    csrrc x2, mscratch, x5

    # CSR-imm: csrrwi/csrrsi/csrrci rd=SP
    csrrwi x2, mscratch, 1
    csrrsi x2, mscratch, 1
    csrrci x2, mscratch, 1

    # csrrs rd=ZERO (read-only CSR access)
    csrrs x0, mscratch, x5

    # Load/Store need a valid memory address
    la   x3, _scratch_buf

    # Load: rd=SP
    lb   x2, 0(x3)
    lbu  x2, 0(x3)
    lh   x2, 0(x3)
    lhu  x2, 0(x3)

    # Load: rs1=SP — use SP as base (save/restore SP)
    mv   x4, x2           # save SP
    mv   x2, x3           # SP = scratch buf
    lb   x6, 0(x2)
    lbu  x6, 1(x2)
    lh   x6, 2(x2)
    lhu  x6, 4(x2)
    mv   x2, x4           # restore SP

    # Store: rs1=SP
    mv   x4, x2
    mv   x2, x3
    sb   x6, 40(x2)
    sh   x6, 42(x2)
    sw   x6, 44(x2)
    mv   x2, x4
    # csrrs rd=ZERO (explicit, separate from above)
    csrrs x0, mscratch, x0

    # jalr: rd=SP and other missing rd registers
    la   x5, jalr_sp
    jalr x2, x5, 0       # rd=SP
jalr_sp:
    la   x5, jalr_s3
    jalr x19, x5, 0      # rd=S3
jalr_s3:
    la   x5, jalr_s4
    jalr x20, x5, 0      # rd=S4
jalr_s4:
    la   x5, jalr_s10
    jalr x26, x5, 0      # rd=S10
jalr_s10:
    la   x5, jalr_s11
    jalr x27, x5, 0      # rd=S11
jalr_s11:
    # jalr cross-coverage: rd=ra,rs1=t1 and rd=t1,rs1=ra
    la   x6, jalr_ra_t1
    jalr x1, x6, 0       # rd=ra, rs1=t1
jalr_ra_t1:
    la   x1, jalr_t1_ra
    jalr x6, x1, 0       # rd=t1, rs1=ra
jalr_t1_ra:

# ============================================================
# U-type RAW hazard — lui/auipc cp_gpr_hazard=RAW_HAZARD
# RAW: next instruction reads from lui/auipc's rd.
# ============================================================

    lui   x5, 0x12345
    add   x6, x5, x7          # RAW: rs1=x5 == prev.rd=x5

    auipc x8, 0x12345
    add   x9, x8, x7          # RAW: rs1=x8 == prev.rd=x8

# ============================================================
# jal holes
# ============================================================

    # jal_cg.cp_rd=ZERO — j pseudo-instruction
    j 1f
1:

    # jal_cg.cp_rd=SP
    jal x2, 2f
2:

    # jal_cg.cp_rd_align and jal_cg.cp_imm_align:
    # These require compressed (C) extension or misaligned placement.
    # Skipped: RV32I without C extension cannot produce 2-mod-4 addresses.
    # → 2 unreachable holes on pure RV32I.

    # rv32i_misc_cg.cp_misc=ECALL:
    # Set up minimal trap handler, then ecall.
    la   x5, _trap_handler
    csrw mtvec, x5

    # Store cp_rs2=ZERO via base=x0 — must precede ECALL because riscv-dv's
    # spike_log_to_trace_csv.py stops parsing at the first ECALL, so any patch
    # placed after it is invisible to the cover model. The trap handler
    # installed above absorbs the resulting store_access_fault on address 0.
    sb   x3, 0(x0)
    sh   x3, 0(x0)
    sw   x3, 0(x0)

    ecall
    j    _after_ecall
    .balign 4
_trap_handler:
    # Advance mepc past ecall (4 bytes) and return
    csrr x5, mepc
    addi x5, x5, 4
    csrw mepc, x5
    mret
_after_ecall:

# ============================================================
# VCS Coverage (47 covergroups, 64 baseline holes):
#
# Fillable holes addressed (Cells I+II in the paper taxonomy):
#   cp_rd=SP:                 36 holes (all instr types incl load/store/csr/jalr)
#   jal cp_rd=ZERO:            1 hole  (`j` pseudo)
#   sb/sh/sw cp_rs2=ZERO:      3 holes (base=x0 stores, trap-recovered)
#   jalr cp_rd:                6 holes (SP, S3, S4, S10, S11 + cross ra/t1)
#   ECALL:                     1 hole  (with trap handler)
#                             ---
#   Total fillable:           47 holes
#
# Cell-III holes (closable but require value-relation reasoning):
#   andi/ori/xori OPPOSITE     3 holes (rs1[31:0] ⊕ imm = 0xFFFFFFFF)
#   xori IDENTICAL             1 hole  (rs1 == imm)
#   → Handwrite leaves these to RVProbe's SMT solver.
#
# Architecture- or framework-unreachable (Cell IV residual):
#   RAW hazard on no-rs1 ops:  5 holes (lui, auipc, csrrwi/si/ci)
#   opcode_cg unused slots:    4 holes (atomic/FP/vector opcodes outside RV32I)
#   alignment[1]:              3 holes (mepc + jal rd/imm — needs C extension)
#   jalr alignment cross:      1 hole  (specific cp_imm_align/rs1_align cross)
#   csrrs cp_rs1=ZERO:         1 hole  (`csrr` alias drops back to csrrs in cov.py)
#                             ---
#   Total unreachable:        14 holes
# ============================================================

    .data
    .balign 16
_scratch_buf:
    .fill 64, 1, 0
