    .section ".text"
    .globl _start
_start:
    # Set up trap handler to skip faulting instructions
    la   t0, _skip_trap_handler
    csrw mtvec, t0
    j    _after_trap_handler
    .balign 4
_skip_trap_handler:
    csrr t0, mepc
    addi t0, t0, 4
    csrw mepc, t0
    mret
_after_trap_handler:


slli x16, x9, 0
    slli x4, x17, 0
    slli x17, x18, 0
    slli x27, x21, 0
    slli x22, x23, 0
    slli x10, x6, 0
    slli x22, x20, 0
    slli x1, x26, 0
    slli x5, x31, 0
    slli x28, x25, 0
    slli x12, x30, 0
    slli x26, x15, 0
    slli x24, x31, 0
    slli x2, x28, 0
    slli x29, x3, 0
    slli x3, x11, 0
    slli x13, x2, 0
    slli x6, x22, 0
    slli x9, x16, 0
    slli x20, x13, 0
    slli x15, x1, 0
    slli x31, x19, 0
    slli x30, x8, 0
    slli x30, x30, 0
    slli x7, x31, 0
    slli x18, x29, 0
    slli x19, x4, 0
    slli x31, x5, 0
    slli x25, x7, 0
    slli x14, x26, 0
    slli x3, x27, 0
    slli x21, x10, 0
    slli x8, x14, 0
    slli x23, x12, 0
    slli x11, x24, 0

srai x16, x9, 0
    srai x4, x17, 0
    srai x17, x18, 0
    srai x27, x21, 0
    srai x22, x23, 0
    srai x10, x6, 0
    srai x22, x20, 0
    srai x1, x26, 0
    srai x5, x31, 0
    srai x28, x25, 0
    srai x12, x30, 0
    srai x26, x15, 0
    srai x24, x31, 0
    srai x2, x28, 0
    srai x29, x3, 0
    srai x3, x11, 0
    srai x13, x2, 0
    srai x6, x22, 0
    srai x9, x16, 0
    srai x20, x13, 0
    srai x15, x1, 0
    srai x31, x19, 0
    srai x30, x8, 0
    srai x30, x30, 0
    srai x7, x31, 0
    srai x18, x29, 0
    srai x19, x4, 0
    srai x31, x5, 0
    srai x25, x7, 0
    srai x14, x26, 0
    srai x3, x27, 0
    srai x21, x10, 0
    srai x8, x14, 0
    srai x23, x12, 0
    srai x11, x24, 0

srli x16, x9, 0
    srli x4, x17, 0
    srli x17, x18, 0
    srli x27, x21, 0
    srli x22, x23, 0
    srli x10, x6, 0
    srli x22, x20, 0
    srli x1, x26, 0
    srli x5, x31, 0
    srli x28, x25, 0
    srli x12, x30, 0
    srli x26, x15, 0
    srli x24, x31, 0
    srli x2, x28, 0
    srli x29, x3, 0
    srli x3, x11, 0
    srli x13, x2, 0
    srli x6, x22, 0
    srli x9, x16, 0
    srli x20, x13, 0
    srli x15, x1, 0
    srli x31, x19, 0
    srli x30, x8, 0
    srli x30, x30, 0
    srli x7, x31, 0
    srli x18, x29, 0
    srli x19, x4, 0
    srli x31, x5, 0
    srli x25, x7, 0
    srli x14, x26, 0
    srli x3, x27, 0
    srli x21, x10, 0
    srli x8, x14, 0
    srli x23, x12, 0
    srli x11, x24, 0

add x28, x3, x15
    add x16, x12, x29
    add x12, x26, x10
    add x4, x1, x14
    add x27, x7, x18
    add x14, x21, x30
    add x23, x13, x20
    add x21, x27, x29
    add x31, x25, x31
    add x30, x10, x7
    add x30, x31, x8
    add x18, x31, x31
    add x19, x8, x21
    add x7, x23, x13
    add x15, x11, x25
    add x10, x1, x4
    add x2, x29, x12
    add x1, x2, x6
    add x25, x5, x16
    add x1, x30, x9
    add x29, x15, x2
    add x8, x30, x22
    add x9, x9, x1
    add x24, x28, x24
    add x17, x22, x28
    add x22, x19, x26
    add x13, x24, x17
    add x3, x20, x5
    add x6, x6, x11
    add x5, x16, x27
    add x31, x17, x2
    add x1, x11, x30
    add x11, x18, x3
    add x26, x14, x19
    add x20, x4, x23

addi x31, x31, 2047
    addi x31, x30, 2047
    addi x27, x23, 2047
    addi x26, x14, 2047
    addi x25, x29, 2047
    addi x30, x16, 2047
    addi x29, x4, 2047
    addi x19, x18, 2047
    addi x21, x19, 2047
    addi x23, x27, 2047
    addi x28, x11, 2047
    addi x22, x21, 2047
    addi x18, x20, 2047
    addi x24, x8, 2047
    addi x17, x13, 2047
    addi x5, x23, 2047
    addi x14, x12, 2047
    addi x13, x3, 2047
    addi x12, x2, 2047
    addi x11, x9, 2047
    addi x10, x7, 2047
    addi x8, x10, 2047
    addi x16, x5, 2047
    addi x20, x15, 2047
    addi x7, x24, 2047
    addi x25, x6, 0
    addi x4, x1, 2047
    addi x2, x26, 2047
    addi x31, x1, 2047
    addi x26, x22, 2047
    addi x15, x25, 2047
    addi x9, x2, 2047
    addi x3, x28, -1
    addi x1, x2, -2048
    addi x6, x17, 1

and x16, x25, x26
    and x23, x2, x16
    and x13, x14, x19
    and x24, x29, x27
    and x18, x5, x25
    and x30, x10, x14
    and x28, x30, x18
    and x19, x8, x1
    and x7, x22, x24
    and x21, x1, x9
    and x8, x23, x7
    and x2, x17, x15
    and x12, x16, x11
    and x20, x4, x10
    and x1, x12, x21
    and x4, x7, x20
    and x10, x27, x3
    and x26, x11, x13
    and x3, x27, x17
    and x21, x15, x29
    and x15, x3, x11
    and x9, x6, x23
    and x9, x13, x9
    and x17, x18, x22
    and x22, x31, x22
    and x31, x21, x12
    and x2, x20, x31
    and x14, x31, x5
    and x29, x19, x11
    and x11, x25, x8
    and x25, x26, x28
    and x23, x28, x6
    and x6, x24, x2
    and x5, x30, x30
    and x27, x9, x4
    li x5, 0x2a
    and x6, x5, x5
    li x1, 0x55555555
    li x4, 0xffffffffaaaaaaaa
    and x8, x1, x4
    li x7, 0x0
    li x3, 0xff
    and x2, x7, x3

andi x30, x30, 2047
    andi x30, x30, 2047
    andi x29, x26, 2047
    andi x23, x13, 2047
    andi x20, x24, 2047
    andi x26, x6, 2047
    andi x1, x19, 2047
    andi x31, x14, 2047
    andi x30, x17, 2047
    andi x22, x9, 2047
    andi x18, x25, 2047
    andi x28, x28, 2047
    andi x27, x22, 2047
    andi x25, x23, 2047
    andi x24, x4, 2047
    andi x21, x20, 2047
    andi x20, x18, 2047
    andi x19, x15, 2047
    andi x18, x21, 2047
    andi x17, x16, 2047
    andi x16, x3, 2047
    andi x15, x29, 2047
    andi x14, x12, -1
    andi x13, x11, 2047
    andi x12, x10, 2047
    andi x11, x29, 2047
    andi x10, x8, 2047
    andi x9, x7, 2047
    andi x8, x29, 2047
    andi x7, x5, 2047
    andi x6, x27, 2047
    andi x5, x1, 2047
    andi x4, x2, 1
    andi x3, x1, -2048
    andi x2, x31, 0
    li x4, 0x2a
    andi x1, x4, 42
    li x5, 0x555
    andi x2, x5, -1366
    li x6, 0x0
    andi x3, x6, 255

auipc x27, 0
    auipc x5, 524287
    auipc x26, 0
    auipc x16, 2
    auipc x31, 0
    auipc x12, 1048575
    auipc x29, 2
    auipc x15, 524287
    auipc x18, 524287
    auipc x30, 0
    auipc x10, 524288
    auipc x14, 524287
    auipc x24, 0
    auipc x21, 524288
    auipc x21, 524288
    auipc x2, 524287
    auipc x13, 0
    auipc x20, 1
    auipc x3, 1048575
    auipc x6, 2
    auipc x28, 524287
    auipc x19, 524287
    auipc x25, 0
    auipc x22, 1048575
    auipc x11, 0
    auipc x9, 524287
    auipc x1, 524287
    auipc x4, 2
    auipc x7, 524288
    auipc x17, 524287
    auipc x23, 524288
    auipc x21, 524288
    auipc x8, 524288
    auipc x13, 524287
    auipc x28, 0

addi x1, x0, -2
    addi x2, x0, -2
    addi x3, x0, -2
    addi x4, x0, -2
    addi x5, x0, -2
    beq x31, x13, . + 4
    beq x22, x8, . + 4
    beq x26, x5, . + 4
    beq x12, x7, . + 4
    beq x11, x1, . + 4
    beq x8, x4, . + 4
    beq x3, x8, . + 4
    beq x28, x6, . + 4
    beq x29, x1, . + 4
    beq x10, x9, . + 4
    beq x7, x10, . + 4
    beq x21, x3, . + 4
    beq x19, x2, . + 4
    beq x30, x1, . + 4
    beq x14, x4, . + 4
    beq x17, x11, . + 4
    beq x20, x12, . + 4
    beq x15, x14, . + 4
    beq x6, x15, . + 4
    beq x1, x16, . + 4
    beq x1, x17, . + 4
    beq x18, x18, . + 4
    beq x13, x20, . + 4
    beq x5, x19, . + 4
    beq x4, x21, . + 4
    beq x8, x22, . + 4
    beq x23, x23, . + 4
    beq x7, x24, . + 4
    beq x16, x25, . + 4
    beq x9, x26, . + 4
    beq x24, x27, . + 4
    beq x31, x28, . + 4
    beq x25, x29, . + 4
    beq x27, x30, . + 4
    beq x2, x31, . + 4

addi x1, x0, -2
    addi x2, x0, -2
    addi x3, x0, -2
    addi x4, x0, -2
    addi x5, x0, -2
    bge x31, x13, . + 4
    bge x22, x8, . + 4
    bge x26, x5, . + 4
    bge x12, x7, . + 4
    bge x11, x1, . + 4
    bge x8, x4, . + 4
    bge x3, x8, . + 4
    bge x28, x6, . + 4
    bge x29, x1, . + 4
    bge x10, x9, . + 4
    bge x7, x10, . + 4
    bge x21, x3, . + 4
    bge x19, x2, . + 4
    bge x30, x1, . + 4
    bge x14, x4, . + 4
    bge x17, x11, . + 4
    bge x20, x12, . + 4
    bge x15, x14, . + 4
    bge x6, x15, . + 4
    bge x1, x16, . + 4
    bge x1, x17, . + 4
    bge x18, x18, . + 4
    bge x13, x20, . + 4
    bge x5, x19, . + 4
    bge x4, x21, . + 4
    bge x8, x22, . + 4
    bge x23, x23, . + 4
    bge x7, x24, . + 4
    bge x16, x25, . + 4
    bge x9, x26, . + 4
    bge x24, x27, . + 4
    bge x31, x28, . + 4
    bge x25, x29, . + 4
    bge x27, x30, . + 4
    bge x2, x31, . + 4

addi x1, x0, -2
    addi x2, x0, -2
    addi x3, x0, -2
    addi x4, x0, -2
    addi x5, x0, -2
    bgeu x31, x13, . + 4
    bgeu x22, x8, . + 4
    bgeu x26, x5, . + 4
    bgeu x12, x7, . + 4
    bgeu x11, x1, . + 4
    bgeu x8, x4, . + 4
    bgeu x3, x8, . + 4
    bgeu x28, x6, . + 4
    bgeu x29, x1, . + 4
    bgeu x10, x9, . + 4
    bgeu x7, x10, . + 4
    bgeu x21, x3, . + 4
    bgeu x19, x2, . + 4
    bgeu x30, x1, . + 4
    bgeu x14, x4, . + 4
    bgeu x17, x11, . + 4
    bgeu x20, x12, . + 4
    bgeu x15, x14, . + 4
    bgeu x6, x15, . + 4
    bgeu x1, x16, . + 4
    bgeu x1, x17, . + 4
    bgeu x18, x18, . + 4
    bgeu x13, x20, . + 4
    bgeu x5, x19, . + 4
    bgeu x4, x21, . + 4
    bgeu x8, x22, . + 4
    bgeu x23, x23, . + 4
    bgeu x7, x24, . + 4
    bgeu x16, x25, . + 4
    bgeu x9, x26, . + 4
    bgeu x24, x27, . + 4
    bgeu x31, x28, . + 4
    bgeu x25, x29, . + 4
    bgeu x27, x30, . + 4
    bgeu x2, x31, . + 4

addi x1, x0, -2
    addi x2, x0, -2
    addi x3, x0, -2
    addi x4, x0, -2
    addi x5, x0, -2
    blt x31, x13, . + 4
    blt x22, x8, . + 4
    blt x26, x5, . + 4
    blt x12, x7, . + 4
    blt x11, x1, . + 4
    blt x8, x4, . + 4
    blt x3, x8, . + 4
    blt x28, x6, . + 4
    blt x29, x1, . + 4
    blt x10, x9, . + 4
    blt x7, x10, . + 4
    blt x21, x3, . + 4
    blt x19, x2, . + 4
    blt x30, x1, . + 4
    blt x14, x4, . + 4
    blt x17, x11, . + 4
    blt x20, x12, . + 4
    blt x15, x14, . + 4
    blt x6, x15, . + 4
    blt x1, x16, . + 4
    blt x1, x17, . + 4
    blt x18, x18, . + 4
    blt x13, x20, . + 4
    blt x5, x19, . + 4
    blt x4, x21, . + 4
    blt x8, x22, . + 4
    blt x23, x23, . + 4
    blt x7, x24, . + 4
    blt x16, x25, . + 4
    blt x9, x26, . + 4
    blt x24, x27, . + 4
    blt x31, x28, . + 4
    blt x25, x29, . + 4
    blt x27, x30, . + 4
    blt x2, x31, . + 4

addi x1, x0, -2
    addi x2, x0, -2
    addi x3, x0, -2
    addi x4, x0, -2
    addi x5, x0, -2
    bltu x31, x13, . + 4
    bltu x22, x8, . + 4
    bltu x26, x5, . + 4
    bltu x12, x7, . + 4
    bltu x11, x1, . + 4
    bltu x8, x4, . + 4
    bltu x3, x8, . + 4
    bltu x28, x6, . + 4
    bltu x29, x1, . + 4
    bltu x10, x9, . + 4
    bltu x7, x10, . + 4
    bltu x21, x3, . + 4
    bltu x19, x2, . + 4
    bltu x30, x1, . + 4
    bltu x14, x4, . + 4
    bltu x17, x11, . + 4
    bltu x20, x12, . + 4
    bltu x15, x14, . + 4
    bltu x6, x15, . + 4
    bltu x1, x16, . + 4
    bltu x1, x17, . + 4
    bltu x18, x18, . + 4
    bltu x13, x20, . + 4
    bltu x5, x19, . + 4
    bltu x4, x21, . + 4
    bltu x8, x22, . + 4
    bltu x23, x23, . + 4
    bltu x7, x24, . + 4
    bltu x16, x25, . + 4
    bltu x9, x26, . + 4
    bltu x24, x27, . + 4
    bltu x31, x28, . + 4
    bltu x25, x29, . + 4
    bltu x27, x30, . + 4
    bltu x2, x31, . + 4

addi x1, x0, -2
    addi x2, x0, -2
    addi x3, x0, -2
    addi x4, x0, -2
    addi x5, x0, -2
    bne x31, x13, . + 4
    bne x22, x8, . + 4
    bne x26, x5, . + 4
    bne x12, x7, . + 4
    bne x11, x1, . + 4
    bne x8, x4, . + 4
    bne x3, x8, . + 4
    bne x28, x6, . + 4
    bne x29, x1, . + 4
    bne x10, x9, . + 4
    bne x7, x10, . + 4
    bne x21, x3, . + 4
    bne x19, x2, . + 4
    bne x30, x1, . + 4
    bne x14, x4, . + 4
    bne x17, x11, . + 4
    bne x20, x12, . + 4
    bne x15, x14, . + 4
    bne x6, x15, . + 4
    bne x1, x16, . + 4
    bne x1, x17, . + 4
    bne x18, x18, . + 4
    bne x13, x20, . + 4
    bne x5, x19, . + 4
    bne x4, x21, . + 4
    bne x8, x22, . + 4
    bne x23, x23, . + 4
    bne x7, x24, . + 4
    bne x16, x25, . + 4
    bne x9, x26, . + 4
    bne x24, x27, . + 4
    bne x31, x28, . + 4
    bne x25, x29, . + 4
    bne x27, x30, . + 4
    bne x2, x31, . + 4

lui x27, 0
    lui x5, 524287
    lui x26, 0
    lui x16, 2
    lui x31, 0
    lui x12, 1048575
    lui x29, 2
    lui x15, 524287
    lui x18, 524287
    lui x30, 0
    lui x10, 524288
    lui x14, 524287
    lui x24, 0
    lui x21, 524288
    lui x21, 524288
    lui x2, 524287
    lui x13, 0
    lui x20, 1
    lui x3, 1048575
    lui x6, 2
    lui x28, 524287
    lui x19, 524287
    lui x25, 0
    lui x22, 1048575
    lui x11, 0
    lui x9, 524287
    lui x1, 524287
    lui x4, 2
    lui x7, 524288
    lui x17, 524287
    lui x23, 524288
    lui x21, 524288
    lui x8, 524288
    lui x13, 524287
    lui x28, 0

or x16, x25, x26
    or x23, x2, x16
    or x13, x14, x19
    or x24, x29, x27
    or x18, x5, x25
    or x30, x10, x14
    or x28, x30, x18
    or x19, x8, x1
    or x7, x22, x24
    or x21, x1, x9
    or x8, x23, x7
    or x2, x17, x15
    or x12, x16, x11
    or x20, x4, x10
    or x1, x12, x21
    or x4, x7, x20
    or x10, x27, x3
    or x26, x11, x13
    or x3, x27, x17
    or x21, x15, x29
    or x15, x3, x11
    or x9, x6, x23
    or x9, x13, x9
    or x17, x18, x22
    or x22, x31, x22
    or x31, x21, x12
    or x2, x20, x31
    or x14, x31, x5
    or x29, x19, x11
    or x11, x25, x8
    or x25, x26, x28
    or x23, x28, x6
    or x6, x24, x2
    or x5, x30, x30
    or x27, x9, x4
    li x5, 0x2a
    or x6, x5, x5
    li x1, 0x55555555
    li x4, 0xffffffffaaaaaaaa
    or x8, x1, x4
    li x7, 0x0
    li x3, 0xff
    or x2, x7, x3

ori x30, x30, 2047
    ori x30, x30, 2047
    ori x29, x26, 2047
    ori x23, x13, 2047
    ori x20, x24, 2047
    ori x26, x6, 2047
    ori x1, x19, 2047
    ori x31, x14, 2047
    ori x30, x17, 2047
    ori x22, x9, 2047
    ori x18, x25, 2047
    ori x28, x28, 2047
    ori x27, x22, 2047
    ori x25, x23, 2047
    ori x24, x4, 2047
    ori x21, x20, 2047
    ori x20, x18, 2047
    ori x19, x15, 2047
    ori x18, x21, 2047
    ori x17, x16, 2047
    ori x16, x3, 2047
    ori x15, x29, 2047
    ori x14, x12, -1
    ori x13, x11, 2047
    ori x12, x10, 2047
    ori x11, x29, 2047
    ori x10, x8, 2047
    ori x9, x7, 2047
    ori x8, x29, 2047
    ori x7, x5, 2047
    ori x6, x27, 2047
    ori x5, x1, 2047
    ori x4, x2, 1
    ori x3, x1, -2048
    ori x2, x31, 0
    li x4, 0x2a
    ori x1, x4, 42
    li x5, 0x555
    ori x2, x5, -1366
    li x6, 0x0
    ori x3, x6, 255

sll x11, x26, x4
    sll x2, x24, x18
    sll x15, x18, x19
    sll x22, x22, x2
    sll x14, x28, x30
    sll x9, x9, x21
    sll x1, x11, x11
    sll x11, x22, x3
    sll x6, x14, x30
    sll x4, x4, x27
    sll x23, x17, x22
    sll x16, x20, x24
    sll x10, x31, x20
    sll x20, x7, x7
    sll x8, x3, x6
    sll x12, x10, x30
    sll x1, x15, x1
    sll x1, x2, x14
    sll x7, x30, x15
    sll x13, x8, x28
    sll x25, x21, x17
    sll x18, x13, x30
    sll x3, x16, x25
    sll x19, x6, x5
    sll x27, x19, x23
    sll x26, x27, x29
    sll x5, x27, x28
    sll x30, x25, x8
    sll x31, x23, x12
    sll x22, x5, x13
    sll x24, x11, x10
    sll x17, x27, x26
    sll x21, x29, x16
    sll x28, x12, x31
    sll x29, x1, x9

slt x11, x26, x4
    slt x2, x24, x18
    slt x15, x18, x19
    slt x22, x22, x2
    slt x14, x28, x30
    slt x9, x9, x21
    slt x1, x11, x11
    slt x11, x22, x3
    slt x6, x14, x30
    slt x4, x4, x27
    slt x23, x17, x22
    slt x16, x20, x24
    slt x10, x31, x20
    slt x20, x7, x7
    slt x8, x3, x6
    slt x12, x10, x30
    slt x1, x15, x1
    slt x1, x2, x14
    slt x7, x30, x15
    slt x13, x8, x28
    slt x25, x21, x17
    slt x18, x13, x30
    slt x3, x16, x25
    slt x19, x6, x5
    slt x27, x19, x23
    slt x26, x27, x29
    slt x5, x27, x28
    slt x30, x25, x8
    slt x31, x23, x12
    slt x22, x5, x13
    slt x24, x11, x10
    slt x17, x27, x26
    slt x21, x29, x16
    slt x28, x12, x31
    slt x29, x1, x9

slti x31, x31, 2047
    slti x31, x30, 2047
    slti x27, x23, 2047
    slti x26, x14, 2047
    slti x25, x29, 2047
    slti x30, x16, 2047
    slti x29, x4, 2047
    slti x19, x18, 2047
    slti x21, x19, 2047
    slti x23, x27, 2047
    slti x28, x11, 2047
    slti x22, x21, 2047
    slti x18, x20, 2047
    slti x24, x8, 2047
    slti x17, x13, 2047
    slti x5, x23, 2047
    slti x14, x12, 2047
    slti x13, x3, 2047
    slti x12, x2, 2047
    slti x11, x9, 2047
    slti x10, x7, 2047
    slti x8, x10, 2047
    slti x16, x5, 2047
    slti x20, x15, 2047
    slti x7, x24, 2047
    slti x25, x6, 0
    slti x4, x1, 2047
    slti x2, x26, 2047
    slti x31, x1, 2047
    slti x26, x22, 2047
    slti x15, x25, 2047
    slti x9, x2, 2047
    slti x3, x28, -1
    slti x1, x2, -2048
    slti x6, x17, 1

sltiu x31, x31, 2047
    sltiu x31, x30, 2047
    sltiu x27, x23, 2047
    sltiu x26, x14, 2047
    sltiu x25, x29, 2047
    sltiu x30, x16, 2047
    sltiu x29, x4, 2047
    sltiu x19, x18, 2047
    sltiu x21, x19, 2047
    sltiu x23, x27, 2047
    sltiu x28, x11, 2047
    sltiu x22, x21, 2047
    sltiu x18, x20, 2047
    sltiu x24, x8, 2047
    sltiu x17, x13, 2047
    sltiu x5, x23, 2047
    sltiu x14, x12, 2047
    sltiu x13, x3, 2047
    sltiu x12, x2, 2047
    sltiu x11, x9, 2047
    sltiu x10, x7, 2047
    sltiu x8, x10, 2047
    sltiu x16, x5, 2047
    sltiu x20, x15, 2047
    sltiu x7, x24, 2047
    sltiu x25, x6, 0
    sltiu x4, x1, 2047
    sltiu x2, x26, 2047
    sltiu x31, x1, 2047
    sltiu x26, x22, 2047
    sltiu x15, x25, 2047
    sltiu x9, x2, 2047
    sltiu x3, x28, -1
    sltiu x1, x2, -2048
    sltiu x6, x17, 1

sltu x11, x26, x4
    sltu x2, x24, x18
    sltu x15, x18, x19
    sltu x22, x22, x2
    sltu x14, x28, x30
    sltu x9, x9, x21
    sltu x1, x11, x11
    sltu x11, x22, x3
    sltu x6, x14, x30
    sltu x4, x4, x27
    sltu x23, x17, x22
    sltu x16, x20, x24
    sltu x10, x31, x20
    sltu x20, x7, x7
    sltu x8, x3, x6
    sltu x12, x10, x30
    sltu x1, x15, x1
    sltu x1, x2, x14
    sltu x7, x30, x15
    sltu x13, x8, x28
    sltu x25, x21, x17
    sltu x18, x13, x30
    sltu x3, x16, x25
    sltu x19, x6, x5
    sltu x27, x19, x23
    sltu x26, x27, x29
    sltu x5, x27, x28
    sltu x30, x25, x8
    sltu x31, x23, x12
    sltu x22, x5, x13
    sltu x24, x11, x10
    sltu x17, x27, x26
    sltu x21, x29, x16
    sltu x28, x12, x31
    sltu x29, x1, x9

sra x11, x26, x4
    sra x2, x24, x18
    sra x15, x18, x19
    sra x22, x22, x2
    sra x14, x28, x30
    sra x9, x9, x21
    sra x1, x11, x11
    sra x11, x22, x3
    sra x6, x14, x30
    sra x4, x4, x27
    sra x23, x17, x22
    sra x16, x20, x24
    sra x10, x31, x20
    sra x20, x7, x7
    sra x8, x3, x6
    sra x12, x10, x30
    sra x1, x15, x1
    sra x1, x2, x14
    sra x7, x30, x15
    sra x13, x8, x28
    sra x25, x21, x17
    sra x18, x13, x30
    sra x3, x16, x25
    sra x19, x6, x5
    sra x27, x19, x23
    sra x26, x27, x29
    sra x5, x27, x28
    sra x30, x25, x8
    sra x31, x23, x12
    sra x22, x5, x13
    sra x24, x11, x10
    sra x17, x27, x26
    sra x21, x29, x16
    sra x28, x12, x31
    sra x29, x1, x9

srl x11, x26, x4
    srl x2, x24, x18
    srl x15, x18, x19
    srl x22, x22, x2
    srl x14, x28, x30
    srl x9, x9, x21
    srl x1, x11, x11
    srl x11, x22, x3
    srl x6, x14, x30
    srl x4, x4, x27
    srl x23, x17, x22
    srl x16, x20, x24
    srl x10, x31, x20
    srl x20, x7, x7
    srl x8, x3, x6
    srl x12, x10, x30
    srl x1, x15, x1
    srl x1, x2, x14
    srl x7, x30, x15
    srl x13, x8, x28
    srl x25, x21, x17
    srl x18, x13, x30
    srl x3, x16, x25
    srl x19, x6, x5
    srl x27, x19, x23
    srl x26, x27, x29
    srl x5, x27, x28
    srl x30, x25, x8
    srl x31, x23, x12
    srl x22, x5, x13
    srl x24, x11, x10
    srl x17, x27, x26
    srl x21, x29, x16
    srl x28, x12, x31
    srl x29, x1, x9

sub x11, x26, x4
    sub x2, x24, x18
    sub x15, x18, x19
    sub x22, x22, x2
    sub x14, x28, x30
    sub x9, x9, x21
    sub x1, x11, x11
    sub x11, x22, x3
    sub x6, x14, x30
    sub x4, x4, x27
    sub x23, x17, x22
    sub x16, x20, x24
    sub x10, x31, x20
    sub x20, x7, x7
    sub x8, x3, x6
    sub x12, x10, x30
    sub x1, x15, x1
    sub x1, x2, x14
    sub x7, x30, x15
    sub x13, x8, x28
    sub x25, x21, x17
    sub x18, x13, x30
    sub x3, x16, x25
    sub x19, x6, x5
    sub x27, x19, x23
    sub x26, x27, x29
    sub x5, x27, x28
    sub x30, x25, x8
    sub x31, x23, x12
    sub x22, x5, x13
    sub x24, x11, x10
    sub x17, x27, x26
    sub x21, x29, x16
    sub x28, x12, x31
    sub x29, x1, x9

xor x16, x25, x26
    xor x23, x2, x16
    xor x13, x14, x19
    xor x24, x29, x27
    xor x18, x5, x25
    xor x30, x10, x14
    xor x28, x30, x18
    xor x19, x8, x1
    xor x7, x22, x24
    xor x21, x1, x9
    xor x8, x23, x7
    xor x2, x17, x15
    xor x12, x16, x11
    xor x20, x4, x10
    xor x1, x12, x21
    xor x4, x7, x20
    xor x10, x27, x3
    xor x26, x11, x13
    xor x3, x27, x17
    xor x21, x15, x29
    xor x15, x3, x11
    xor x9, x6, x23
    xor x9, x13, x9
    xor x17, x18, x22
    xor x22, x31, x22
    xor x31, x21, x12
    xor x2, x20, x31
    xor x14, x31, x5
    xor x29, x19, x11
    xor x11, x25, x8
    xor x25, x26, x28
    xor x23, x28, x6
    xor x6, x24, x2
    xor x5, x30, x30
    xor x27, x9, x4
    li x5, 0x2a
    xor x6, x5, x5
    li x1, 0x55555555
    li x4, 0xffffffffaaaaaaaa
    xor x8, x1, x4
    li x7, 0x0
    li x3, 0xff
    xor x2, x7, x3

xori x30, x30, 2047
    xori x30, x30, 2047
    xori x29, x26, 2047
    xori x23, x13, 2047
    xori x20, x24, 2047
    xori x26, x6, 2047
    xori x1, x19, 2047
    xori x31, x14, 2047
    xori x30, x17, 2047
    xori x22, x9, 2047
    xori x18, x25, 2047
    xori x28, x28, 2047
    xori x27, x22, 2047
    xori x25, x23, 2047
    xori x24, x4, 2047
    xori x21, x20, 2047
    xori x20, x18, 2047
    xori x19, x15, 2047
    xori x18, x21, 2047
    xori x17, x16, 2047
    xori x16, x3, 2047
    xori x15, x29, 2047
    xori x14, x12, -1
    xori x13, x11, 2047
    xori x12, x10, 2047
    xori x11, x29, 2047
    xori x10, x8, 2047
    xori x9, x7, 2047
    xori x8, x29, 2047
    xori x7, x5, 2047
    xori x6, x27, 2047
    xori x5, x1, 2047
    xori x4, x2, 1
    xori x3, x1, -2048
    xori x2, x31, 0
    li x4, 0x2a
    xori x1, x4, 42
    li x5, 0x555
    xori x2, x5, -1366
    li x6, 0x0
    xori x3, x6, 255

la x3, _scratch_buf
mv x4, x3
mv x5, x3
mv x6, x3
mv x7, x3
mv x8, x3
mv x9, x3
mv x10, x3
mv x11, x3
mv x12, x3
mv x13, x3
mv x14, x3
mv x15, x3
mv x16, x3
mv x17, x3
mv x18, x3
mv x19, x3
mv x20, x3
mv x21, x3
mv x22, x3
mv x23, x3
mv x24, x3
mv x25, x3
mv x26, x3
mv x27, x3
mv x28, x3
mv x29, x3
mv x30, x3
mv x31, x3
    lb x5, 0(x25)
    lb x4, 0(x14)
    lb x13, 0(x6)
    lb x20, 0(x8)
    lb x6, 0(x12)
    lb x8, 0(x31)
    lb x30, 0(x17)
    lb x1, 0(x31)
    lb x7, 0(x29)
    lb x24, 0(x20)
    lb x10, 0(x10)
    lb x17, 0(x11)
    lb x31, 0(x22)
    lb x15, 0(x18)
    lb x13, 0(x4)
    lb x3, 0(x16)
    lb x18, 0(x9)
    lb x9, 0(x19)
    lb x27, 0(x30)
    lb x25, 0(x27)
    lb x13, 0(x23)
    lb x12, 0(x7)
    lb x10, 0(x21)
    lb x2, 0(x24)
    lb x26, 0(x31)
    lb x23, 0(x26)
    lb x13, 0(x24)
    lb x29, 0(x13)
    lb x28, 0(x24)
    lb x16, 0(x31)
    lb x22, 0(x28)
    lb x14, 0(x5)
    lb x21, 0(x8)
    lb x11, 0(x3)
    lb x19, 0(x15)

la x3, _scratch_buf
mv x4, x3
mv x5, x3
mv x6, x3
mv x7, x3
mv x8, x3
mv x9, x3
mv x10, x3
mv x11, x3
mv x12, x3
mv x13, x3
mv x14, x3
mv x15, x3
mv x16, x3
mv x17, x3
mv x18, x3
mv x19, x3
mv x20, x3
mv x21, x3
mv x22, x3
mv x23, x3
mv x24, x3
mv x25, x3
mv x26, x3
mv x27, x3
mv x28, x3
mv x29, x3
mv x30, x3
mv x31, x3
    lbu x5, 0(x25)
    lbu x4, 0(x14)
    lbu x13, 0(x6)
    lbu x20, 0(x8)
    lbu x6, 0(x12)
    lbu x8, 0(x31)
    lbu x30, 0(x17)
    lbu x1, 0(x31)
    lbu x7, 0(x29)
    lbu x24, 0(x20)
    lbu x10, 0(x10)
    lbu x17, 0(x11)
    lbu x31, 0(x22)
    lbu x15, 0(x18)
    lbu x13, 0(x4)
    lbu x3, 0(x16)
    lbu x18, 0(x9)
    lbu x9, 0(x19)
    lbu x27, 0(x30)
    lbu x25, 0(x27)
    lbu x13, 0(x23)
    lbu x12, 0(x7)
    lbu x10, 0(x21)
    lbu x2, 0(x24)
    lbu x26, 0(x31)
    lbu x23, 0(x26)
    lbu x13, 0(x24)
    lbu x29, 0(x13)
    lbu x28, 0(x24)
    lbu x16, 0(x31)
    lbu x22, 0(x28)
    lbu x14, 0(x5)
    lbu x21, 0(x8)
    lbu x11, 0(x3)
    lbu x19, 0(x15)

la x3, _scratch_buf
mv x4, x3
mv x5, x3
mv x6, x3
mv x7, x3
mv x8, x3
mv x9, x3
mv x10, x3
mv x11, x3
mv x12, x3
mv x13, x3
mv x14, x3
mv x15, x3
mv x16, x3
mv x17, x3
mv x18, x3
mv x19, x3
mv x20, x3
mv x21, x3
mv x22, x3
mv x23, x3
mv x24, x3
mv x25, x3
mv x26, x3
mv x27, x3
mv x28, x3
mv x29, x3
mv x30, x3
mv x31, x3
    lh x5, 0(x25)
    lh x4, 0(x14)
    lh x13, 0(x6)
    lh x20, 0(x8)
    lh x6, 0(x12)
    lh x8, 0(x31)
    lh x30, 0(x17)
    lh x1, 0(x31)
    lh x7, 0(x29)
    lh x24, 0(x20)
    lh x10, 0(x10)
    lh x17, 0(x11)
    lh x31, 0(x22)
    lh x15, 0(x18)
    lh x13, 0(x4)
    lh x3, 0(x16)
    lh x18, 0(x9)
    lh x9, 0(x19)
    lh x27, 0(x30)
    lh x25, 0(x27)
    lh x13, 0(x23)
    lh x12, 0(x7)
    lh x10, 0(x21)
    lh x2, 0(x24)
    lh x26, 0(x31)
    lh x23, 0(x26)
    lh x13, 0(x24)
    lh x29, 0(x13)
    lh x28, 0(x24)
    lh x16, 0(x31)
    lh x22, 0(x28)
    lh x14, 0(x5)
    lh x21, 0(x8)
    lh x11, 0(x3)
    lh x19, 0(x15)

la x3, _scratch_buf
mv x4, x3
mv x5, x3
mv x6, x3
mv x7, x3
mv x8, x3
mv x9, x3
mv x10, x3
mv x11, x3
mv x12, x3
mv x13, x3
mv x14, x3
mv x15, x3
mv x16, x3
mv x17, x3
mv x18, x3
mv x19, x3
mv x20, x3
mv x21, x3
mv x22, x3
mv x23, x3
mv x24, x3
mv x25, x3
mv x26, x3
mv x27, x3
mv x28, x3
mv x29, x3
mv x30, x3
mv x31, x3
    lhu x5, 0(x25)
    lhu x4, 0(x14)
    lhu x13, 0(x6)
    lhu x20, 0(x8)
    lhu x6, 0(x12)
    lhu x8, 0(x31)
    lhu x30, 0(x17)
    lhu x1, 0(x31)
    lhu x7, 0(x29)
    lhu x24, 0(x20)
    lhu x10, 0(x10)
    lhu x17, 0(x11)
    lhu x31, 0(x22)
    lhu x15, 0(x18)
    lhu x13, 0(x4)
    lhu x3, 0(x16)
    lhu x18, 0(x9)
    lhu x9, 0(x19)
    lhu x27, 0(x30)
    lhu x25, 0(x27)
    lhu x13, 0(x23)
    lhu x12, 0(x7)
    lhu x10, 0(x21)
    lhu x2, 0(x24)
    lhu x26, 0(x31)
    lhu x23, 0(x26)
    lhu x13, 0(x24)
    lhu x29, 0(x13)
    lhu x28, 0(x24)
    lhu x16, 0(x31)
    lhu x22, 0(x28)
    lhu x14, 0(x5)
    lhu x21, 0(x8)
    lhu x11, 0(x3)
    lhu x19, 0(x15)

la x3, _scratch_buf
mv x4, x3
mv x5, x3
mv x6, x3
mv x7, x3
mv x8, x3
mv x9, x3
mv x10, x3
mv x11, x3
mv x12, x3
mv x13, x3
mv x14, x3
mv x15, x3
mv x16, x3
mv x17, x3
mv x18, x3
mv x19, x3
mv x20, x3
mv x21, x3
mv x22, x3
mv x23, x3
mv x24, x3
mv x25, x3
mv x26, x3
mv x27, x3
mv x28, x3
mv x29, x3
mv x30, x3
mv x31, x3
    lw x5, 0(x25)
    lw x4, 0(x14)
    lw x13, 0(x6)
    lw x20, 0(x8)
    lw x6, 0(x12)
    lw x8, 0(x31)
    lw x30, 0(x17)
    lw x1, 0(x31)
    lw x7, 0(x29)
    lw x24, 0(x20)
    lw x10, 0(x10)
    lw x17, 0(x11)
    lw x31, 0(x22)
    lw x15, 0(x18)
    lw x13, 0(x4)
    lw x3, 0(x16)
    lw x18, 0(x9)
    lw x9, 0(x19)
    lw x27, 0(x30)
    lw x25, 0(x27)
    lw x13, 0(x23)
    lw x12, 0(x7)
    lw x10, 0(x21)
    lw x2, 0(x24)
    lw x26, 0(x31)
    lw x23, 0(x26)
    lw x13, 0(x24)
    lw x29, 0(x13)
    lw x28, 0(x24)
    lw x16, 0(x31)
    lw x22, 0(x28)
    lw x14, 0(x5)
    lw x21, 0(x8)
    lw x11, 0(x3)
    lw x19, 0(x15)

la x3, _scratch_buf
mv x4, x3
mv x5, x3
mv x6, x3
mv x7, x3
mv x8, x3
mv x9, x3
mv x10, x3
mv x11, x3
mv x12, x3
mv x13, x3
mv x14, x3
mv x15, x3
mv x16, x3
mv x17, x3
mv x18, x3
mv x19, x3
mv x20, x3
mv x21, x3
mv x22, x3
mv x23, x3
mv x24, x3
mv x25, x3
mv x26, x3
mv x27, x3
mv x28, x3
mv x29, x3
mv x30, x3
mv x31, x3
    sb x3, 0(x19)
    sb x8, 0(x27)
    sb x7, 0(x13)
    sb x11, 0(x15)
    sb x0, 0(x15)
    sb x4, 0(x5)
    sb x18, 0(x4)
    sb x1, 0(x3)
    sb x14, 0(x21)
    sb x0, 0(x29)
    sb x6, 0(x10)
    sb x9, 0(x26)
    sb x0, 0(x3)
    sb x0, 0(x6)
    sb x10, 0(x28)
    sb x12, 0(x12)
    sb x2, 0(x22)
    sb x13, 0(x3)
    sb x5, 0(x17)
    sb x15, 0(x8)
    sb x16, 0(x3)
    sb x17, 0(x16)
    sb x19, 0(x7)
    sb x20, 0(x31)
    sb x21, 0(x14)
    sb x22, 0(x9)
    sb x23, 0(x3)
    sb x24, 0(x20)
    sb x25, 0(x3)
    sb x26, 0(x24)
    sb x27, 0(x25)
    sb x28, 0(x18)
    sb x29, 0(x23)
    sb x30, 0(x30)
    sb x31, 0(x11)

la x3, _scratch_buf
mv x4, x3
mv x5, x3
mv x6, x3
mv x7, x3
mv x8, x3
mv x9, x3
mv x10, x3
mv x11, x3
mv x12, x3
mv x13, x3
mv x14, x3
mv x15, x3
mv x16, x3
mv x17, x3
mv x18, x3
mv x19, x3
mv x20, x3
mv x21, x3
mv x22, x3
mv x23, x3
mv x24, x3
mv x25, x3
mv x26, x3
mv x27, x3
mv x28, x3
mv x29, x3
mv x30, x3
mv x31, x3
    sh x3, 0(x19)
    sh x8, 0(x27)
    sh x7, 0(x13)
    sh x11, 0(x15)
    sh x0, 0(x15)
    sh x4, 0(x5)
    sh x18, 0(x4)
    sh x1, 0(x3)
    sh x14, 0(x21)
    sh x0, 0(x29)
    sh x6, 0(x10)
    sh x9, 0(x26)
    sh x0, 0(x3)
    sh x0, 0(x6)
    sh x10, 0(x28)
    sh x12, 0(x12)
    sh x2, 0(x22)
    sh x13, 0(x3)
    sh x5, 0(x17)
    sh x15, 0(x8)
    sh x16, 0(x3)
    sh x17, 0(x16)
    sh x19, 0(x7)
    sh x20, 0(x31)
    sh x21, 0(x14)
    sh x22, 0(x9)
    sh x23, 0(x3)
    sh x24, 0(x20)
    sh x25, 0(x3)
    sh x26, 0(x24)
    sh x27, 0(x25)
    sh x28, 0(x18)
    sh x29, 0(x23)
    sh x30, 0(x30)
    sh x31, 0(x11)

la x3, _scratch_buf
mv x4, x3
mv x5, x3
mv x6, x3
mv x7, x3
mv x8, x3
mv x9, x3
mv x10, x3
mv x11, x3
mv x12, x3
mv x13, x3
mv x14, x3
mv x15, x3
mv x16, x3
mv x17, x3
mv x18, x3
mv x19, x3
mv x20, x3
mv x21, x3
mv x22, x3
mv x23, x3
mv x24, x3
mv x25, x3
mv x26, x3
mv x27, x3
mv x28, x3
mv x29, x3
mv x30, x3
mv x31, x3
    sw x3, 0(x19)
    sw x8, 0(x27)
    sw x7, 0(x13)
    sw x11, 0(x15)
    sw x0, 0(x15)
    sw x4, 0(x5)
    sw x18, 0(x4)
    sw x1, 0(x3)
    sw x14, 0(x21)
    sw x0, 0(x29)
    sw x6, 0(x10)
    sw x9, 0(x26)
    sw x0, 0(x3)
    sw x0, 0(x6)
    sw x10, 0(x28)
    sw x12, 0(x12)
    sw x2, 0(x22)
    sw x13, 0(x3)
    sw x5, 0(x17)
    sw x15, 0(x8)
    sw x16, 0(x3)
    sw x17, 0(x16)
    sw x19, 0(x7)
    sw x20, 0(x31)
    sw x21, 0(x14)
    sw x22, 0(x9)
    sw x23, 0(x3)
    sw x24, 0(x20)
    sw x25, 0(x3)
    sw x26, 0(x24)
    sw x27, 0(x25)
    sw x28, 0(x18)
    sw x29, 0(x23)
    sw x30, 0(x30)
    sw x31, 0(x11)

mv x1, x2
la x2, _scratch_buf
lb  x3, 0(x2)
lbu x3, 1(x2)
lh  x3, 2(x2)
lhu x3, 4(x2)
lw  x3, 8(x2)
sb x3, 16(x2)
sh x3, 18(x2)
mv x2, x1
la x3, 1f
jalr x2, x3, 0
1:
csrrw x2, mscratch, x3
csrrs x2, mscratch, x3
csrrc x2, mscratch, x3
csrrwi x2, mscratch, 1
csrrsi x2, mscratch, 1
csrrci x2, mscratch, 1

exit:
    li   t0, 0
    slli t0, t0, 1
    ori  t0, t0, 1

    la   t1, tohost
    sd   t0, 0(t1)
spin:
    j    spin

    .section ".tohost","aw",@progbits
    .align 6
    .globl tohost
tohost:
    .dword 0
    .align 6
    .globl fromhost
fromhost:
    .dword 0

    .data
    .balign 16
    .globl _scratch_buf
_scratch_buf:
    .fill 256, 1, 0

