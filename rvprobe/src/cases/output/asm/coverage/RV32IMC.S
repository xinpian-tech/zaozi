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

mul x11, x26, x4
    mul x2, x24, x18
    mul x15, x18, x19
    mul x22, x22, x2
    mul x14, x28, x30
    mul x9, x9, x21
    mul x1, x11, x11
    mul x11, x22, x3
    mul x6, x14, x30
    mul x4, x4, x27
    mul x23, x17, x22
    mul x16, x20, x24
    mul x10, x31, x20
    mul x20, x7, x7
    mul x8, x3, x6
    mul x12, x10, x30
    mul x1, x15, x1
    mul x1, x2, x14
    mul x7, x30, x15
    mul x13, x8, x28
    mul x25, x21, x17
    mul x18, x13, x30
    mul x3, x16, x25
    mul x19, x6, x5
    mul x27, x19, x23
    mul x26, x27, x29
    mul x5, x27, x28
    mul x30, x25, x8
    mul x31, x23, x12
    mul x22, x5, x13
    mul x24, x11, x10
    mul x17, x27, x26
    mul x21, x29, x16
    mul x28, x12, x31
    mul x29, x1, x9

mulh x11, x26, x4
    mulh x2, x24, x18
    mulh x15, x18, x19
    mulh x22, x22, x2
    mulh x14, x28, x30
    mulh x9, x9, x21
    mulh x1, x11, x11
    mulh x11, x22, x3
    mulh x6, x14, x30
    mulh x4, x4, x27
    mulh x23, x17, x22
    mulh x16, x20, x24
    mulh x10, x31, x20
    mulh x20, x7, x7
    mulh x8, x3, x6
    mulh x12, x10, x30
    mulh x1, x15, x1
    mulh x1, x2, x14
    mulh x7, x30, x15
    mulh x13, x8, x28
    mulh x25, x21, x17
    mulh x18, x13, x30
    mulh x3, x16, x25
    mulh x19, x6, x5
    mulh x27, x19, x23
    mulh x26, x27, x29
    mulh x5, x27, x28
    mulh x30, x25, x8
    mulh x31, x23, x12
    mulh x22, x5, x13
    mulh x24, x11, x10
    mulh x17, x27, x26
    mulh x21, x29, x16
    mulh x28, x12, x31
    mulh x29, x1, x9

mulhu x11, x26, x4
    mulhu x2, x24, x18
    mulhu x15, x18, x19
    mulhu x22, x22, x2
    mulhu x14, x28, x30
    mulhu x9, x9, x21
    mulhu x1, x11, x11
    mulhu x11, x22, x3
    mulhu x6, x14, x30
    mulhu x4, x4, x27
    mulhu x23, x17, x22
    mulhu x16, x20, x24
    mulhu x10, x31, x20
    mulhu x20, x7, x7
    mulhu x8, x3, x6
    mulhu x12, x10, x30
    mulhu x1, x15, x1
    mulhu x1, x2, x14
    mulhu x7, x30, x15
    mulhu x13, x8, x28
    mulhu x25, x21, x17
    mulhu x18, x13, x30
    mulhu x3, x16, x25
    mulhu x19, x6, x5
    mulhu x27, x19, x23
    mulhu x26, x27, x29
    mulhu x5, x27, x28
    mulhu x30, x25, x8
    mulhu x31, x23, x12
    mulhu x22, x5, x13
    mulhu x24, x11, x10
    mulhu x17, x27, x26
    mulhu x21, x29, x16
    mulhu x28, x12, x31
    mulhu x29, x1, x9

mulhsu x11, x26, x4
    mulhsu x2, x24, x18
    mulhsu x15, x18, x19
    mulhsu x22, x22, x2
    mulhsu x14, x28, x30
    mulhsu x9, x9, x21
    mulhsu x1, x11, x11
    mulhsu x11, x22, x3
    mulhsu x6, x14, x30
    mulhsu x4, x4, x27
    mulhsu x23, x17, x22
    mulhsu x16, x20, x24
    mulhsu x10, x31, x20
    mulhsu x20, x7, x7
    mulhsu x8, x3, x6
    mulhsu x12, x10, x30
    mulhsu x1, x15, x1
    mulhsu x1, x2, x14
    mulhsu x7, x30, x15
    mulhsu x13, x8, x28
    mulhsu x25, x21, x17
    mulhsu x18, x13, x30
    mulhsu x3, x16, x25
    mulhsu x19, x6, x5
    mulhsu x27, x19, x23
    mulhsu x26, x27, x29
    mulhsu x5, x27, x28
    mulhsu x30, x25, x8
    mulhsu x31, x23, x12
    mulhsu x22, x5, x13
    mulhsu x24, x11, x10
    mulhsu x17, x27, x26
    mulhsu x21, x29, x16
    mulhsu x28, x12, x31
    mulhsu x29, x1, x9

div x11, x26, x4
    div x2, x24, x18
    div x15, x18, x19
    div x22, x22, x2
    div x14, x28, x30
    div x9, x9, x21
    div x1, x11, x11
    div x11, x22, x3
    div x6, x14, x30
    div x4, x4, x27
    div x23, x17, x22
    div x16, x20, x24
    div x10, x31, x20
    div x20, x7, x7
    div x8, x3, x6
    div x12, x10, x30
    div x1, x15, x1
    div x1, x2, x14
    div x7, x30, x15
    div x13, x8, x28
    div x25, x21, x17
    div x18, x13, x30
    div x3, x16, x25
    div x19, x6, x5
    div x27, x19, x23
    div x26, x27, x29
    div x5, x27, x28
    div x30, x25, x8
    div x31, x23, x12
    div x22, x5, x13
    div x24, x11, x10
    div x17, x27, x26
    div x21, x29, x16
    div x28, x12, x31
    div x29, x1, x9

divu x11, x26, x4
    divu x2, x24, x18
    divu x15, x18, x19
    divu x22, x22, x2
    divu x14, x28, x30
    divu x9, x9, x21
    divu x1, x11, x11
    divu x11, x22, x3
    divu x6, x14, x30
    divu x4, x4, x27
    divu x23, x17, x22
    divu x16, x20, x24
    divu x10, x31, x20
    divu x20, x7, x7
    divu x8, x3, x6
    divu x12, x10, x30
    divu x1, x15, x1
    divu x1, x2, x14
    divu x7, x30, x15
    divu x13, x8, x28
    divu x25, x21, x17
    divu x18, x13, x30
    divu x3, x16, x25
    divu x19, x6, x5
    divu x27, x19, x23
    divu x26, x27, x29
    divu x5, x27, x28
    divu x30, x25, x8
    divu x31, x23, x12
    divu x22, x5, x13
    divu x24, x11, x10
    divu x17, x27, x26
    divu x21, x29, x16
    divu x28, x12, x31
    divu x29, x1, x9

rem x11, x26, x4
    rem x2, x24, x18
    rem x15, x18, x19
    rem x22, x22, x2
    rem x14, x28, x30
    rem x9, x9, x21
    rem x1, x11, x11
    rem x11, x22, x3
    rem x6, x14, x30
    rem x4, x4, x27
    rem x23, x17, x22
    rem x16, x20, x24
    rem x10, x31, x20
    rem x20, x7, x7
    rem x8, x3, x6
    rem x12, x10, x30
    rem x1, x15, x1
    rem x1, x2, x14
    rem x7, x30, x15
    rem x13, x8, x28
    rem x25, x21, x17
    rem x18, x13, x30
    rem x3, x16, x25
    rem x19, x6, x5
    rem x27, x19, x23
    rem x26, x27, x29
    rem x5, x27, x28
    rem x30, x25, x8
    rem x31, x23, x12
    rem x22, x5, x13
    rem x24, x11, x10
    rem x17, x27, x26
    rem x21, x29, x16
    rem x28, x12, x31
    rem x29, x1, x9

remu x11, x26, x4
    remu x2, x24, x18
    remu x15, x18, x19
    remu x22, x22, x2
    remu x14, x28, x30
    remu x9, x9, x21
    remu x1, x11, x11
    remu x11, x22, x3
    remu x6, x14, x30
    remu x4, x4, x27
    remu x23, x17, x22
    remu x16, x20, x24
    remu x10, x31, x20
    remu x20, x7, x7
    remu x8, x3, x6
    remu x12, x10, x30
    remu x1, x15, x1
    remu x1, x2, x14
    remu x7, x30, x15
    remu x13, x8, x28
    remu x25, x21, x17
    remu x18, x13, x30
    remu x3, x16, x25
    remu x19, x6, x5
    remu x27, x19, x23
    remu x26, x27, x29
    remu x5, x27, x28
    remu x30, x25, x8
    remu x31, x23, x12
    remu x22, x5, x13
    remu x24, x11, x10
    remu x17, x27, x26
    remu x21, x29, x16
    remu x28, x12, x31
    remu x29, x1, x9

c.add x17, x7
    c.add x21, x13
    c.add x11, x6
    c.add x22, x1
    c.add x6, x4
    c.add x29, x2
    c.add x1, x1
    c.add x14, x8
    c.add x12, x9
    c.add x8, x5
    c.add x2, x14
    c.add x13, x3
    c.add x1, x1
    c.add x18, x1
    c.add x1, x10
    c.add x4, x1
    c.add x20, x11
    c.add x31, x12
    c.add x30, x15
    c.add x15, x16
    c.add x5, x17
    c.add x28, x18
    c.add x24, x19
    c.add x3, x20
    c.add x16, x21
    c.add x1, x22
    c.add x1, x23
    c.add x7, x24
    c.add x10, x25
    c.add x23, x26
    c.add x25, x27
    c.add x27, x28
    c.add x19, x29
    c.add x26, x30
    c.add x9, x31

c.mv x17, x7
    c.mv x21, x13
    c.mv x11, x6
    c.mv x22, x1
    c.mv x6, x4
    c.mv x29, x2
    c.mv x1, x1
    c.mv x14, x8
    c.mv x12, x9
    c.mv x8, x5
    c.mv x2, x14
    c.mv x13, x3
    c.mv x1, x1
    c.mv x18, x1
    c.mv x1, x10
    c.mv x4, x1
    c.mv x20, x11
    c.mv x31, x12
    c.mv x30, x15
    c.mv x15, x16
    c.mv x5, x17
    c.mv x28, x18
    c.mv x24, x19
    c.mv x3, x20
    c.mv x16, x21
    c.mv x1, x22
    c.mv x1, x23
    c.mv x7, x24
    c.mv x10, x25
    c.mv x23, x26
    c.mv x25, x27
    c.mv x27, x28
    c.mv x19, x29
    c.mv x26, x30
    c.mv x9, x31

la x5, cjr_rv32imc_1
mv x1, x5
c.jr x1
cjr_rv32imc_1:
la x5, cjr_rv32imc_2
mv x2, x5
c.jr x2
cjr_rv32imc_2:
la x5, cjr_rv32imc_3
mv x3, x5
c.jr x3
cjr_rv32imc_3:
la x5, cjr_rv32imc_4
mv x4, x5
c.jr x4
cjr_rv32imc_4:
la x5, cjr_rv32imc_5
mv x5, x5
c.jr x5
cjr_rv32imc_5:
la x5, cjr_rv32imc_6
mv x6, x5
c.jr x6
cjr_rv32imc_6:
la x5, cjr_rv32imc_7
mv x7, x5
c.jr x7
cjr_rv32imc_7:
la x5, cjr_rv32imc_8
mv x8, x5
c.jr x8
cjr_rv32imc_8:
la x5, cjr_rv32imc_9
mv x9, x5
c.jr x9
cjr_rv32imc_9:
la x5, cjr_rv32imc_10
mv x10, x5
c.jr x10
cjr_rv32imc_10:
la x5, cjr_rv32imc_11
mv x11, x5
c.jr x11
cjr_rv32imc_11:
la x5, cjr_rv32imc_12
mv x12, x5
c.jr x12
cjr_rv32imc_12:
la x5, cjr_rv32imc_13
mv x13, x5
c.jr x13
cjr_rv32imc_13:
la x5, cjr_rv32imc_14
mv x14, x5
c.jr x14
cjr_rv32imc_14:
la x5, cjr_rv32imc_15
mv x15, x5
c.jr x15
cjr_rv32imc_15:
la x5, cjr_rv32imc_16
mv x16, x5
c.jr x16
cjr_rv32imc_16:
la x5, cjr_rv32imc_17
mv x17, x5
c.jr x17
cjr_rv32imc_17:
la x5, cjr_rv32imc_18
mv x18, x5
c.jr x18
cjr_rv32imc_18:
la x5, cjr_rv32imc_19
mv x19, x5
c.jr x19
cjr_rv32imc_19:
la x5, cjr_rv32imc_20
mv x20, x5
c.jr x20
cjr_rv32imc_20:
la x5, cjr_rv32imc_21
mv x21, x5
c.jr x21
cjr_rv32imc_21:
la x5, cjr_rv32imc_22
mv x22, x5
c.jr x22
cjr_rv32imc_22:
la x5, cjr_rv32imc_23
mv x23, x5
c.jr x23
cjr_rv32imc_23:
la x5, cjr_rv32imc_24
mv x24, x5
c.jr x24
cjr_rv32imc_24:
la x5, cjr_rv32imc_25
mv x25, x5
c.jr x25
cjr_rv32imc_25:
la x5, cjr_rv32imc_26
mv x26, x5
c.jr x26
cjr_rv32imc_26:
la x5, cjr_rv32imc_27
mv x27, x5
c.jr x27
cjr_rv32imc_27:
la x5, cjr_rv32imc_28
mv x28, x5
c.jr x28
cjr_rv32imc_28:
la x5, cjr_rv32imc_29
mv x29, x5
c.jr x29
cjr_rv32imc_29:
la x5, cjr_rv32imc_30
mv x30, x5
c.jr x30
cjr_rv32imc_30:
la x5, cjr_rv32imc_31
mv x31, x5
c.jr x31
cjr_rv32imc_31:

la x5, cjalr_rv32imc_1
mv x1, x5
c.jalr x1
cjalr_rv32imc_1:
la x5, cjalr_rv32imc_2
mv x2, x5
c.jalr x2
cjalr_rv32imc_2:
la x5, cjalr_rv32imc_3
mv x3, x5
c.jalr x3
cjalr_rv32imc_3:
la x5, cjalr_rv32imc_4
mv x4, x5
c.jalr x4
cjalr_rv32imc_4:
la x5, cjalr_rv32imc_5
mv x5, x5
c.jalr x5
cjalr_rv32imc_5:
la x5, cjalr_rv32imc_6
mv x6, x5
c.jalr x6
cjalr_rv32imc_6:
la x5, cjalr_rv32imc_7
mv x7, x5
c.jalr x7
cjalr_rv32imc_7:
la x5, cjalr_rv32imc_8
mv x8, x5
c.jalr x8
cjalr_rv32imc_8:
la x5, cjalr_rv32imc_9
mv x9, x5
c.jalr x9
cjalr_rv32imc_9:
la x5, cjalr_rv32imc_10
mv x10, x5
c.jalr x10
cjalr_rv32imc_10:
la x5, cjalr_rv32imc_11
mv x11, x5
c.jalr x11
cjalr_rv32imc_11:
la x5, cjalr_rv32imc_12
mv x12, x5
c.jalr x12
cjalr_rv32imc_12:
la x5, cjalr_rv32imc_13
mv x13, x5
c.jalr x13
cjalr_rv32imc_13:
la x5, cjalr_rv32imc_14
mv x14, x5
c.jalr x14
cjalr_rv32imc_14:
la x5, cjalr_rv32imc_15
mv x15, x5
c.jalr x15
cjalr_rv32imc_15:
la x5, cjalr_rv32imc_16
mv x16, x5
c.jalr x16
cjalr_rv32imc_16:
la x5, cjalr_rv32imc_17
mv x17, x5
c.jalr x17
cjalr_rv32imc_17:
la x5, cjalr_rv32imc_18
mv x18, x5
c.jalr x18
cjalr_rv32imc_18:
la x5, cjalr_rv32imc_19
mv x19, x5
c.jalr x19
cjalr_rv32imc_19:
la x5, cjalr_rv32imc_20
mv x20, x5
c.jalr x20
cjalr_rv32imc_20:
la x5, cjalr_rv32imc_21
mv x21, x5
c.jalr x21
cjalr_rv32imc_21:
la x5, cjalr_rv32imc_22
mv x22, x5
c.jalr x22
cjalr_rv32imc_22:
la x5, cjalr_rv32imc_23
mv x23, x5
c.jalr x23
cjalr_rv32imc_23:
la x5, cjalr_rv32imc_24
mv x24, x5
c.jalr x24
cjalr_rv32imc_24:
la x5, cjalr_rv32imc_25
mv x25, x5
c.jalr x25
cjalr_rv32imc_25:
la x5, cjalr_rv32imc_26
mv x26, x5
c.jalr x26
cjalr_rv32imc_26:
la x5, cjalr_rv32imc_27
mv x27, x5
c.jalr x27
cjalr_rv32imc_27:
la x5, cjalr_rv32imc_28
mv x28, x5
c.jalr x28
cjalr_rv32imc_28:
la x5, cjalr_rv32imc_29
mv x29, x5
c.jalr x29
cjalr_rv32imc_29:
la x5, cjalr_rv32imc_30
mv x30, x5
c.jalr x30
cjalr_rv32imc_30:
la x5, cjalr_rv32imc_31
mv x31, x5
c.jalr x31
cjalr_rv32imc_31:

c.addi x9, 1
    c.addi x12, 1
    c.addi x3, 1
    c.addi x8, 1
    c.addi x10, 1
    c.addi x4, 1
    c.addi x1, 1
    c.addi x16, 1
    c.addi x15, 1
    c.addi x13, 1
    c.addi x6, 1
    c.addi x1, 1
    c.addi x1, 1
    c.addi x17, 1
    c.addi x2, 1
    c.addi x7, 1
    c.addi x14, 1
    c.addi x5, 1
    c.addi x1, 1
    c.addi x1, 1
    c.addi x11, 1
    c.addi x18, 1
    c.addi x20, 1
    c.addi x19, 1
    c.addi x21, 1
    c.addi x22, 1
    c.addi x23, 1
    c.addi x24, 1
    c.addi x25, 1
    c.addi x26, 1
    c.addi x27, 1
    c.addi x28, 1
    c.addi x29, 1
    c.addi x30, 1
    c.addi x31, 1

c.li x9, 0
    c.li x10, 0
    c.li x5, 0
    c.li x1, 0
    c.li x16, 0
    c.li x15, 0
    c.li x13, 0
    c.li x2, 0
    c.li x1, 0
    c.li x1, 0
    c.li x1, 0
    c.li x7, 0
    c.li x11, 0
    c.li x6, 0
    c.li x4, 0
    c.li x14, 0
    c.li x1, 0
    c.li x8, 0
    c.li x17, 0
    c.li x3, 0
    c.li x12, 0
    c.li x18, 0
    c.li x19, 0
    c.li x20, 0
    c.li x21, 0
    c.li x22, 0
    c.li x23, 0
    c.li x24, 0
    c.li x25, 0
    c.li x26, 0
    c.li x27, 0
    c.li x28, 0
    c.li x29, 0
    c.li x30, 0
    c.li x31, 0

c.lui x9, 1
    c.lui x13, 1
    c.lui x29, 1
    c.lui x16, 1
    c.lui x7, 1
    c.lui x20, 1
    c.lui x24, 1
    c.lui x17, 1
    c.lui x0, 1
    c.lui x4, 1
    c.lui x13, 1
    c.lui x23, 1
    c.lui x18, 1
    c.lui x0, 1
    c.lui x5, 1
    c.lui x14, 1
    c.lui x28, 1
    c.lui x8, 1
    c.lui x11, 1
    c.lui x10, 1
    c.lui x22, 1
    c.lui x13, 1
    c.lui x3, 1
    c.lui x19, 1
    c.lui x27, 1
    c.lui x6, 1
    c.lui x15, 1
    c.lui x12, 1
    c.lui x31, 1
    c.lui x1, 1
    c.lui x3, 1
    c.lui x26, 1
    c.lui x21, 1
    c.lui x30, 1
    c.lui x25, 1

c.slli x9, 1
    c.slli x10, 1
    c.slli x5, 1
    c.slli x1, 1
    c.slli x16, 1
    c.slli x15, 1
    c.slli x13, 1
    c.slli x2, 1
    c.slli x1, 1
    c.slli x1, 1
    c.slli x1, 1
    c.slli x7, 1
    c.slli x11, 1
    c.slli x6, 1
    c.slli x4, 1
    c.slli x14, 1
    c.slli x1, 1
    c.slli x8, 1
    c.slli x17, 1
    c.slli x3, 1
    c.slli x12, 1
    c.slli x18, 1
    c.slli x19, 1
    c.slli x20, 1
    c.slli x21, 1
    c.slli x22, 1
    c.slli x23, 1
    c.slli x24, 1
    c.slli x25, 1
    c.slli x26, 1
    c.slli x27, 1
    c.slli x28, 1
    c.slli x29, 1
    c.slli x30, 1
    c.slli x31, 1

la x2, _scratch_buf
    c.lwsp x16, 0(sp)
    c.lwsp x1, 0(sp)
    c.lwsp x1, 0(sp)
    c.lwsp x11, 0(sp)
    c.lwsp x3, 0(sp)
    c.lwsp x4, 0(sp)
    c.lwsp x1, 0(sp)
    c.lwsp x10, 0(sp)
    c.lwsp x13, 0(sp)
    c.lwsp x1, 0(sp)
    c.lwsp x17, 0(sp)
    c.lwsp x14, 0(sp)
    c.lwsp x5, 0(sp)
    c.lwsp x8, 0(sp)
    c.lwsp x6, 0(sp)
    c.lwsp x7, 0(sp)
    c.lwsp x2, 0(sp)
    c.lwsp x15, 0(sp)
    c.lwsp x12, 0(sp)
    c.lwsp x9, 0(sp)
    c.lwsp x1, 0(sp)
    c.lwsp x18, 0(sp)
    c.lwsp x19, 0(sp)
    c.lwsp x20, 0(sp)
    c.lwsp x21, 0(sp)
    c.lwsp x22, 0(sp)
    c.lwsp x23, 0(sp)
    c.lwsp x24, 0(sp)
    c.lwsp x25, 0(sp)
    c.lwsp x26, 0(sp)
    c.lwsp x27, 0(sp)
    c.lwsp x28, 0(sp)
    c.lwsp x29, 0(sp)
    c.lwsp x30, 0(sp)
    c.lwsp x31, 0(sp)

la x2, _scratch_buf
    c.swsp x9, 0(sp)
    c.swsp x14, 0(sp)
    c.swsp x0, 0(sp)
    c.swsp x16, 0(sp)
    c.swsp x6, 0(sp)
    c.swsp x3, 0(sp)
    c.swsp x12, 0(sp)
    c.swsp x2, 0(sp)
    c.swsp x0, 0(sp)
    c.swsp x10, 0(sp)
    c.swsp x7, 0(sp)
    c.swsp x5, 0(sp)
    c.swsp x13, 0(sp)
    c.swsp x15, 0(sp)
    c.swsp x4, 0(sp)
    c.swsp x1, 0(sp)
    c.swsp x0, 0(sp)
    c.swsp x0, 0(sp)
    c.swsp x11, 0(sp)
    c.swsp x17, 0(sp)
    c.swsp x8, 0(sp)
    c.swsp x18, 0(sp)
    c.swsp x19, 0(sp)
    c.swsp x20, 0(sp)
    c.swsp x21, 0(sp)
    c.swsp x22, 0(sp)
    c.swsp x23, 0(sp)
    c.swsp x24, 0(sp)
    c.swsp x25, 0(sp)
    c.swsp x26, 0(sp)
    c.swsp x27, 0(sp)
    c.swsp x28, 0(sp)
    c.swsp x29, 0(sp)
    c.swsp x30, 0(sp)
    c.swsp x31, 0(sp)

la x2, _scratch_buf
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x8, sp, 8
    c.addi4spn x9, sp, 8
    c.addi4spn x10, sp, 8
    c.addi4spn x11, sp, 8
    c.addi4spn x12, sp, 8
    c.addi4spn x13, sp, 8
    c.addi4spn x14, sp, 8
    c.addi4spn x15, sp, 8

la x2, _scratch_buf
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
    c.addi16sp sp, 16
    c.addi16sp sp, -16
la x2, _scratch_buf

la x8, _scratch_buf
mv x9, x8
mv x10, x8
mv x11, x8
mv x12, x8
mv x13, x8
mv x14, x8
mv x15, x8
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x11, 0(x8)
    c.lw x8, 0(x8)
    c.lw x14, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x15, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x8)
    c.lw x8, 0(x9)
    c.lw x8, 0(x10)
    c.lw x8, 0(x11)
    c.lw x9, 0(x12)
    c.lw x12, 0(x13)
    c.lw x10, 0(x14)
    c.lw x13, 0(x15)

la x8, _scratch_buf
mv x9, x8
mv x10, x8
mv x11, x8
mv x12, x8
mv x13, x8
mv x14, x8
mv x15, x8
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x11, 0(x8)
    c.sw x8, 0(x8)
    c.sw x14, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x15, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x8)
    c.sw x8, 0(x9)
    c.sw x8, 0(x10)
    c.sw x8, 0(x11)
    c.sw x9, 0(x12)
    c.sw x12, 0(x13)
    c.sw x10, 0(x14)
    c.sw x13, 0(x15)

c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x11, x8
    c.and x8, x8
    c.and x14, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x15, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x8
    c.and x8, x9
    c.and x8, x10
    c.and x8, x11
    c.and x9, x12
    c.and x12, x13
    c.and x10, x14
    c.and x13, x15

c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x11, x8
    c.or x8, x8
    c.or x14, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x15, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x8
    c.or x8, x9
    c.or x8, x10
    c.or x8, x11
    c.or x9, x12
    c.or x12, x13
    c.or x10, x14
    c.or x13, x15

c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x11, x8
    c.xor x8, x8
    c.xor x14, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x15, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x8
    c.xor x8, x9
    c.xor x8, x10
    c.xor x8, x11
    c.xor x9, x12
    c.xor x12, x13
    c.xor x10, x14
    c.xor x13, x15

c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x11, x8
    c.sub x8, x8
    c.sub x14, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x15, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x8
    c.sub x8, x9
    c.sub x8, x10
    c.sub x8, x11
    c.sub x9, x12
    c.sub x12, x13
    c.sub x10, x14
    c.sub x13, x15

c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x8, 0
    c.andi x9, 0
    c.andi x10, 0
    c.andi x11, 0
    c.andi x12, 0
    c.andi x13, 0
    c.andi x14, 0
    c.andi x15, 0

c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x8, 1
    c.srli x9, 1
    c.srli x10, 1
    c.srli x11, 1
    c.srli x12, 1
    c.srli x13, 1
    c.srli x14, 1
    c.srli x15, 1

c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x8, 1
    c.srai x9, 1
    c.srai x10, 1
    c.srai x11, 1
    c.srai x12, 1
    c.srai x13, 1
    c.srai x14, 1
    c.srai x15, 1

addi x8, x0, -2
    addi x8, x0, -2
    addi x8, x0, -2
    addi x8, x0, -2
    addi x8, x0, -2
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x8, . + 4
    c.beqz x9, . + 4
    c.beqz x8, . + 4
    c.beqz x10, . + 4
    c.beqz x11, . + 4
    c.beqz x12, . + 4
    c.beqz x13, . + 4
    c.beqz x14, . + 4
    c.beqz x15, . + 4

addi x8, x0, -2
    addi x8, x0, -2
    addi x8, x0, -2
    addi x8, x0, -2
    addi x8, x0, -2
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x8, . + 4
    c.bnez x9, . + 4
    c.bnez x8, . + 4
    c.bnez x10, . + 4
    c.bnez x11, . + 4
    c.bnez x12, . + 4
    c.bnez x13, . + 4
    c.bnez x14, . + 4
    c.bnez x15, . + 4

c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2
    c.j . + 2

c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2
    c.jal . + 2

.option push
.option norvc
jal x1, jal_norvc_1
jal_norvc_1:
jal x2, jal_norvc_2
jal_norvc_2:
jal x3, jal_norvc_3
jal_norvc_3:
jal x4, jal_norvc_4
jal_norvc_4:
jal x5, jal_norvc_5
jal_norvc_5:
jal x6, jal_norvc_6
jal_norvc_6:
jal x7, jal_norvc_7
jal_norvc_7:
jal x8, jal_norvc_8
jal_norvc_8:
jal x9, jal_norvc_9
jal_norvc_9:
jal x10, jal_norvc_10
jal_norvc_10:
jal x11, jal_norvc_11
jal_norvc_11:
jal x12, jal_norvc_12
jal_norvc_12:
jal x13, jal_norvc_13
jal_norvc_13:
jal x14, jal_norvc_14
jal_norvc_14:
jal x15, jal_norvc_15
jal_norvc_15:
jal x16, jal_norvc_16
jal_norvc_16:
jal x17, jal_norvc_17
jal_norvc_17:
jal x18, jal_norvc_18
jal_norvc_18:
jal x19, jal_norvc_19
jal_norvc_19:
jal x20, jal_norvc_20
jal_norvc_20:
jal x21, jal_norvc_21
jal_norvc_21:
jal x22, jal_norvc_22
jal_norvc_22:
jal x23, jal_norvc_23
jal_norvc_23:
jal x24, jal_norvc_24
jal_norvc_24:
jal x25, jal_norvc_25
jal_norvc_25:
jal x26, jal_norvc_26
jal_norvc_26:
jal x27, jal_norvc_27
jal_norvc_27:
jal x28, jal_norvc_28
jal_norvc_28:
jal x29, jal_norvc_29
jal_norvc_29:
jal x30, jal_norvc_30
jal_norvc_30:
jal x31, jal_norvc_31
jal_norvc_31:
.option pop

.option push
.option norvc
la x5, jalr_norvc_1
jalr x1, x5, 0
jalr_norvc_1:
la x5, jalr_norvc_2
jalr x2, x5, 0
jalr_norvc_2:
la x5, jalr_norvc_3
jalr x3, x5, 0
jalr_norvc_3:
la x5, jalr_norvc_4
jalr x4, x5, 0
jalr_norvc_4:
la x5, jalr_norvc_5
jalr x5, x5, 0
jalr_norvc_5:
la x5, jalr_norvc_6
jalr x6, x5, 0
jalr_norvc_6:
la x5, jalr_norvc_7
jalr x7, x5, 0
jalr_norvc_7:
la x5, jalr_norvc_8
jalr x8, x5, 0
jalr_norvc_8:
la x5, jalr_norvc_9
jalr x9, x5, 0
jalr_norvc_9:
la x5, jalr_norvc_10
jalr x10, x5, 0
jalr_norvc_10:
la x5, jalr_norvc_11
jalr x11, x5, 0
jalr_norvc_11:
la x5, jalr_norvc_12
jalr x12, x5, 0
jalr_norvc_12:
la x5, jalr_norvc_13
jalr x13, x5, 0
jalr_norvc_13:
la x5, jalr_norvc_14
jalr x14, x5, 0
jalr_norvc_14:
la x5, jalr_norvc_15
jalr x15, x5, 0
jalr_norvc_15:
la x5, jalr_norvc_16
jalr x16, x5, 0
jalr_norvc_16:
la x5, jalr_norvc_17
jalr x17, x5, 0
jalr_norvc_17:
la x5, jalr_norvc_18
jalr x18, x5, 0
jalr_norvc_18:
la x5, jalr_norvc_19
jalr x19, x5, 0
jalr_norvc_19:
la x5, jalr_norvc_20
jalr x20, x5, 0
jalr_norvc_20:
la x5, jalr_norvc_21
jalr x21, x5, 0
jalr_norvc_21:
la x5, jalr_norvc_22
jalr x22, x5, 0
jalr_norvc_22:
la x5, jalr_norvc_23
jalr x23, x5, 0
jalr_norvc_23:
la x5, jalr_norvc_24
jalr x24, x5, 0
jalr_norvc_24:
la x5, jalr_norvc_25
jalr x25, x5, 0
jalr_norvc_25:
la x5, jalr_norvc_26
jalr x26, x5, 0
jalr_norvc_26:
la x5, jalr_norvc_27
jalr x27, x5, 0
jalr_norvc_27:
la x5, jalr_norvc_28
jalr x28, x5, 0
jalr_norvc_28:
la x5, jalr_norvc_29
jalr x29, x5, 0
jalr_norvc_29:
la x5, jalr_norvc_30
jalr x30, x5, 0
jalr_norvc_30:
la x5, jalr_norvc_31
jalr x31, x5, 0
jalr_norvc_31:
.option pop

exit:
    li   t0, 0
    slli t0, t0, 1
    ori  t0, t0, 1

    la   t1, tohost
    sw   t0, 0(t1)
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

