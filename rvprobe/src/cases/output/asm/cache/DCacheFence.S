    .section ".text"
    .globl _start
_start:
    la x4, buf
    addi x3, x0, 42
    sw x3, 0(x4)
    fence rw, rw
    lw x1, 0(x4)
    la x6, buf2
    addi x5, x0, 99
    sw x5, 0(x6)
    fence.tso
    lw x2, 0(x6)
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
buf:
    .zero 64
    .balign 64
buf2:
    .zero 64