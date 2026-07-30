    .section ".text"
    .globl _start
_start:
    la x2, buf
    addi x7, x0, 42
    sw x7, 0(x2)
    fence rw, rw
    lw x8, 0(x2)
    la x1, buf2
    addi x4, x0, 99
    sw x4, 0(x1)
    fence.tso
    lw x3, 0(x1)
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
buf:
    .zero 64
    .balign 64
buf2:
    .zero 64