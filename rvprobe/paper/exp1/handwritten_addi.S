# Hand-written coverage test for ADDI instruction (I-type ALU)
#
# Coverage goals (same as RVProbe iTypeAlu(35, isAddi())):
#   - rd  covers x1..x31  (31 bins)
#   - rs1 covers x1..x31  (31 bins)
#   - imm12 hits boundary values: -2048, -1, 0, 1, 2047
#   - At least one adjacent pair with RAW hazard
#   - At least one adjacent pair with WAR hazard (pure, no RAW)
#   - At least one adjacent pair with WAW hazard
#   - At least one adjacent pair with no hazard
#
# Additional difficulty vs R-type:
#   - Must also cover 5 immediate boundary bins across 35 instructions
#   - Immediate values affect sign-extension behavior — wrong value
#     may trigger unexpected architectural state on real hardware

    .section ".text"
    .globl _start
_start:

# Phase 1: Cover rd=x1..x31 and rs1=x1..x31, with imm12 boundary values
# Strategy: rd=x(i+1), rs1 rotated, imm12 cycles through boundaries
# Boundary cycle: -2048, -1, 0, 1, 2047 (5 values, repeating)

# i=0:  rd=x1,  rs1=x8,  imm=-2048
    addi x1,  x8,  -2048
# i=1:  rd=x2,  rs1=x15, imm=-1
    addi x2,  x15, -1
# i=2:  rd=x3,  rs1=x22, imm=0
    addi x3,  x22, 0
# i=3:  rd=x4,  rs1=x29, imm=1
    addi x4,  x29, 1
# i=4:  rd=x5,  rs1=x5,  imm=2047
    addi x5,  x5,  2047
# i=5:  rd=x6,  rs1=x12, imm=-2048
    addi x6,  x12, -2048
# i=6:  rd=x7,  rs1=x19, imm=-1
    addi x7,  x19, -1
# i=7:  rd=x8,  rs1=x26, imm=0
    addi x8,  x26, 0
# i=8:  rd=x9,  rs1=x2,  imm=1
    addi x9,  x2,  1
# i=9:  rd=x10, rs1=x9,  imm=2047
    addi x10, x9,  2047
# i=10: rd=x11, rs1=x16, imm=-2048
    addi x11, x16, -2048
# i=11: rd=x12, rs1=x23, imm=-1
    addi x12, x23, -1
# i=12: rd=x13, rs1=x30, imm=0
    addi x13, x30, 0
# i=13: rd=x14, rs1=x6,  imm=1
    addi x14, x6,  1
# i=14: rd=x15, rs1=x13, imm=2047
    addi x15, x13, 2047
# i=15: rd=x16, rs1=x20, imm=-2048
    addi x16, x20, -2048
# i=16: rd=x17, rs1=x27, imm=-1
    addi x17, x27, -1
# i=17: rd=x18, rs1=x3,  imm=0
    addi x18, x3,  0
# i=18: rd=x19, rs1=x10, imm=1
    addi x19, x10, 1
# i=19: rd=x20, rs1=x17, imm=2047
    addi x20, x17, 2047
# i=20: rd=x21, rs1=x24, imm=-2048
    addi x21, x24, -2048
# i=21: rd=x22, rs1=x31, imm=-1
    addi x22, x31, -1
# i=22: rd=x23, rs1=x7,  imm=0
    addi x23, x7,  0
# i=23: rd=x24, rs1=x14, imm=1
    addi x24, x14, 1
# i=24: rd=x25, rs1=x21, imm=2047
    addi x25, x21, 2047
# i=25: rd=x26, rs1=x28, imm=-2048
    addi x26, x28, -2048
# i=26: rd=x27, rs1=x4,  imm=-1
    addi x27, x4,  -1
# i=27: rd=x28, rs1=x11, imm=0
    addi x28, x11, 0
# i=28: rd=x29, rs1=x18, imm=1
    addi x29, x18, 1
# i=29: rd=x30, rs1=x25, imm=2047
    addi x30, x25, 2047
# i=30: rd=x31, rs1=x1,  imm=-2048
    addi x31, x1,  -2048

# Phase 2: Hazard patches (4 extra instructions)
# Inst 32: WAW with inst 31 (rd=x31 == rd=x31)
    addi x31, x2,  0
# Inst 33: RAW with inst 32 (rs1=x31 == prev.rd=x31)
    addi x1,  x31, 1
# Inst 34: WAR with inst 33 — prev.rs1=x31, so rd=x31 would be WAR
#          but also WAW (prev.rd=x1, our rd != x1). Use: prev has rs1=x31,
#          so rd=x31 gives WAR. Ensure no RAW: our rs1 != prev.rd=x1.
    addi x31, x5,  -1
# Inst 35: NoHazard with inst 34 (rd=x10, rs1=x20 vs prev rd=x31,rs1=x5)
    addi x10, x20, 2047

# --- Verification required ---
# Must manually verify:
#   rd:   x1..x31 all covered? (x31 appears 3 times in phase 2, but all 31 bins hit in phase 1)
#   rs1:  x1..x31 all covered? Need to enumerate...
#   imm:  -2048, -1, 0, 1, 2047 all appear? Yes by construction (cycling).
#   RAW:  inst32→33 ✓
#   WAR:  inst33→34? prev.rs1=x31, our rd=x31 → WAR. prev.rd=x1, our rs1=x5 → no RAW. ✓
#   WAW:  inst31→32 ✓
#   NoHazard: inst34→35 ✓
#
# ERROR FOUND DURING REVIEW:
#   inst33→34: WAR check — prev is inst33: rd=x1, rs1=x31.
#   inst34: rd=x31. Is rs1_prev(x31) == rd_curr(x31)? Yes → WAR.
#   But is rd_prev(x1) == rs1_curr(x5)? No. So no RAW. ✓
#   But wait: inst33→34 also has WAW? prev.rd=x1, curr.rd=x31. No WAW. ✓
#   Actually: is inst34's rd=x31 == inst33's rd=x1? No. So pure WAR. ✓
#
# This verification process took ~20 minutes for ONE instruction type.
