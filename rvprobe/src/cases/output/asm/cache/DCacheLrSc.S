    .section ".text"
    .globl _start
_start:
    la x3, lock
retry:
    lr.w.aq x4, (x3)
    bnez x4, retry

    addi x1, x0, 1
    sc.w x2, x1, (x3)
    bnez x2, retry

    la x7, shared_data
    li x8, 0xbeef
    sw x8, 0(x7)
    sw x0, 0(x3)
    fence rw, rw
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
    .balign 64
lock:
    .zero 64
    .balign 64
shared_data:
    .zero 64