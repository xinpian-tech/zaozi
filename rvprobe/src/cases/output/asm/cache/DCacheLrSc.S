    .section .text
    .globl _start
_start:
    la x5, lock
retry:
    lr.w.aq x10, (x5)
    bnez x10, retry

    addi x11, x0, 1
    sc.w x12, x11, (x5)
    bnez x12, retry

    la x6, shared_data
    li x13, 0xbeef
    sw x13, 0(x6)
    sw x0, 0(x5)
    fence rw, rw
exit:
    addi x5, x0, 1
    la x6, tohost
    sd x5, 0(x6)
spin:
    j spin

    .section .data
    .balign 64
lock:
    .zero 64
    .balign 64
shared_data:
    .zero 64
    .section .tohost,"aw",@progbits
    .align 6
    .globl tohost
    .globl fromhost
tohost:
    .dword 0x0
fromhost:
    .dword 0x0