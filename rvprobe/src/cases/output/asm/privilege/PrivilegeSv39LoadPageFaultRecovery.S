    .section ".text"
    .globl _start
_start:
    la x5, trap_handler_custom
    csrrw x0, mtvec, x5
    addi x5, x0, -1
    csrrw x0, pmpaddr0, x5
    addi x5, x0, 31
    csrrw x0, pmpcfg0, x5
    la x5, pgtbl
    li x6, 0x200000cf
    sd x6, 16(x5)
    la x5, pgtbl
    addi x7, x0, 8
    slli x7, x7, 60
    srli x6, x5, 12
    or x7, x7, x6
    csrrw x0, satp, x7
    sfence.vma x0, x0
    csrrw x0, medeleg, x0
    csrrw x0, mideleg, x0
    li x5, 0xffffffffffffe7ff
    csrrs x6, mstatus, x0
    and x6, x6, x5
    li x7, 0x800
    or x6, x6, x7
    csrrw x0, mstatus, x6
    la x5, s_code
    csrrw x0, mepc, x5
    mret
s_code:
    li x16, 0x40000000
    lw x17, 0(x16)
after_fault:
    la x10, trap_cause
    ld x11, 0(x10)
    addi x12, x0, 13
    bne x11, x12, fail

    j exit

    j trap_handler_custom_skip

    .align 2
trap_handler_custom:
    csrrs x6, mcause, x0
    la x28, trap_cause
    sd x6, 0(x28)
    addi x7, x0, 13
    bne x6, x7, fail

    la x5, after_fault
    csrrw x0, mepc, x5
    csrrw x0, mcause, x0
    csrrw x0, mtval, x0
    mret
trap_handler_custom_skip:
exit:
    li x5, 0x0
    slli x5, x5, 1
    ori x5, x5, 1
    la x6, tohost
    sd x5, 0(x6)
spin:
    j spin

fail:
    li x5, 0x1
    slli x5, x5, 1
    ori x5, x5, 1
    la x6, tohost
    sd x5, 0(x6)
spin_fail:
    j spin_fail

    .section ".tohost","aw",@progbits
    .align 6
    .globl tohost
tohost:
    .dword 0x0
    .align 6
    .globl fromhost
fromhost:
    .dword 0x0
    .section .data
    .balign 4096
pgtbl:
    .zero 4096
    .section .data
    .balign 8
trap_cause:
    .dword 0x0