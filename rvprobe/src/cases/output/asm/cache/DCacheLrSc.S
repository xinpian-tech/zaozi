    .section ".text"
    .globl _start
_start:
    la x1, lock
retry:
    lr.w.aq x2, (x1)
    bnez x2, retry

    addi x3, x0, 1
    sc.w x4, x3, (x1)
    bnez x4, retry

    la x6, shared_data
    li x5, 0xbeef
    sw x5, 0(x6)
    sw x0, 0(x1)
    fence rw, rw
exit:
    li x5, 0x0
    slli x5, x5, 1
    ori x5, x5, 1
    la x6, tohost
    sd x5, 0(x6)
spin:
    j spin

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