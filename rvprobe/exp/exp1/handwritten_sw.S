# Hand-written coverage test for SW instruction (Store)
#
# Coverage goals (same as RVProbe store(35, isSw())):
#   - rs1 covers x1..x31  (31 bins)
#   - rs2 covers x1..x31  (31 bins)
#   - At least one adjacent pair with NoHazard
#
# Note: stores have NO rd field, so:
#   - No RAW (no destination to read after)
#   - No WAR in the traditional sense (no rd to write after reading)
#   - No WAW (no destination)
#   - Only NoHazard is meaningful
#
# This is simpler than R-type but still requires covering 31×2 register bins.
# The main difficulty: sw needs a valid base address in rs1, or the test
# will fault. Hand-written tests must set up memory regions first.
#
# IMPORTANT PRACTICAL ISSUE:
# Unlike RVProbe which generates pure constraint solutions, hand-written
# store tests need actual memory setup. Simply writing "sw x2, 0(x8)"
# will segfault unless x8 holds a valid address. This means:
#   1. Setup code to initialize base registers (adds complexity)
#   2. OR accept that the test is purely for coverage collection via
#      tracing (not execution) — but this limits verification utility.

    .section ".text"
    .globl _start
_start:

# Assume: all x1..x31 pre-loaded with valid writable addresses
# (In practice this requires ~31 setup instructions not counted here)

# Phase 1: Cover rs1=x1..x31 and rs2=x1..x31
# Strategy: rs1=x(i+1), rs2 rotated via (i*7) mod 31 + 1

# i=0:  rs1=x1,  rs2=x8
    sw x8,  0(x1)
# i=1:  rs1=x2,  rs2=x15
    sw x15, 0(x2)
# i=2:  rs1=x3,  rs2=x22
    sw x22, 0(x3)
# i=3:  rs1=x4,  rs2=x29
    sw x29, 0(x4)
# i=4:  rs1=x5,  rs2=x5
    sw x5,  0(x5)
# i=5:  rs1=x6,  rs2=x12
    sw x12, 0(x6)
# i=6:  rs1=x7,  rs2=x19
    sw x19, 0(x7)
# i=7:  rs1=x8,  rs2=x26
    sw x26, 0(x8)
# i=8:  rs1=x9,  rs2=x2
    sw x2,  0(x9)
# i=9:  rs1=x10, rs2=x9
    sw x9,  0(x10)
# i=10: rs1=x11, rs2=x16
    sw x16, 0(x11)
# i=11: rs1=x12, rs2=x23
    sw x23, 0(x12)
# i=12: rs1=x13, rs2=x30
    sw x30, 0(x13)
# i=13: rs1=x14, rs2=x6
    sw x6,  0(x14)
# i=14: rs1=x15, rs2=x13
    sw x13, 0(x15)
# i=15: rs1=x16, rs2=x20
    sw x20, 0(x16)
# i=16: rs1=x17, rs2=x27
    sw x27, 0(x17)
# i=17: rs1=x18, rs2=x3
    sw x3,  0(x18)
# i=18: rs1=x19, rs2=x10
    sw x10, 0(x19)
# i=19: rs1=x20, rs2=x17
    sw x17, 0(x20)
# i=20: rs1=x21, rs2=x24
    sw x24, 0(x21)
# i=21: rs1=x22, rs2=x31
    sw x31, 0(x22)
# i=22: rs1=x23, rs2=x7
    sw x7,  0(x23)
# i=23: rs1=x24, rs2=x14
    sw x14, 0(x24)
# i=24: rs1=x25, rs2=x21
    sw x21, 0(x25)
# i=25: rs1=x26, rs2=x28
    sw x28, 0(x26)
# i=26: rs1=x27, rs2=x4
    sw x4,  0(x27)
# i=27: rs1=x28, rs2=x11
    sw x11, 0(x28)
# i=28: rs1=x29, rs2=x18
    sw x18, 0(x29)
# i=29: rs1=x30, rs2=x25
    sw x25, 0(x30)
# i=30: rs1=x31, rs2=x1
    sw x1,  0(x31)

# Phase 2: Fill remaining bins + NoHazard
# Need rs2 bins: check which are missing...
# rs2 so far: 8,15,22,29,5,12,19,26,2,9,16,23,30,6,13,20,27,3,10,17,24,31,7,14,21,28,4,11,18,25,1
# That's all 31. ✓
# Just need 4 more for N=35 + NoHazard pair

# Inst 32-35: padding + explicit NoHazard
    sw x1,  0(x2)
    sw x3,  0(x4)
    sw x5,  0(x6)
    sw x7,  0(x8)
# NoHazard between inst34→35: stores have no rd,
# so there is trivially no hazard between any two stores.
# Wait — is this actually what RVProbe's coverNoHazard() checks for stores?
# Looking at the code: coverNoHazard checks rd/rs1/rs2 overlap.
# For stores (no rd), the only check is rs1/rs2 overlap... but stores
# don't write to registers, so WAR/WAW don't apply.
# Actually the CoverApi checks e.hasRd — for stores hasRd=false,
# so most conditions are skipped. NoHazard is trivially satisfied.
#
# This reveals a subtlety: the coverage model for stores is simpler,
# but understanding WHICH hazards apply requires reading the coverage
# library code. RVProbe handles this automatically via hasRd()/hasRs1()/hasRs2().
